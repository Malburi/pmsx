package kr.pmsx.cm.dash;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.pj.wbs.WbsService;
import kr.pmsx.pj.wbs.WbsService.ProjectProgress;
import kr.pmsx.qa.IssueController;
import kr.pmsx.qa.IssueController.Risk;
import kr.pmsx.rs.TimesheetController;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 대시보드(PM/고객 뷰)·포트폴리오(PMO·임원)·통합 검색 (FR-CM-008·009, docs/12 §7). */
@RestController
@RequestMapping("/api/v1")
public class DashboardController {

    public record Defects(int open, int critical) {
    }

    public record Mm(double planned, double actual) {
    }

    public record Analysis(int done, int total) {
    }

    public record Milestone(String name, LocalDate planEnd, String status) {
    }

    public record ProjectDashboard(ProjectProgress progress, int openIssues, int overdueIssues, List<Risk> risksTop,
                                   Defects defects, Mm mm, Map<String, Integer> deliverables, Analysis analysis,
                                   List<Milestone> milestones, SmSummary sm) {
    }

    /** SM 프로젝트 위젯 (R3): 진행 중 SR·SLA 임박/위반·이번 달 준수율·진행 중 장애. SI 프로젝트면 null */
    public record SmSummary(int openSrs, int dueSoon, int breached, double complianceRate, int openIncidents) {
    }

    public record PortfolioRow(String projectId, String name, String type, String customer, String pm, double planned,
                               double actual, Double spi, int openIssues, int criticalDefects, String health) {
    }

    public record SearchHit(String type, String id, String label, String sub, String projectId) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final WbsService wbs;
    private final IssueController issues;
    private final TimesheetController timesheets;
    private final kr.pmsx.sm.SlaController sla;

    public DashboardController(JdbcTemplate jdbc, ProjectAccess access, WbsService wbs, IssueController issues,
            TimesheetController timesheets, kr.pmsx.sm.SlaController sla) {
        this.jdbc = jdbc;
        this.access = access;
        this.wbs = wbs;
        this.issues = issues;
        this.timesheets = timesheets;
        this.sla = sla;
    }

