package kr.pmsx.sm;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import kr.pmsx.cm.excel.ExcelDataset;
import kr.pmsx.cm.excel.ExcelDataset.Column;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.sm.SlaEngine.SrSla;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R3 엑셀 데이터셋 (docs/13): srs, incident-report, changes, ops-assets, kb, monthly-ops-report — 모두 내보내기 전용.
 * 장애보고서·월간 운영보고는 행(타임라인·SR 목록) + 템플릿 공통 셀 값(장애 개요·월 집계)을 제공한다.
 */
@Configuration
public class SmDatasets {

    interface Rows {
        List<Map<String, Object>> rows(String projectId, Map<String, String> params);
    }

    record SmDataset(String id, String name, List<String> params, List<Column> columns, ProjectAccess access,
                     Function<Map<String, String>, String> project, Rows rowsFn,
                     Function<Map<String, String>, Map<String, String>> common) implements ExcelDataset {

        public void checkAccess(AuthUser user, Map<String, String> p, boolean write) {
            if (write) {
                throw ApiException.badRequest("VALIDATION", "가져오기를 지원하지 않는 데이터셋입니다.");
            }
            var a = access.forProject(user, project.apply(p));
            if ("CUSTOMER".equals(a.projectRole()) && "srs".equals(id)) {
                throw ApiException.forbidden();   // 고객은 포털에서 본인 SR 만
            }
        }

        public List<Map<String, Object>> rows(AuthUser user, Map<String, String> p) {
            return rowsFn.rows(project.apply(p), p);
        }

        public Map<String, String> commonValues(Map<String, String> p) {
            Map<String, String> m = new HashMap<>(common == null ? Map.of() : common.apply(p));
            return m;
        }
    }

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;

