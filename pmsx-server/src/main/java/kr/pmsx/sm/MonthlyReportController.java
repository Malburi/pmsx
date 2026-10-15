package kr.pmsx.sm;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.sm.SlaEngine.SrSla;
import kr.pmsx.sm.SrService.Row;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 월간 운영보고 (FR-SM-012, docs/13 §6): 생성 시점에 집계해 저장하고 총평·계획만 편집한다. */
@RestController
@RequestMapping("/api/v1")
public class MonthlyReportController {

    public record SrStat(int total, Map<String, Integer> byType, Map<String, Integer> byStatus, int carriedOver) {
    }

    public record Breach(String srNo, String title, String reason) {
    }

    public record SlaStat(double complianceRate, double responseRate, List<Breach> breaches) {
    }

    public record IncidentRow(String incidentNo, String title, int grade, Long durationMinutes) {
    }

    public record ChangeRow(String changeNo, String title, OffsetDateTime deployedAt, String status) {
    }

    public record EffortRow(String userId, String name, double hours, double mm) {
    }

    public record ScreenRow(String screenId, String name, int srCount) {
    }

    /** 집계 데이터 (DATA_JSON) */
    public record ReportData(SrStat sr, SlaStat sla, List<IncidentRow> incidents, List<ChangeRow> changes,
                             List<EffortRow> effort, List<ScreenRow> topScreens) {
    }

    public record MonthlyReport(String reportId, String month, String summary, String plan, SrStat sr, SlaStat sla,
                                List<IncidentRow> incidents, List<ChangeRow> changes, List<EffortRow> effort,
                                List<ScreenRow> topScreens, OffsetDateTime updatedAt) {
    }

    public record ReportSummary(String reportId, String month, OffsetDateTime updatedAt) {
    }

    public record GenerateRequest(String month) {
    }

    public record EditRequest(String summary, String plan) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final SrService srs;
    private final SlaController sla;
    private final ObjectMapper om;
    private final PmsxProperties props;
    private final AuditService audit;

    public MonthlyReportController(JdbcTemplate jdbc, ProjectAccess access, SrService srs, SlaController sla,
            ObjectMapper om, PmsxProperties props, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.srs = srs;
        this.sla = sla;
        this.om = om;
        this.props = props;
        this.audit = audit;
    }