    @GetMapping("/projects/{projectId}/dashboard")
    public ProjectDashboard dashboard(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(defaultValue = "PM") String view) {
        var a = access.forProject(user, projectId);
        boolean customerView = "CUSTOMER".equals(view) || "CUSTOMER".equals(a.projectRole())
                || "PARTNER".equals(a.projectRole());
        LocalDate today = PjSupport.today();
        ProjectProgress progress = wbs.progress(projectId, today);
        int open = count("SELECT COUNT(*) FROM QA_ISSUE WHERE PROJECT_ID = ? AND STATUS IN ('OPEN', 'IN_PROGRESS')",
                projectId);
        int overdue = count("SELECT COUNT(*) FROM QA_ISSUE WHERE PROJECT_ID = ? AND STATUS IN ('OPEN', 'IN_PROGRESS')"
                + " AND DUE_DT < ?", projectId, today);
        List<Risk> risks = issues.riskList(projectId).stream()
                .filter(r -> !"CLOSED".equals(r.status()) && !"OCCURRED".equals(r.status())).limit(5).toList();
        Defects defects = new Defects(
                count("SELECT COUNT(*) FROM QA_DEFECT WHERE PROJECT_ID = ? AND STATUS <> 'CLOSED'", projectId),
                count("SELECT COUNT(*) FROM QA_DEFECT WHERE PROJECT_ID = ? AND STATUS <> 'CLOSED' AND SEVERITY = 'CRITICAL'",
                        projectId));
        Mm mm = null;
        if (!customerView) {
            Double planned = jdbc.queryForObject("SELECT COALESCE(SUM(PLAN_MM), 0) FROM RS_MM_PLAN WHERE PROJECT_ID = ?"
                    + " AND YM <= ?", Double.class, projectId, YearMonth.from(today).toString());
            mm = new Mm(planned == null ? 0 : Math.round(planned * 100) / 100.0,
                    timesheets.actualMm(projectId, LocalDate.of(2000, 1, 1), today));
        }
        Map<String, Integer> deliverables = new LinkedHashMap<>();
        for (String s : List.of("DRAFT", "REVIEW", "APPROVED", "REJECTED")) {
            deliverables.put(s, 0);
        }
        jdbc.query("SELECT STATUS, COUNT(*) FROM DL_DELIVERABLE WHERE PROJECT_ID = ? GROUP BY STATUS", rs -> {
            deliverables.put(rs.getString(1), rs.getInt(2));
        }, projectId);
        Analysis analysis = jdbc.query("""
                SELECT COUNT(*), SUM(CASE WHEN A.STATUS = 'DONE' THEN 1 ELSE 0 END)
                  FROM AS_SCREEN S JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID LEFT JOIN AS_ANALYSIS A ON A.SCREEN_ID = S.SCREEN_ID
                 WHERE Y.PROJECT_ID = ?""", (rs, i) -> new Analysis(rs.getInt(2), rs.getInt(1)),
                projectId).stream().filter(x -> x.total() > 0).findFirst().orElse(null);
        List<Milestone> milestones = jdbc.query("SELECT NAME, PLAN_END, STATUS FROM PJ_WBS WHERE PROJECT_ID = ?"
                + " AND MILESTONE = TRUE ORDER BY PLAN_END NULLS LAST", (rs, i) -> new Milestone(rs.getString(1),
                rs.getObject(2, LocalDate.class), rs.getString(3)), projectId);
        SmSummary sm = null;
        if ("SM".equals(jdbc.queryForObject("SELECT TYPE FROM PJ_PROJECT WHERE PROJECT_ID = ?", String.class,
                projectId))) {
            var st = sla.compute(projectId, YearMonth.from(today));
            sm = new SmSummary(count("SELECT COUNT(*) FROM SM_SR WHERE PROJECT_ID = ? AND STATUS NOT IN ('RESOLVED',"
                    + " 'CONFIRMED', 'CLOSED', 'REJECTED')", projectId), st.dueSoon().size(), st.breachedList().size(),
                    st.complianceRate(), count("SELECT COUNT(*) FROM SM_INCIDENT WHERE PROJECT_ID = ? AND STATUS = 'OPEN'",
                            projectId));
        }
        return new ProjectDashboard(progress, open, overdue, risks, defects, mm, deliverables, analysis, milestones,
                sm);
    }

    @GetMapping("/dashboard/portfolio")
    public List<PortfolioRow> portfolio(@AuthenticationPrincipal AuthUser user) {
        if (!user.isSysAdmin() && !user.isPmo()) {
            throw ApiException.forbidden();
        }
        LocalDate today = PjSupport.today();
        List<PortfolioRow> out = new ArrayList<>();
        jdbc.query("""
                SELECT P.PROJECT_ID, P.NAME, P.TYPE, P.CUSTOMER,
                       (SELECT MIN(U.NAME) FROM PJ_MEMBER M JOIN CM_USER U ON U.USER_ID = M.USER_ID
                         WHERE M.PROJECT_ID = P.PROJECT_ID AND M.ROLE_ID = 'PM') PM_NM
                  FROM PJ_PROJECT P ORDER BY P.START_DT DESC, P.NAME""", rs -> {
            String pid = rs.getString(1);
            ProjectProgress p = wbs.progress(pid, today);
            int openIssues = count("SELECT COUNT(*) FROM QA_ISSUE WHERE PROJECT_ID = ? AND STATUS IN ('OPEN', 'IN_PROGRESS')",
                    pid);
            int overdue = count("SELECT COUNT(*) FROM QA_ISSUE WHERE PROJECT_ID = ? AND STATUS IN ('OPEN', 'IN_PROGRESS')"
                    + " AND DUE_DT < ?", pid, today);
            int critical = count("SELECT COUNT(*) FROM QA_DEFECT WHERE PROJECT_ID = ? AND STATUS <> 'CLOSED'"
                    + " AND SEVERITY = 'CRITICAL'", pid);
            out.add(new PortfolioRow(pid, rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    p.planned(), p.actual(), p.spi(), openIssues, critical, health(p.spi(), critical, overdue)));
        });
        return out;
    }

    /** RED: SPI<0.9 또는 치명 결함>0, YELLOW: SPI<0.95 또는 지연 이슈>0 (docs/12 §7) */
    public static String health(Double spi, int criticalDefects, int overdueIssues) {
        if ((spi != null && spi < 0.9) || criticalDefects > 0) {
            return "RED";
        }
        if ((spi != null && spi < 0.95) || overdueIssues > 0) {
            return "YELLOW";
        }
        return "GREEN";
    }

