package kr.pmsx.pj.wbs;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.WorkCalendar;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.rs.TimesheetController;
import org.springframework.dao.DuplicateKeyException;
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

/** 주간보고 자동 집계·편집·제출 (FR-PJ-009, docs/12 §3). 작성 PM·PL, 조회 PMO·PM·PL·CUSTOMER. */
@RestController
@RequestMapping("/api/v1")
public class WeeklyReportController {

    public record Progress(double planned, double actual, double prevActual) {
    }

    public record TaskLine(String wbsNo, String name, String assignees, double progress, boolean done, String note) {
    }

    public record NextLine(String wbsNo, String name, String assignees, LocalDate planEnd, String note) {
    }

    public record IssueLine(String no, String title, String status, String owner, LocalDate dueDate) {
    }

    public record RiskLine(String no, String title, int score, String response) {
    }

    public record Mm(double planned, double actual) {
    }

    public record Analysis(int done, int total) {
    }

    /** 저장 본문 (reportId·projectId·weekStart·status 는 컬럼) */
    public record Content(String summary, Progress progress, List<TaskLine> thisWeek, List<NextLine> nextWeek,
                          List<IssueLine> issues, List<RiskLine> risks, Mm mm, Analysis analysis) {
    }

    public record WeeklyReport(String reportId, String projectId, LocalDate weekStart, String status, String summary,
                               Progress progress, List<TaskLine> thisWeek, List<NextLine> nextWeek,
                               List<IssueLine> issues, List<RiskLine> risks, Mm mm, Analysis analysis) {
    }

    public record ReportSummary(String reportId, LocalDate weekStart, String status, OffsetDateTime updatedAt) {
    }

    public record GenerateRequest(LocalDate weekStart) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final ProjectAccess access;
    private final WbsService wbs;
    private final WorkCalendar calendar;
    private final TimesheetController timesheets;
    private final AuditService audit;

    public WeeklyReportController(JdbcTemplate jdbc, ObjectMapper om, ProjectAccess access, WbsService wbs,
            WorkCalendar calendar, TimesheetController timesheets, AuditService audit) {
        this.jdbc = jdbc;
        this.om = om;
        this.access = access;
        this.wbs = wbs;
        this.calendar = calendar;
        this.timesheets = timesheets;
        this.audit = audit;
    }