    public SmDatasets(JdbcTemplate jdbc, ProjectAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    private Function<Map<String, String>, String> byProject() {
        return p -> ExcelDataset.required(p, "projectId");
    }

    private Map<String, String> projectCommon(String projectId) {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT NAME, CUSTOMER FROM PJ_PROJECT WHERE PROJECT_ID = ?", rs -> {
            m.put("projectName", rs.getString(1));
            m.put("customer", rs.getString(2));
        }, projectId);
        return m;
    }

    static String fmt(java.time.OffsetDateTime t) {
        return t == null ? null : t.atZoneSameInstant(Times.ZONE).format(DT);
    }

    @Bean
    ExcelDataset srsDataset(SrService srs) {
        return new SmDataset("srs", "SR 목록", List.of("projectId", "from", "to"), List.of(
                Column.of("srNo", "SR번호"), Column.of("type", "유형"), Column.of("priority", "우선순위"),
                Column.of("title", "제목"), Column.of("requester", "요청자"), Column.of("org", "요청 기관"),
                Column.of("channel", "접수 경로"), Column.of("status", "상태"), Column.of("assignee", "담당자"),
                Column.of("screen", "대상 화면"), Column.of("createdAt", "접수일시"), Column.of("responseDue", "응답기한"),
                Column.of("resolveDue", "해결기한"), Column.of("resolvedAt", "해결일시"), Column.of("slaState", "SLA")),
                access, byProject(), (pid, p) -> {
                    StringBuilder where = new StringBuilder("R.PROJECT_ID = ?");
                    List<Object> args = new ArrayList<>(List.of(pid));
                    var from = kr.pmsx.common.DateParams.from("from", p.get("from"));
                    var to = kr.pmsx.common.DateParams.toExclusive("to", p.get("to"));
                    if (from != null) {
                        where.append(" AND R.CREATED_AT >= ?");
                        args.add(from);
                    }
                    if (to != null) {
                        where.append(" AND R.CREATED_AT < ?");
                        args.add(to);
                    }
                    List<SrService.Row> rows = srs.rows(where + " ORDER BY R.CREATED_AT", args.toArray());
                    Map<String, SrSla> s = srs.evaluate(rows);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (SrService.Row r : rows) {
                        SrSla x = s.get(r.srId());
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("srNo", r.srNo());
                        m.put("type", r.type());
                        m.put("priority", r.priority());
                        m.put("title", r.title());
                        m.put("requester", r.requester().name());
                        m.put("org", r.requester().org());
                        m.put("channel", r.channel());
                        m.put("status", r.status());
                        m.put("assignee", r.assignee() == null ? null : r.assignee().name());
                        m.put("screen", r.screenName());
                        m.put("createdAt", fmt(r.createdAt()));
                        m.put("responseDue", x == null ? null : fmt(x.responseDue()));
                        m.put("resolveDue", x == null ? null : fmt(x.resolveDue()));
                        m.put("resolvedAt", fmt(r.resolvedAt()));
                        m.put("slaState", x == null ? null : x.state());
                        out.add(m);
                    }
                    return out;
                }, p -> projectCommon(ExcelDataset.required(p, "projectId")));
    }

    @Bean
    ExcelDataset incidentReportDataset(IncidentController incidents) {
        Function<Map<String, String>, String> project = p -> incidents.incidentProject(
                ExcelDataset.required(p, "incidentId"));
        return new SmDataset("incident-report", "장애보고서", List.of("incidentId"), List.of(
                Column.of("at", "일시"), Column.of("content", "내용")), access, project, (pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var t : incidents.incident(p.get("incidentId")).timeline()) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("at", fmt(t.at()));
                        m.put("content", t.content());
                        out.add(m);
                    }
                    return out;
                }, p -> {
                    var i = incidents.incident(ExcelDataset.required(p, "incidentId"));
                    Map<String, String> m = projectCommon(project.apply(p));
                    m.put("incidentNo", i.incidentNo());
                    m.put("title", i.title());
                    m.put("grade", String.valueOf(i.grade()));
                    m.put("status", i.status());
                    m.put("startedAt", fmt(i.startedAt()));
                    m.put("detectedAt", fmt(i.detectedAt()));
                    m.put("recoveredAt", fmt(i.recoveredAt()));
                    m.put("durationMinutes", i.durationMinutes() == null ? null : String.valueOf(i.durationMinutes()));
                    m.put("impact", i.impact());
                    m.put("cause", i.cause());
                    m.put("rca", i.rca());
                    m.put("prevention", i.prevention());
                    return m;
                });
    }

    @Bean
    ExcelDataset changesDataset(ChangeController changes) {
        return new SmDataset("changes", "변경·배포 이력", List.of("projectId"), List.of(
                Column.of("changeNo", "변경번호"), Column.of("title", "제목"), Column.of("type", "유형"),
                Column.of("risk", "위험도"), Column.of("status", "상태"), Column.of("scheduledAt", "예정일시"),
                Column.of("deployedAt", "배포일시"), Column.of("cab", "CAB")), access, byProject(), (pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var c : changes.all(pid)) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("changeNo", c.changeNo());
                        m.put("title", c.title());
                        m.put("type", c.type());
                        m.put("risk", c.risk());
                        m.put("status", c.status());
                        m.put("scheduledAt", fmt(c.scheduledAt()));
                        m.put("deployedAt", fmt(c.deployedAt()));
                        m.put("cab", String.join(", ", c.cab().stream().map(x -> x.name() + ":" + x.decision()).toList()));
                        out.add(m);
                    }
                    return out;
                }, p -> projectCommon(ExcelDataset.required(p, "projectId")));
    }

    @Bean
    ExcelDataset opsAssetsDataset() {
        return new SmDataset("ops-assets", "운영 자산", List.of("projectId"), List.of(
                Column.of("type", "유형"), Column.of("name", "이름"), Column.of("host", "호스트"),
                Column.of("owner", "담당"), Column.of("expiresAt", "만료일"), Column.number("daysLeft", "남은 일수"),
                Column.of("note", "비고")), access, byProject(), (pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    jdbc.query("SELECT ASSET_ID, TYPE, NAME, HOST, OWNER, OWNER_ID, EXPIRES_AT, NOTE FROM SM_OPS_ASSET"
                            + " WHERE PROJECT_ID = ? ORDER BY EXPIRES_AT NULLS LAST, NAME", rs -> {
                                var a = RoutineController.mapAsset(rs);
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("type", a.type());
                                m.put("name", a.name());
                                m.put("host", a.host());
                                m.put("owner", a.owner());
                                m.put("expiresAt", a.expiresAt() == null ? null : a.expiresAt().toString());
                                m.put("daysLeft", a.daysLeft());
                                m.put("note", a.note());
                                out.add(m);
                            }, pid);
                    return out;
                }, p -> projectCommon(ExcelDataset.required(p, "projectId")));
    }

    @Bean
    ExcelDataset kbDataset() {
        return new SmDataset("kb", "KB", List.of("projectId"), List.of(
                Column.of("kbNo", "KB번호"), Column.of("title", "제목"), Column.of("tags", "태그"),
                Column.of("content", "내용"), Column.of("updatedAt", "수정일시")), access, byProject(), (pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    jdbc.query("SELECT KB_NO, TITLE, TAGS, CONTENT, UPDATED_AT FROM SM_KB WHERE PROJECT_ID = ?"
                            + " ORDER BY KB_NO", rs -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("kbNo", rs.getString(1));
                                m.put("title", rs.getString(2));
                                m.put("tags", rs.getString(3));
                                m.put("content", rs.getString(4));
                                m.put("updatedAt", fmt(Times.read(rs, "UPDATED_AT")));
                                out.add(m);
                            }, pid);
                    return out;
                }, p -> projectCommon(ExcelDataset.required(p, "projectId")));
    }

    @Bean
    ExcelDataset monthlyOpsReportDataset(MonthlyReportController reports, SrService srs) {
        Function<Map<String, String>, String> project = p -> reports.projectOf(ExcelDataset.required(p, "reportId"));
        return new SmDataset("monthly-ops-report", "월간 운영보고", List.of("reportId"), List.of(
                Column.of("srNo", "SR번호"), Column.of("type", "유형"), Column.of("priority", "우선순위"),
                Column.of("title", "제목"), Column.of("status", "상태"), Column.of("createdAt", "접수일시"),
                Column.of("resolvedAt", "해결일시"), Column.of("slaState", "SLA")), access, project, (pid, p) -> {
                    var r = reports.report(p.get("reportId"));
                    java.time.YearMonth ym = java.time.YearMonth.parse(r.month());
                    var from = ym.atDay(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
                    var to = ym.plusMonths(1).atDay(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
                    List<SrService.Row> rows = srs.rows("R.PROJECT_ID = ? AND R.CREATED_AT >= ? AND R.CREATED_AT < ?"
                            + " ORDER BY R.CREATED_AT", pid, from, to);
                    Map<String, SrSla> s = srs.evaluate(rows);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (SrService.Row x : rows) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("srNo", x.srNo());
                        m.put("type", x.type());
                        m.put("priority", x.priority());
                        m.put("title", x.title());
                        m.put("status", x.status());
                        m.put("createdAt", fmt(x.createdAt()));
                        m.put("resolvedAt", fmt(x.resolvedAt()));
                        m.put("slaState", s.get(x.srId()) == null ? null : s.get(x.srId()).state());
                        out.add(m);
                    }
                    return out;
                }, p -> {
                    var r = reports.report(ExcelDataset.required(p, "reportId"));
                    Map<String, String> m = projectCommon(project.apply(p));
                    m.put("month", r.month());
                    m.put("summary", r.summary());
                    m.put("plan", r.plan());
                    m.put("srTotal", String.valueOf(r.sr().total()));
                    m.put("srCarriedOver", String.valueOf(r.sr().carriedOver()));
                    m.put("complianceRate", String.valueOf(r.sla().complianceRate()));
                    m.put("responseRate", String.valueOf(r.sla().responseRate()));
                    m.put("incidentCount", String.valueOf(r.incidents().size()));
                    m.put("changeCount", String.valueOf(r.changes().size()));
                    return m;
                });
    }
}