    @GetMapping("/search")
    public List<SearchHit> search(@AuthenticationPrincipal AuthUser user, @RequestParam String q,
            @RequestParam(required = false) String projectId) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        List<String> projects;
        if (projectId != null && !projectId.isBlank()) {
            access.forProject(user, projectId);
            projects = List.of(projectId);
        } else if (access.seesAll(user)) {
            projects = jdbc.queryForList("SELECT PROJECT_ID FROM PJ_PROJECT", String.class);
        } else {
            projects = jdbc.queryForList("SELECT PROJECT_ID FROM PJ_MEMBER WHERE USER_ID = ?", String.class,
                    user.userId());
        }
        if (projects.isEmpty()) {
            return List.of();
        }
        String like = "%" + q.trim().toLowerCase() + "%";
        String in = String.join(",", projects.stream().map(x -> "?").toList());
        String[][] sources = {
                {"REQ", "SELECT REQ_ID, REQ_NO || ' ' || TITLE, COALESCE(CATEGORY, ''), PROJECT_ID FROM DL_REQUIREMENT"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(REQ_NO) LIKE ?)"},
                {"ISSUE", "SELECT ISSUE_ID, ISSUE_NO || ' ' || TITLE, STATUS, PROJECT_ID FROM QA_ISSUE"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(ISSUE_NO) LIKE ?)"},
                {"DEFECT", "SELECT DEFECT_ID, DEFECT_NO || ' ' || TITLE, STATUS, PROJECT_ID FROM QA_DEFECT"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(DEFECT_NO) LIKE ?)"},
                {"CR", "SELECT CR_ID, CR_NO || ' ' || TITLE, STATUS, PROJECT_ID FROM QA_CR"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(CR_NO) LIKE ?)"},
                {"TC", "SELECT CASE_ID, TC_NO || ' ' || TITLE, '', PROJECT_ID FROM QA_TEST_CASE"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(TC_NO) LIKE ?)"},
                {"DELIVERABLE", "SELECT DELIVERABLE_ID, NAME, PHASE, PROJECT_ID FROM DL_DELIVERABLE"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(NAME) LIKE ? OR LOWER(PHASE) LIKE ?)"},
                {"WBS", "SELECT WBS_ID, WBS_NO || ' ' || NAME, STATUS, PROJECT_ID FROM PJ_WBS"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(NAME) LIKE ? OR WBS_NO LIKE ?)"},
                {"MEETING", "SELECT MEETING_ID, MEETING_NO || ' ' || TITLE, '', PROJECT_ID FROM QA_MEETING"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(CONTENT) LIKE ?)"},
                {"SR", "SELECT SR_ID, SR_NO || ' ' || TITLE, STATUS, PROJECT_ID FROM SM_SR"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(SR_NO) LIKE ?)"},
                {"KB", "SELECT KB_ID, KB_NO || ' ' || TITLE, COALESCE(TAGS, ''), PROJECT_ID FROM SM_KB"
                        + " WHERE PROJECT_ID IN (" + in + ") AND (LOWER(TITLE) LIKE ? OR LOWER(CONTENT) LIKE ?)"},
                {"SCREEN", "SELECT S.SCREEN_ID, S.SCREEN_CODE || ' ' || S.NAME, Y.NAME, Y.PROJECT_ID FROM AS_SCREEN S"
                        + " JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID WHERE Y.PROJECT_ID IN (" + in + ")"
                        + " AND (LOWER(S.NAME) LIKE ? OR LOWER(S.SCREEN_CODE) LIKE ?)"}};
        List<SearchHit> out = new ArrayList<>();
        for (String[] src : sources) {
            if (out.size() >= 50) {
                break;
            }
            List<Object> args = new ArrayList<>(projects);
            args.add(like);
            args.add(like);
            args.add(50 - out.size());
            out.addAll(jdbc.query(src[1] + " LIMIT ?", (rs, i) -> new SearchHit(src[0], rs.getString(1),
                    rs.getString(2), rs.getString(3), rs.getString(4)), args.toArray()));
        }
        return out;
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