    @GetMapping("/projects/{projectId}/monthly-reports")
    public List<ReportSummary> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query("SELECT REPORT_ID, YM, UPDATED_AT FROM SM_MONTHLY_REPORT WHERE PROJECT_ID = ? ORDER BY YM DESC",
                (rs, i) -> new ReportSummary(rs.getString(1), rs.getString(2), Times.read(rs, "UPDATED_AT")),
                projectId);
    }

    @PostMapping("/projects/{projectId}/monthly-reports/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public MonthlyReport generate(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody GenerateRequest req) {
        access.forProject(user, projectId).require(SmPerm.REPORT_EDIT);
        YearMonth ym = SlaController.parse(PjSupport.required("month", req.month(), 7));
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM SM_MONTHLY_REPORT WHERE PROJECT_ID = ? AND YM = ?",
                Integer.class, projectId, ym.toString());
        if (exists != null && exists > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", ym + " 월간 운영보고가 이미 있습니다.");
        }
        ReportData data = aggregate(projectId, ym);
        String summary = String.format("%s 운영 결과: SR %d건 접수(전월 이월 %d건), SLA 준수율 %.1f%%, 장애 %d건, 변경 %d건.",
                ym, data.sr().total(), data.sr().carriedOver(), data.sla().complianceRate(), data.incidents().size(),
                data.changes().size());
        String id = Ulid.next();
        OffsetDateTime now = Times.now();
        jdbc.update("""
                INSERT INTO SM_MONTHLY_REPORT (REPORT_ID, PROJECT_ID, YM, SUMMARY, PLAN_TXT, DATA_JSON, CREATED_AT,
                                               CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, '', ?, ?, ?, ?, ?)""", id, projectId, ym.toString(), summary, json(data), now,
                user.userId(), now, user.userId());
        audit.log(Action.CREATE, "monthly-report:" + id, "월간 운영보고 생성 " + ym, projectId);
        return report(id);
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 월 집계 (docs/13 §6) */
    public ReportData aggregate(String projectId, YearMonth ym) {
        OffsetDateTime from = ym.atDay(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
        OffsetDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
        List<Row> month = srs.rows("R.PROJECT_ID = ? AND R.CREATED_AT >= ? AND R.CREATED_AT < ? ORDER BY R.CREATED_AT",
                projectId, from, to);
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String t : List.of("INQUIRY", "IMPROVE", "DATA", "INCIDENT", "AUTH")) {
            byType.put(t, 0);
        }
        Map<String, Integer> screenCount = new LinkedHashMap<>();
        Map<String, String> screenName = new LinkedHashMap<>();
        for (Row r : month) {
            byType.merge(r.type(), 1, Integer::sum);
            byStatus.merge(r.status(), 1, Integer::sum);
            if (r.screenId() != null) {
                screenCount.merge(r.screenId(), 1, Integer::sum);
                screenName.put(r.screenId(), r.screenName());
            }
        }
        Integer carried = jdbc.queryForObject("""
                SELECT COUNT(*) FROM SM_SR WHERE PROJECT_ID = ? AND CREATED_AT < ? AND STATUS <> 'REJECTED'
                   AND (CLOSED_AT IS NULL OR CLOSED_AT >= ?)""", Integer.class, projectId, from, from);
        SrStat sr = new SrStat(month.size(), byType, byStatus, carried == null ? 0 : carried);

        SlaController.SlaStatus st = sla.compute(projectId, ym);
        Map<String, SrSla> s = srs.evaluate(month);
        List<Breach> breaches = new ArrayList<>();
        for (Row r : month) {
            SrSla x = s.get(r.srId());
            if (x != null && !"REJECTED".equals(r.status()) && (x.responseBreached() || x.resolveBreached())) {
                breaches.add(new Breach(r.srNo(), r.title(), x.responseBreached() && x.resolveBreached() ? "응답·해결 지연"
                        : x.responseBreached() ? "응답 지연" : "해결 지연"));
            }
        }
        SlaStat slaStat = new SlaStat(st.complianceRate(), st.responseRate(), breaches);

        List<IncidentRow> incidents = jdbc.query("""
                SELECT INCIDENT_NO, TITLE, GRADE, STARTED_AT, RECOVERED_AT FROM SM_INCIDENT
                 WHERE PROJECT_ID = ? AND STARTED_AT >= ? AND STARTED_AT < ? ORDER BY STARTED_AT""", (rs, i) -> {
            OffsetDateTime a = Times.read(rs, "STARTED_AT");
            OffsetDateTime b = Times.read(rs, "RECOVERED_AT");
            return new IncidentRow(rs.getString(1), rs.getString(2), rs.getInt(3),
                    a == null || b == null ? null : java.time.Duration.between(a, b).toMinutes());
        }, projectId, from, to);
        List<ChangeRow> changes = jdbc.query("""
                SELECT CHANGE_NO, TITLE, DEPLOYED_AT, STATUS FROM SM_CHANGE WHERE PROJECT_ID = ?
                   AND ((DEPLOYED_AT >= ? AND DEPLOYED_AT < ?) OR (DEPLOYED_AT IS NULL AND SCHEDULED_AT >= ? AND SCHEDULED_AT < ?))
                 ORDER BY COALESCE(DEPLOYED_AT, SCHEDULED_AT)""", (rs, i) -> new ChangeRow(rs.getString(1),
                rs.getString(2), Times.read(rs, "DEPLOYED_AT"), rs.getString(4)), projectId, from, to, from, to);
        double hpm = props.hoursPerMonth();
        List<EffortRow> effort = jdbc.query("""
                SELECT T.USER_ID, U.NAME, SUM(E.HOURS) FROM RS_TIMESHEET_ENTRY E
                  JOIN RS_TIMESHEET T ON T.TIMESHEET_ID = E.TIMESHEET_ID JOIN CM_USER U ON U.USER_ID = T.USER_ID
                 WHERE E.PROJECT_ID = ? AND E.WORK_DT >= ? AND E.WORK_DT <= ? AND T.STATUS IN ('SUBMITTED', 'APPROVED')
                 GROUP BY T.USER_ID, U.NAME ORDER BY U.NAME""", (rs, i) -> {
            double h = rs.getDouble(3);
            return new EffortRow(rs.getString(1), rs.getString(2), Math.round(h * 10) / 10.0,
                    Math.round(h / hpm * 100) / 100.0);
        }, projectId, ym.atDay(1), ym.atEndOfMonth());
        List<ScreenRow> top = screenCount.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
                .map(e -> new ScreenRow(e.getKey(), screenName.get(e.getKey()), e.getValue())).toList();
        return new ReportData(sr, slaStat, incidents, changes, effort, top);
    }

    public MonthlyReport report(String id) {
        return jdbc.query("SELECT REPORT_ID, YM, SUMMARY, PLAN_TXT, DATA_JSON, UPDATED_AT FROM SM_MONTHLY_REPORT"
                + " WHERE REPORT_ID = ?", (rs, i) -> {
                    ReportData d;
                    try {
                        d = om.readValue(rs.getString(5), ReportData.class);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return new MonthlyReport(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            d.sr(), d.sla(), d.incidents(), d.changes(), d.effort(), d.topScreens(),
                            Times.read(rs, "UPDATED_AT"));
                }, id).stream().findFirst().orElseThrow(() -> ApiException.notFound("월간 운영보고"));
    }

    public String projectOf(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_MONTHLY_REPORT WHERE REPORT_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("월간 운영보고"));
    }

    @GetMapping("/monthly-reports/{reportId}")
    public MonthlyReport get(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId) {
        access.forProject(user, projectOf(reportId));
        return report(reportId);
    }

    @PutMapping("/monthly-reports/{reportId}")
    @Transactional
    public MonthlyReport update(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId,
            @RequestBody EditRequest req) {
        String projectId = projectOf(reportId);
        access.forProject(user, projectId).require(SmPerm.REPORT_EDIT);
        jdbc.update("UPDATE SM_MONTHLY_REPORT SET SUMMARY = ?, PLAN_TXT = ?, UPDATED_AT = ?, UPDATED_BY = ?"
                + " WHERE REPORT_ID = ?", PjSupport.optional("summary", req.summary(), 20_000),
                PjSupport.optional("plan", req.plan(), 20_000), Times.now(), user.userId(), reportId);
        audit.log(Action.UPDATE, "monthly-report:" + reportId, "월간 운영보고 수정", projectId);
        return report(reportId);
    }
}