    @GetMapping("/projects/{projectId}/weekly-reports")
    public List<ReportSummary> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId).require(Perm.WEEKLY_READ);
        return jdbc.query("SELECT REPORT_ID, WEEK_START, STATUS, UPDATED_AT FROM PJ_WEEKLY_REPORT WHERE PROJECT_ID = ?"
                + " ORDER BY WEEK_START DESC", (rs, i) -> new ReportSummary(rs.getString(1),
                rs.getObject(2, LocalDate.class), rs.getString(3), Times.read(rs, "UPDATED_AT")), projectId);
    }

    @PostMapping("/projects/{projectId}/weekly-reports/generate")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public WeeklyReport generate(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody GenerateRequest req) {
        access.forProject(user, projectId).require(Perm.WEEKLY_EDIT);
        if (req.weekStart() == null) {
            throw PjSupport.validation("weekStart", "필수 입력입니다.");
        }
        LocalDate ws = req.weekStart().with(DayOfWeek.MONDAY);
        Content c = compose(projectId, ws);
        String id = Ulid.next();
        try {
            jdbc.update("""
                    INSERT INTO PJ_WEEKLY_REPORT (REPORT_ID, PROJECT_ID, WEEK_START, STATUS, CONTENT_JSON, CREATED_AT,
                                                  CREATED_BY, UPDATED_AT, UPDATED_BY)
                    VALUES (?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", id, projectId, ws, json(c), Times.now(), user.userId(),
                    Times.now(), user.userId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", ws + " 주 보고서가 이미 있습니다.");
        }
        audit.log(Action.CREATE, "weekly:" + id, "주간보고 생성 " + ws, projectId);
        return load(id);
    }

    /** 자동 집계 (docs/12 §3) */
    public Content compose(String projectId, LocalDate ws) {
        LocalDate we = ws.plusDays(6);
        LocalDate friday = ws.plusDays(4);
        WorkCalendar.Days cal = calendar.load();
        WbsService.Tree t = wbs.tree(projectId, cal);
        WbsService.ProjectProgress prog = wbs.progress(projectId, friday);
        Double prev = jdbc.query("SELECT CONTENT_JSON FROM PJ_WEEKLY_REPORT WHERE PROJECT_ID = ? AND WEEK_START < ?"
                        + " ORDER BY WEEK_START DESC LIMIT 1", (rs, i) -> rs.getString(1), projectId, ws).stream()
                .findFirst().map(j -> parse(j).progress()).map(Progress::actual).orElse(null);
        List<TaskLine> thisWeek = new ArrayList<>();
        List<NextLine> nextWeek = new ArrayList<>();
        for (WbsService.Row r : WbsService.ordered(t)) {
            if (!r.leaf()) {
                continue;
            }
            String who = r.assignees.stream().map(UserRef::name).collect(Collectors.joining(", "));
            boolean planned = overlaps(r.planStart, r.planEnd, ws, we);
            boolean worked = (r.actStart != null && !r.actStart.isAfter(we) && (r.actEnd == null || !r.actEnd.isBefore(ws)));
            if (planned || worked) {
                thisWeek.add(new TaskLine(r.wbsNo, r.name, who, r.progress, "DONE".equals(r.status), ""));
            }
            if (!"DONE".equals(r.status) && overlaps(r.planStart, r.planEnd, ws.plusWeeks(1), we.plusWeeks(1))) {
                nextWeek.add(new NextLine(r.wbsNo, r.name, who, r.planEnd, ""));
            }
        }
        List<IssueLine> issues = jdbc.query("""
                SELECT I.ISSUE_NO, I.TITLE, I.STATUS, U.NAME, I.DUE_DT FROM QA_ISSUE I LEFT JOIN CM_USER U ON U.USER_ID = I.OWNER_ID
                 WHERE I.PROJECT_ID = ? AND I.STATUS IN ('OPEN', 'IN_PROGRESS') ORDER BY I.PRIORITY, I.ISSUE_NO""",
                (rs, i) -> new IssueLine(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getObject(5, LocalDate.class)), projectId);
        List<RiskLine> risks = jdbc.query("""
                SELECT RISK_NO, TITLE, PROBABILITY * IMPACT, RESPONSE FROM QA_RISK
                 WHERE PROJECT_ID = ? AND STATUS IN ('IDENTIFIED', 'MITIGATING') AND PROBABILITY * IMPACT >= 9
                 ORDER BY PROBABILITY * IMPACT DESC, RISK_NO LIMIT 10""",
                (rs, i) -> new RiskLine(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4)), projectId);
        // 주 계획 MM = Σ 월 계획 × (그 주 영업일 / 그 달 영업일)
        double plannedMm = 0;
        for (LocalDate d = ws; !d.isAfter(we); ) {
            YearMonth ym = YearMonth.from(d);
            LocalDate segEnd = ym.atEndOfMonth().isBefore(we) ? ym.atEndOfMonth() : we;
            Double monthPlan = jdbc.queryForObject("SELECT COALESCE(SUM(PLAN_MM), 0) FROM RS_MM_PLAN WHERE PROJECT_ID = ?"
                    + " AND YM = ?", Double.class, projectId, ym.toString());
            int monthDays = Math.max(1, cal.count(ym.atDay(1), ym.atEndOfMonth()));
            plannedMm += (monthPlan == null ? 0 : monthPlan) * cal.count(d, segEnd) / monthDays;
            d = segEnd.plusDays(1);
        }
        double actualMm = timesheets.actualMm(projectId, ws, we);
        Analysis analysis = jdbc.query("""
                SELECT COUNT(*), SUM(CASE WHEN A.STATUS = 'DONE' THEN 1 ELSE 0 END)
                  FROM AS_SCREEN S JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID LEFT JOIN AS_ANALYSIS A ON A.SCREEN_ID = S.SCREEN_ID
                 WHERE Y.PROJECT_ID = ?""", (rs, i) -> new Analysis(rs.getInt(2), rs.getInt(1)),
                projectId).stream().filter(x -> x.total() > 0).findFirst().orElse(null);
        long doneCount = thisWeek.stream().filter(TaskLine::done).count();
        String summary = String.format("계획 진척 %.1f%% / 실적 %.1f%%%s. 금주 작업 %d건 중 완료 %d건, 미결 이슈 %d건.",
                prog.planned(), prog.actual(), prog.spi() == null ? "" : String.format(" (SPI %.2f)", prog.spi()),
                thisWeek.size(), doneCount, issues.size());
        return new Content(summary, new Progress(prog.planned(), prog.actual(), prev == null ? 0 : prev), thisWeek,
                nextWeek, issues, risks, new Mm(Math.round(plannedMm * 100) / 100.0, actualMm), analysis);
    }

    private static boolean overlaps(LocalDate s, LocalDate e, LocalDate from, LocalDate to) {
        return s != null && e != null && !s.isAfter(to) && !e.isBefore(from);
    }

    @GetMapping("/weekly-reports/{reportId}")
    public WeeklyReport get(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId) {
        access.forProject(user, projectOf(reportId)).require(Perm.WEEKLY_READ);
        return load(reportId);
    }

    @PutMapping("/weekly-reports/{reportId}")
    @Transactional
    public WeeklyReport update(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId,
            @RequestBody WeeklyReport req) {
        String projectId = projectOf(reportId);
        access.forProject(user, projectId).require(Perm.WEEKLY_EDIT);
        WeeklyReport cur = load(reportId);
        if ("SUBMITTED".equals(cur.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "제출된 보고서는 수정할 수 없습니다.");
        }
        Content c = new Content(PjSupport.optional("summary", req.summary(), 10_000),
                req.progress() == null ? cur.progress() : req.progress(),
                req.thisWeek() == null ? cur.thisWeek() : req.thisWeek(),
                req.nextWeek() == null ? cur.nextWeek() : req.nextWeek(),
                req.issues() == null ? cur.issues() : req.issues(), req.risks() == null ? cur.risks() : req.risks(),
                req.mm() == null ? cur.mm() : req.mm(), req.analysis() == null ? cur.analysis() : req.analysis());
        jdbc.update("UPDATE PJ_WEEKLY_REPORT SET CONTENT_JSON = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE REPORT_ID = ?",
                json(c), Times.now(), user.userId(), reportId);
        audit.log(Action.UPDATE, "weekly:" + reportId, "주간보고 수정", projectId);
        return load(reportId);
    }

    @PostMapping("/weekly-reports/{reportId}/submit")
    @Transactional
    public WeeklyReport submit(@AuthenticationPrincipal AuthUser user, @PathVariable String reportId) {
        String projectId = projectOf(reportId);
        access.forProject(user, projectId).require(Perm.WEEKLY_EDIT);
        int n = jdbc.update("UPDATE PJ_WEEKLY_REPORT SET STATUS = 'SUBMITTED', SUBMITTED_AT = ?, UPDATED_AT = ?,"
                + " UPDATED_BY = ? WHERE REPORT_ID = ? AND STATUS = 'DRAFT'", Times.now(), Times.now(), user.userId(),
                reportId);
        if (n == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 제출된 보고서입니다.");
        }
        audit.log(Action.UPDATE, "weekly:" + reportId, "주간보고 제출", projectId);
        return load(reportId);
    }

    public String projectOf(String reportId) {
        return jdbc.query("SELECT PROJECT_ID FROM PJ_WEEKLY_REPORT WHERE REPORT_ID = ?", (rs, i) -> rs.getString(1),
                reportId).stream().findFirst().orElseThrow(() -> ApiException.notFound("주간보고"));
    }

    public WeeklyReport load(String reportId) {
        return jdbc.query("SELECT REPORT_ID, PROJECT_ID, WEEK_START, STATUS, CONTENT_JSON FROM PJ_WEEKLY_REPORT"
                + " WHERE REPORT_ID = ?", (rs, i) -> {
            Content c = parse(rs.getString(5));
            return new WeeklyReport(rs.getString(1), rs.getString(2), rs.getObject(3, LocalDate.class),
                    rs.getString(4), c.summary(), c.progress(), c.thisWeek(), c.nextWeek(), c.issues(), c.risks(),
                    c.mm(), c.analysis());
        }, reportId).stream().findFirst().orElseThrow(() -> ApiException.notFound("주간보고"));
    }

    private String json(Content c) {
        try {
            return om.writeValueAsString(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Content parse(String json) {
        try {
            Content c = om.readValue(json, Content.class);
            return new Content(c.summary(), c.progress() == null ? new Progress(0, 0, 0) : c.progress(),
                    c.thisWeek() == null ? List.of() : c.thisWeek(), c.nextWeek() == null ? List.of() : c.nextWeek(),
                    c.issues() == null ? List.of() : c.issues(), c.risks() == null ? List.of() : c.risks(),
                    c.mm() == null ? new Mm(0, 0) : c.mm(), c.analysis());
        } catch (Exception e) {
            return new Content(null, new Progress(0, 0, 0), List.of(), List.of(), List.of(), List.of(), new Mm(0, 0),
                    null);
        }
    }
}
