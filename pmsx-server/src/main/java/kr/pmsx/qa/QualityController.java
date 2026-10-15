package kr.pmsx.qa;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import kr.pmsx.cm.notify.NotificationService;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.pj.ProjectSeq;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 변경요청·테스트(시나리오/케이스/회차/결과)·결함·품질 리포트 (FR-QA-004~009, docs/12 §6). */
@RestController
@RequestMapping("/api/v1")
public class QualityController {

    // ── 변경요청 ──
    public record Impact(String scope, double mmDelta, int scheduleDeltaDays) {
    }

    public record ChangeRequest(String crId, String crNo, String title, String reason, String requester, Impact impact,
                                String status, List<String> reqIds, List<String> wbsIds, OffsetDateTime updatedAt) {
    }

    public record TransitionRequest(String to, String comment) {
    }

    // ── 테스트 ──
    public record TestCase(String caseId, String tcNo, String title, String precondition, String steps,
                           String expected, List<String> reqIds, String screenId, String lastResult) {
    }

    public record TestScenario(String scenarioId, String level, String name, List<TestCase> cases) {
    }

    public record TestRound(String roundId, String name, String level, OffsetDateTime createdAt) {
    }

    public record TestResult(String caseId, String result, String note, String defectId) {
    }

    // ── 결함 ──
    public record Defect(String defectId, String defectNo, String title, String description, String severity,
                         String priority, String status, UserRef assignee, UserRef reporter, String caseId,
                         String screenId, String elementId, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record DefectRequest(String title, String description, String severity, String priority, String assigneeId,
                                String caseId, String screenId, String elementId) {
    }

    public record TrendPoint(LocalDate date, int opened, int closed, int open) {
    }

    public record RoundProgress(String roundId, String name, int total, int pass, int fail, int block, int notRun) {
    }

    public record ScreenDefects(String screenId, String name, int defects) {
    }

    public record QualityReport(List<TrendPoint> defectTrend, Map<String, Integer> bySeverity,
                                Map<String, Integer> byStatus, double reopenRate, List<RoundProgress> testProgress,
                                List<ScreenDefects> topScreens) {
    }

    /** 결함 허용 전이 (docs/12 §6) */
    static final Map<String, Set<String>> DEFECT_FLOW = Map.of(
            "NEW", Set.of("ASSIGNED"),
            "ASSIGNED", Set.of("FIXED"),
            "FIXED", Set.of("VERIFIED", "REOPENED"),
            "VERIFIED", Set.of("CLOSED", "REOPENED"),
            "REOPENED", Set.of("ASSIGNED"),
            "CLOSED", Set.of());

    static final Map<String, Set<String>> CR_FLOW = Map.of(
            "REQUESTED", Set.of("ANALYZED", "REJECTED"),
            "ANALYZED", Set.of("APPROVED", "REJECTED"),
            "APPROVED", Set.of("APPLIED"),
            "REJECTED", Set.of(),
            "APPLIED", Set.of());

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final PjSupport support;
    private final ProjectSeq seq;
    private final QaSupport qa;
    private final NotificationService notify;
    private final AuditService audit;

    public QualityController(JdbcTemplate jdbc, ProjectAccess access, PjSupport support, ProjectSeq seq, QaSupport qa,
            NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.support = support;
        this.seq = seq;
        this.qa = qa;
        this.notify = notify;
        this.audit = audit;
    }

    private String projectOf(String table, String idCol, String id, String what) {
        return jdbc.query("SELECT PROJECT_ID FROM " + table + " WHERE " + idCol + " = ?", (rs, i) -> rs.getString(1),
                id).stream().findFirst().orElseThrow(() -> ApiException.notFound(what));
    }

    // ───────────────────────── 변경요청 ─────────────────────────

    private static final String CR_COLS = "SELECT CR_ID, CR_NO, TITLE, REASON, REQUESTER, IMPACT_SCOPE, MM_DELTA,"
            + " SCHEDULE_DELTA, STATUS, REQ_IDS, WBS_IDS, UPDATED_AT FROM QA_CR";

    private static ChangeRequest mapCr(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ChangeRequest(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                new Impact(rs.getString(6), rs.getBigDecimal(7).doubleValue(), rs.getInt(8)), rs.getString(9),
                PjSupport.csv(rs.getString(10)), PjSupport.csv(rs.getString(11)), Times.read(rs, "UPDATED_AT"));
    }

    @GetMapping("/projects/{projectId}/change-requests")
    public List<ChangeRequest> crs(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query(CR_COLS + " WHERE PROJECT_ID = ? ORDER BY CR_NO DESC", (rs, i) -> mapCr(rs), projectId);
    }

    private ChangeRequest cr(String id) {
        return jdbc.query(CR_COLS + " WHERE CR_ID = ?", (rs, i) -> mapCr(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("변경요청"));
    }

    @GetMapping("/change-requests/{crId}")
    public ChangeRequest getCr(@AuthenticationPrincipal AuthUser user, @PathVariable String crId) {
        access.forProject(user, projectOf("QA_CR", "CR_ID", crId, "변경요청"));
        return cr(crId);
    }

    @PostMapping("/projects/{projectId}/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ChangeRequest createCr(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody ChangeRequest req) {
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        validateCr(projectId, req);
        String id = Ulid.next();
        Impact im = req.impact() == null ? new Impact(null, 0, 0) : req.impact();
        jdbc.update("""
                INSERT INTO QA_CR (CR_ID, PROJECT_ID, CR_NO, TITLE, REASON, REQUESTER, IMPACT_SCOPE, MM_DELTA, SCHEDULE_DELTA,
                                   STATUS, REQ_IDS, WBS_IDS, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?)""", id, projectId,
                seq.next(projectId, "CR"), req.title().trim(), req.reason(),
                PjSupport.optional("requester", req.requester(), 100), im.scope(), BigDecimal.valueOf(im.mmDelta()),
                im.scheduleDeltaDays(), PjSupport.csv(req.reqIds()), PjSupport.csv(req.wbsIds()), Times.now(),
                user.userId(), Times.now(), user.userId());
        support.hist("CR", id, "CREATE", null, "REQUESTED", null);
        audit.log(Action.CREATE, "cr:" + id, "변경요청 등록", projectId);
        return cr(id);
    }

    private void validateCr(String projectId, ChangeRequest req) {
        PjSupport.required("title", req.title(), 300);
        for (String r : req.reqIds() == null ? List.<String>of() : req.reqIds()) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM DL_REQUIREMENT WHERE REQ_ID = ? AND PROJECT_ID = ?",
                    Integer.class, r, projectId);
            if (n == null || n == 0) {
                throw PjSupport.validation("reqIds", "이 프로젝트의 요구사항이 아닙니다: " + r);
            }
        }
        for (String w : req.wbsIds() == null ? List.<String>of() : req.wbsIds()) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_WBS WHERE WBS_ID = ? AND PROJECT_ID = ?",
                    Integer.class, w, projectId);
            if (n == null || n == 0) {
                throw PjSupport.validation("wbsIds", "이 프로젝트의 WBS 항목이 아닙니다: " + w);
            }
        }
    }

    @PutMapping("/change-requests/{crId}")
    @Transactional
    public ChangeRequest updateCr(@AuthenticationPrincipal AuthUser user, @PathVariable String crId,
            @RequestBody ChangeRequest req) {
        String projectId = projectOf("QA_CR", "CR_ID", crId, "변경요청");
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        ChangeRequest cur = cr(crId);
        if (Set.of("APPROVED", "APPLIED", "REJECTED").contains(cur.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "결정된 변경요청은 수정할 수 없습니다.");
        }
        validateCr(projectId, req);
        Impact im = req.impact() == null ? cur.impact() : req.impact();
        jdbc.update("""
                UPDATE QA_CR SET TITLE = ?, REASON = ?, REQUESTER = ?, IMPACT_SCOPE = ?, MM_DELTA = ?, SCHEDULE_DELTA = ?,
                       REQ_IDS = ?, WBS_IDS = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE CR_ID = ?""", req.title().trim(),
                req.reason(), PjSupport.optional("requester", req.requester(), 100), im.scope(),
                BigDecimal.valueOf(im.mmDelta()), im.scheduleDeltaDays(), PjSupport.csv(req.reqIds()),
                PjSupport.csv(req.wbsIds()), Times.now(), user.userId(), crId);
        audit.log(Action.UPDATE, "cr:" + crId, "변경요청 수정", projectId);
        return cr(crId);
    }

    @PostMapping("/change-requests/{crId}/transition")
    @Transactional
    public ChangeRequest transitionCr(@AuthenticationPrincipal AuthUser user, @PathVariable String crId,
            @RequestBody TransitionRequest req) {
        String projectId = projectOf("QA_CR", "CR_ID", crId, "변경요청");
        var a = access.forProject(user, projectId);
        ChangeRequest cur = cr(crId);
        String to = PjSupport.oneOf("to", req.to(), null, "REQUESTED", "ANALYZED", "APPROVED", "REJECTED", "APPLIED");
        if (!CR_FLOW.getOrDefault(cur.status(), Set.of()).contains(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", cur.status() + " → " + to
                    + " 전이는 허용되지 않습니다.");
        }
        a.require("APPROVED".equals(to) || "REJECTED".equals(to) ? Perm.CR_APPROVE : Set.of("PM", "PL"));
        jdbc.update("UPDATE QA_CR SET STATUS = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE CR_ID = ?", to, Times.now(),
                user.userId(), crId);
        support.hist("CR", crId, "TRANSITION", cur.status(), to, req.comment());
        audit.log(Action.UPDATE, "cr:" + crId, "변경요청 " + cur.status() + "→" + to, projectId);
        return cr(crId);
    }

    @GetMapping("/change-requests/{crId}/history")
    public List<PjSupport.Hist> crHistory(@AuthenticationPrincipal AuthUser user, @PathVariable String crId) {
        access.forProject(user, projectOf("QA_CR", "CR_ID", crId, "변경요청"));
        return support.hist("CR", crId);
    }

    // ───────────────────────── 테스트 ─────────────────────────

    @GetMapping("/projects/{projectId}/test-scenarios")
    public List<TestScenario> scenarios(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return scenarioTree(projectId, null);
    }

    public List<TestScenario> scenarioTree(String projectId, String onlyScenario) {
        Map<String, String> last = lastResults(projectId);
        Map<String, List<TestCase>> cases = new HashMap<>();
        jdbc.query("SELECT CASE_ID, SCENARIO_ID, TC_NO, TITLE, PRECONDITION, STEPS, EXPECTED, REQ_IDS, SCREEN_ID"
                + " FROM QA_TEST_CASE WHERE PROJECT_ID = ? ORDER BY ORD, TC_NO", rs -> {
            cases.computeIfAbsent(rs.getString(2), k -> new ArrayList<>()).add(new TestCase(rs.getString(1),
                    rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                    PjSupport.csv(rs.getString(8)), rs.getString(9), last.get(rs.getString(1))));
        }, projectId);
        return jdbc.query("SELECT SCENARIO_ID, LVL, NAME FROM QA_TEST_SCENARIO WHERE PROJECT_ID = ?"
                        + (onlyScenario == null ? "" : " AND SCENARIO_ID = ?") + " ORDER BY ORD, CREATED_AT",
                (rs, i) -> new TestScenario(rs.getString(1), rs.getString(2), rs.getString(3),
                        cases.getOrDefault(rs.getString(1), List.of())),
                onlyScenario == null ? new Object[] {projectId} : new Object[] {projectId, onlyScenario});
    }

    /** 케이스별 최근 회차 결과 */
    Map<String, String> lastResults(String projectId) {
        Map<String, String> last = new HashMap<>();
        jdbc.query("""
                SELECT R.CASE_ID, R.RESULT_CD FROM QA_TEST_RESULT R JOIN QA_TEST_ROUND D ON D.ROUND_ID = R.ROUND_ID
                 WHERE D.PROJECT_ID = ? ORDER BY D.CREATED_AT, D.ROUND_ID""", rs -> {
            last.put(rs.getString(1), rs.getString(2));
        }, projectId);
        return last;
    }

    @PostMapping("/projects/{projectId}/test-scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TestScenario createScenario(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody TestScenario req) {
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        String id = Ulid.next();
        Integer ord = jdbc.queryForObject("SELECT COALESCE(MAX(ORD), 0) + 1 FROM QA_TEST_SCENARIO WHERE PROJECT_ID = ?",
                Integer.class, projectId);
        jdbc.update("INSERT INTO QA_TEST_SCENARIO (SCENARIO_ID, PROJECT_ID, LVL, NAME, ORD, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
                id, projectId, PjSupport.oneOf("level", req.level(), null, "UNIT", "INTEGRATION", "ACCEPTANCE"),
                PjSupport.required("name", req.name(), 300), ord, Times.now());
        for (TestCase c : req.cases() == null ? List.<TestCase>of() : req.cases()) {
            insertCase(projectId, id, c);
        }
        audit.log(Action.CREATE, "test-scenario:" + id, "테스트 시나리오 등록", projectId);
        return scenarioTree(projectId, id).get(0);
    }

    @PutMapping("/test-scenarios/{id}")
    public TestScenario updateScenario(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody TestScenario req) {
        String projectId = projectOf("QA_TEST_SCENARIO", "SCENARIO_ID", id, "테스트 시나리오");
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        jdbc.update("UPDATE QA_TEST_SCENARIO SET LVL = ?, NAME = ? WHERE SCENARIO_ID = ?",
                PjSupport.oneOf("level", req.level(), null, "UNIT", "INTEGRATION", "ACCEPTANCE"),
                PjSupport.required("name", req.name(), 300), id);
        return scenarioTree(projectId, id).get(0);
    }

    @DeleteMapping("/test-scenarios/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteScenario(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        String projectId = projectOf("QA_TEST_SCENARIO", "SCENARIO_ID", id, "테스트 시나리오");
        access.forProject(user, projectId).require(Set.of("PM", "QA"));
        for (String c : jdbc.queryForList("SELECT CASE_ID FROM QA_TEST_CASE WHERE SCENARIO_ID = ?", String.class, id)) {
            deleteCaseRows(c);
        }
        jdbc.update("DELETE FROM QA_TEST_SCENARIO WHERE SCENARIO_ID = ?", id);
        audit.log(Action.DELETE, "test-scenario:" + id, "테스트 시나리오 삭제", projectId);
    }

    @PostMapping("/test-scenarios/{id}/cases")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public TestCase addCase(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody TestCase req) {
        String projectId = projectOf("QA_TEST_SCENARIO", "SCENARIO_ID", id, "테스트 시나리오");
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        String caseId = insertCase(projectId, id, req);
        return testCase(projectId, caseId);
    }

    public String insertCase(String projectId, String scenarioId, TestCase c) {
        validateCase(projectId, c);
        String id = Ulid.next();
        Integer ord = jdbc.queryForObject("SELECT COALESCE(MAX(ORD), 0) + 1 FROM QA_TEST_CASE WHERE SCENARIO_ID = ?",
                Integer.class, scenarioId);
        String no = c.tcNo() == null || c.tcNo().isBlank() ? seq.next(projectId, "TC") : c.tcNo().trim();
        seq.bumpTo(projectId, "TC", no);
        jdbc.update("""
                INSERT INTO QA_TEST_CASE (CASE_ID, SCENARIO_ID, PROJECT_ID, TC_NO, TITLE, PRECONDITION, STEPS, EXPECTED,
                                          REQ_IDS, SCREEN_ID, ORD, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, scenarioId, projectId, no, c.title().trim(),
                c.precondition(), c.steps(), c.expected(), PjSupport.csv(c.reqIds()), c.screenId(), ord, Times.now());
        return id;
    }

    private void validateCase(String projectId, TestCase c) {
        PjSupport.required("title", c.title(), 300);
        if (c.screenId() != null && !projectId.equals(qa.projectOfScreen(c.screenId()))) {
            throw PjSupport.validation("screenId", "이 프로젝트의 화면이 아닙니다.");
        }
        for (String r : c.reqIds() == null ? List.<String>of() : c.reqIds()) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM DL_REQUIREMENT WHERE REQ_ID = ? AND PROJECT_ID = ?",
                    Integer.class, r, projectId);
            if (n == null || n == 0) {
                throw PjSupport.validation("reqIds", "이 프로젝트의 요구사항이 아닙니다: " + r);
            }
        }
    }

    private TestCase testCase(String projectId, String caseId) {
        Map<String, String> last = lastResults(projectId);
        return jdbc.query("SELECT CASE_ID, TC_NO, TITLE, PRECONDITION, STEPS, EXPECTED, REQ_IDS, SCREEN_ID"
                + " FROM QA_TEST_CASE WHERE CASE_ID = ?", (rs, i) -> new TestCase(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), PjSupport.csv(rs.getString(7)),
                rs.getString(8), last.get(rs.getString(1))), caseId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("테스트 케이스"));
    }

    @PutMapping("/test-cases/{id}")
    public TestCase updateCase(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody TestCase req) {
        String projectId = projectOf("QA_TEST_CASE", "CASE_ID", id, "테스트 케이스");
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        validateCase(projectId, req);
        jdbc.update("""
                UPDATE QA_TEST_CASE SET TITLE = ?, PRECONDITION = ?, STEPS = ?, EXPECTED = ?, REQ_IDS = ?, SCREEN_ID = ?
                 WHERE CASE_ID = ?""", req.title().trim(), req.precondition(), req.steps(), req.expected(),
                PjSupport.csv(req.reqIds()), req.screenId(), id);
        audit.log(Action.UPDATE, "test-case:" + id, "테스트 케이스 수정", projectId);
        return testCase(projectId, id);
    }

    @DeleteMapping("/test-cases/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteCase(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        String projectId = projectOf("QA_TEST_CASE", "CASE_ID", id, "테스트 케이스");
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        deleteCaseRows(id);
        audit.log(Action.DELETE, "test-case:" + id, "테스트 케이스 삭제", projectId);
    }

    private void deleteCaseRows(String caseId) {
        jdbc.update("DELETE FROM QA_TEST_RESULT WHERE CASE_ID = ?", caseId);
        jdbc.update("UPDATE QA_DEFECT SET CASE_ID = NULL WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM DL_TRACE_LINK WHERE (FROM_TYPE = 'TC' AND FROM_ID = ?) OR (TO_TYPE = 'TC' AND TO_ID = ?)",
                caseId, caseId);
        jdbc.update("DELETE FROM QA_TEST_CASE WHERE CASE_ID = ?", caseId);
    }

    @GetMapping("/projects/{projectId}/test-rounds")
    public List<TestRound> rounds(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query("SELECT ROUND_ID, NAME, LVL, CREATED_AT FROM QA_TEST_ROUND WHERE PROJECT_ID = ?"
                + " ORDER BY CREATED_AT DESC", (rs, i) -> new TestRound(rs.getString(1), rs.getString(2),
                rs.getString(3), Times.read(rs, "CREATED_AT")), projectId);
    }

    @PostMapping("/projects/{projectId}/test-rounds")
    @ResponseStatus(HttpStatus.CREATED)
    public TestRound createRound(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody TestRound req) {
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        String id = Ulid.next();
        jdbc.update("INSERT INTO QA_TEST_ROUND (ROUND_ID, PROJECT_ID, NAME, LVL, CREATED_AT, CREATED_BY)"
                        + " VALUES (?, ?, ?, ?, ?, ?)", id, projectId, PjSupport.required("name", req.name(), 200),
                req.level() == null || req.level().isBlank() ? null
                        : PjSupport.oneOf("level", req.level(), null, "UNIT", "INTEGRATION", "ACCEPTANCE"),
                Times.now(), user.userId());
        audit.log(Action.CREATE, "test-round:" + id, "테스트 회차 생성", projectId);
        return rounds(user, projectId).stream().filter(r -> r.roundId().equals(id)).findFirst().orElseThrow();
    }

    @GetMapping("/test-rounds/{roundId}/results")
    public List<TestResult> results(@AuthenticationPrincipal AuthUser user, @PathVariable String roundId) {
        access.forProject(user, projectOf("QA_TEST_ROUND", "ROUND_ID", roundId, "테스트 회차"));
        return jdbc.query("SELECT CASE_ID, RESULT_CD, NOTE, DEFECT_ID FROM QA_TEST_RESULT WHERE ROUND_ID = ?",
                (rs, i) -> new TestResult(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), roundId);
    }

    @PutMapping("/test-rounds/{roundId}/results")
    @Transactional
    public List<TestResult> saveResults(@AuthenticationPrincipal AuthUser user, @PathVariable String roundId,
            @RequestBody List<TestResult> results) {
        String projectId = projectOf("QA_TEST_ROUND", "ROUND_ID", roundId, "테스트 회차");
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        for (TestResult r : results) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM QA_TEST_CASE WHERE CASE_ID = ? AND PROJECT_ID = ?",
                    Integer.class, r.caseId(), projectId);
            if (n == null || n == 0) {
                throw PjSupport.validation("caseId", "이 프로젝트의 테스트 케이스가 아닙니다: " + r.caseId());
            }
            String res = PjSupport.oneOf("result", r.result(), null, "PASS", "FAIL", "BLOCK", "NOT_RUN");
            if (r.defectId() != null) {
                Integer d = jdbc.queryForObject("SELECT COUNT(*) FROM QA_DEFECT WHERE DEFECT_ID = ? AND PROJECT_ID = ?",
                        Integer.class, r.defectId(), projectId);
                if (d == null || d == 0) {
                    throw PjSupport.validation("defectId", "이 프로젝트의 결함이 아닙니다: " + r.defectId());
                }
            }
            jdbc.update("DELETE FROM QA_TEST_RESULT WHERE ROUND_ID = ? AND CASE_ID = ?", roundId, r.caseId());
            jdbc.update("""
                    INSERT INTO QA_TEST_RESULT (ROUND_ID, CASE_ID, RESULT_CD, NOTE, DEFECT_ID, EXECUTED_AT, EXECUTED_BY)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", roundId, r.caseId(), res,
                    PjSupport.optional("note", r.note(), 2000), r.defectId(), Times.now(), user.userId());
        }
        audit.log(Action.UPDATE, "test-round:" + roundId, "테스트 결과 " + results.size() + "건", projectId);
        return results(user, roundId);
    }

    // ───────────────────────── 결함 ─────────────────────────

    private static final String DEFECT_COLS = """
            SELECT D.DEFECT_ID, D.DEFECT_NO, D.TITLE, D.DESCRIPTION, D.SEVERITY, D.PRIORITY, D.STATUS, D.ASSIGNEE_ID,
                   A.NAME ASSIGNEE_NM, D.REPORTER_ID, R.NAME REPORTER_NM, D.CASE_ID, D.SCREEN_ID, D.ELEMENT_ID,
                   D.CREATED_AT, D.UPDATED_AT
              FROM QA_DEFECT D LEFT JOIN CM_USER A ON A.USER_ID = D.ASSIGNEE_ID LEFT JOIN CM_USER R ON R.USER_ID = D.REPORTER_ID""";

    private static Defect mapDefect(java.sql.ResultSet rs) throws java.sql.SQLException {
        String a = rs.getString("ASSIGNEE_ID");
        return new Defect(rs.getString("DEFECT_ID"), rs.getString("DEFECT_NO"), rs.getString("TITLE"),
                rs.getString("DESCRIPTION"), rs.getString("SEVERITY"), rs.getString("PRIORITY"), rs.getString("STATUS"),
                a == null ? null : new UserRef(a, rs.getString("ASSIGNEE_NM")),
                new UserRef(rs.getString("REPORTER_ID"), rs.getString("REPORTER_NM")), rs.getString("CASE_ID"),
                rs.getString("SCREEN_ID"), rs.getString("ELEMENT_ID"), Times.read(rs, "CREATED_AT"),
                Times.read(rs, "UPDATED_AT"));
    }

    @GetMapping("/projects/{projectId}/defects")
    public PageDto<Defect> defects(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String severity,
            @RequestParam(required = false) String screenId, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        access.forProject(user, projectId);
        int[] ps = PageDto.normalize(page, size);
        StringBuilder where = new StringBuilder(" WHERE D.PROJECT_ID = ?");
        List<Object> args = new ArrayList<>(List.of(projectId));
        for (String[] f : new String[][] {{"D.STATUS", status}, {"D.SEVERITY", severity}}) {
            List<String> v = PjSupport.csv(f[1]);
            if (!v.isEmpty()) {
                where.append(" AND ").append(f[0]).append(" IN (").append(String.join(",", v.stream().map(x -> "?")
                        .toList())).append(")");
                args.addAll(v);
            }
        }
        if (screenId != null && !screenId.isBlank()) {
            where.append(" AND D.SCREEN_ID = ?");
            args.add(screenId);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(D.TITLE) LIKE ? OR LOWER(D.DEFECT_NO) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM QA_DEFECT D" + where, Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(ps[1]);
        pa.add((long) ps[0] * ps[1]);
        return new PageDto<>(jdbc.query(DEFECT_COLS + where + " ORDER BY D.DEFECT_NO DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapDefect(rs), pa.toArray()), ps[0], ps[1], total == null ? 0 : total);
    }

    public Defect defect(String id) {
        return jdbc.query(DEFECT_COLS + " WHERE D.DEFECT_ID = ?", (rs, i) -> mapDefect(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("결함"));
    }

    @GetMapping("/defects/{defectId}")
    public Defect getDefect(@AuthenticationPrincipal AuthUser user, @PathVariable String defectId) {
        access.forProject(user, projectOf("QA_DEFECT", "DEFECT_ID", defectId, "결함"));
        return defect(defectId);
    }

    @PostMapping("/projects/{projectId}/defects")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Defect createDefect(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody DefectRequest req) {
        access.forProject(user, projectId).require(Perm.TEST_EDIT);
        return insertDefect(projectId, req, user);
    }

    public Defect insertDefect(String projectId, DefectRequest req, AuthUser user) {
        String screenId = validateDefect(projectId, req);
        String id = Ulid.next();
        String status = req.assigneeId() == null ? "NEW" : "ASSIGNED";
        String no = seq.next(projectId, "DEF");
        jdbc.update("""
                INSERT INTO QA_DEFECT (DEFECT_ID, PROJECT_ID, DEFECT_NO, TITLE, DESCRIPTION, SEVERITY, PRIORITY, STATUS,
                                       ASSIGNEE_ID, REPORTER_ID, CASE_ID, SCREEN_ID, ELEMENT_ID, CREATED_AT, UPDATED_AT,
                                       UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no, req.title().trim(),
                req.description(), PjSupport.oneOf("severity", req.severity(), "MAJOR", "CRITICAL", "MAJOR", "MINOR",
                        "TRIVIAL"), PjSupport.oneOf("priority", req.priority(), "M", "H", "M", "L"), status,
                req.assigneeId(), user.userId(), req.caseId(), screenId, req.elementId(), Times.now(), Times.now(),
                user.userId());
        support.hist("DEFECT", id, "CREATE", null, status, null);
        if (req.assigneeId() != null && !req.assigneeId().equals(user.userId())) {
            notify.notify(req.assigneeId(), Event.DEFECT_ASSIGNED, "결함 배정: " + no + " " + req.title(), null,
                    "/quality/defects/" + id);
        }
        audit.log(Action.CREATE, "defect:" + id, "결함 등록 " + no, projectId);
        return defect(id);
    }

    /** 검증 후 화면 ID (요소가 있으면 요소의 화면) */
    private String validateDefect(String projectId, DefectRequest req) {
        PjSupport.required("title", req.title(), 300);
        qa.requireMember("assigneeId", projectId, req.assigneeId());
        String screenId = req.screenId();
        if (req.elementId() != null) {
            String[] el = qa.element(req.elementId());
            if (!projectId.equals(el[1])) {
                throw PjSupport.validation("elementId", "이 프로젝트의 화면 요소가 아닙니다.");
            }
            screenId = el[0];
        } else if (screenId != null && !projectId.equals(qa.projectOfScreen(screenId))) {
            throw PjSupport.validation("screenId", "이 프로젝트의 화면이 아닙니다.");
        }
        if (req.caseId() != null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM QA_TEST_CASE WHERE CASE_ID = ? AND PROJECT_ID = ?",
                    Integer.class, req.caseId(), projectId);
            if (n == null || n == 0) {
                throw PjSupport.validation("caseId", "이 프로젝트의 테스트 케이스가 아닙니다.");
            }
        }
        return screenId;
    }

    @PutMapping("/defects/{defectId}")
    @Transactional
    public Defect updateDefect(@AuthenticationPrincipal AuthUser user, @PathVariable String defectId,
            @RequestBody DefectRequest req) {
        String projectId = projectOf("QA_DEFECT", "DEFECT_ID", defectId, "결함");
        var a = access.forProject(user, projectId);
        a.require(Perm.TEST_EDIT);
        Defect cur = defect(defectId);
        QaSupport.requireOwnIfCustomer(a, user, cur.reporter().userId());
        String screenId = validateDefect(projectId, req);
        jdbc.update("""
                UPDATE QA_DEFECT SET TITLE = ?, DESCRIPTION = ?, SEVERITY = ?, PRIORITY = ?, ASSIGNEE_ID = ?, CASE_ID = ?,
                       SCREEN_ID = ?, ELEMENT_ID = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE DEFECT_ID = ?""",
                req.title().trim(), req.description(), PjSupport.oneOf("severity", req.severity(), cur.severity(),
                        "CRITICAL", "MAJOR", "MINOR", "TRIVIAL"),
                PjSupport.oneOf("priority", req.priority(), cur.priority(), "H", "M", "L"), req.assigneeId(),
                req.caseId(), screenId, req.elementId(), Times.now(), user.userId(), defectId);
        String prevAssignee = cur.assignee() == null ? null : cur.assignee().userId();
        if (req.assigneeId() != null && !req.assigneeId().equals(prevAssignee)) {
            if ("NEW".equals(cur.status()) || "REOPENED".equals(cur.status())) {
                jdbc.update("UPDATE QA_DEFECT SET STATUS = 'ASSIGNED' WHERE DEFECT_ID = ?", defectId);
                support.hist("DEFECT", defectId, "TRANSITION", cur.status(), "ASSIGNED", null);
            }
            if (!req.assigneeId().equals(user.userId())) {
                notify.notify(req.assigneeId(), Event.DEFECT_ASSIGNED, "결함 배정: " + cur.defectNo() + " "
                        + req.title(), null, "/quality/defects/" + defectId);
            }
        }
        audit.log(Action.UPDATE, "defect:" + defectId, "결함 수정 " + cur.defectNo(), projectId);
        return defect(defectId);
    }

    @PostMapping("/defects/{defectId}/transition")
    @Transactional
    public Defect transitionDefect(@AuthenticationPrincipal AuthUser user, @PathVariable String defectId,
            @RequestBody TransitionRequest req) {
        String projectId = projectOf("QA_DEFECT", "DEFECT_ID", defectId, "결함");
        var a = access.forProject(user, projectId);
        a.require(Perm.TEST_EDIT);
        Defect cur = defect(defectId);
        String to = PjSupport.oneOf("to", req.to(), null, "NEW", "ASSIGNED", "FIXED", "VERIFIED", "CLOSED", "REOPENED");
        if (!DEFECT_FLOW.getOrDefault(cur.status(), Set.of()).contains(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", cur.status() + " → " + to
                    + " 전이는 허용되지 않습니다.");
        }
        if ("ASSIGNED".equals(to) && cur.assignee() == null) {
            throw PjSupport.validation("assigneeId", "담당자를 먼저 지정해주세요.");
        }
        if ("CUSTOMER".equals(a.projectRole()) && !Set.of("REOPENED", "CLOSED").contains(to)) {
            throw ApiException.forbidden();
        }
        jdbc.update("""
                UPDATE QA_DEFECT SET STATUS = ?, REOPEN_COUNT = REOPEN_COUNT + ?, CLOSED_AT = ?, UPDATED_AT = ?,
                       UPDATED_BY = ? WHERE DEFECT_ID = ?""", to, "REOPENED".equals(to) ? 1 : 0,
                "CLOSED".equals(to) ? Times.now() : null, Times.now(), user.userId(), defectId);
        support.hist("DEFECT", defectId, "TRANSITION", cur.status(), to, req.comment());
        if ("REOPENED".equals(to) && cur.assignee() != null) {
            notify.notify(cur.assignee().userId(), Event.DEFECT_ASSIGNED, "결함 재오픈: " + cur.defectNo() + " "
                    + cur.title(), req.comment(), "/quality/defects/" + defectId);
        }
        if ("FIXED".equals(to) && !cur.reporter().userId().equals(user.userId())) {
            notify.notify(cur.reporter().userId(), Event.REVIEW_REQUESTED, "결함 조치 완료 확인 요청: " + cur.defectNo(),
                    req.comment(), "/quality/defects/" + defectId);
        }
        audit.log(Action.UPDATE, "defect:" + defectId, "결함 " + cur.status() + "→" + to, projectId);
        return defect(defectId);
    }

    @GetMapping("/defects/{defectId}/history")
    public List<PjSupport.Hist> defectHistory(@AuthenticationPrincipal AuthUser user, @PathVariable String defectId) {
        access.forProject(user, projectOf("QA_DEFECT", "DEFECT_ID", defectId, "결함"));
        return support.hist("DEFECT", defectId);
    }

    // ───────────────────────── 품질 리포트 ─────────────────────────

    @GetMapping("/projects/{projectId}/quality-report")
    public QualityReport report(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return qualityReport(projectId);
    }

    public QualityReport qualityReport(String projectId) {
        Map<String, Integer> bySeverity = new LinkedHashMap<>();
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (String s : List.of("CRITICAL", "MAJOR", "MINOR", "TRIVIAL")) {
            bySeverity.put(s, 0);
        }
        for (String s : List.of("NEW", "ASSIGNED", "FIXED", "VERIFIED", "CLOSED", "REOPENED")) {
            byStatus.put(s, 0);
        }
        int[] reopened = {0};
        int[] total = {0};
        TreeMap<LocalDate, int[]> daily = new TreeMap<>();
        Map<String, Integer> perScreen = new HashMap<>();
        jdbc.query("SELECT SEVERITY, STATUS, REOPEN_COUNT, CREATED_AT, CLOSED_AT, SCREEN_ID FROM QA_DEFECT"
                + " WHERE PROJECT_ID = ?", rs -> {
            total[0]++;
            bySeverity.merge(rs.getString(1), 1, Integer::sum);
            byStatus.merge(rs.getString(2), 1, Integer::sum);
            if (rs.getInt(3) > 0) {
                reopened[0]++;
            }
            OffsetDateTime c = Times.read(rs, "CREATED_AT");
            OffsetDateTime cl = Times.read(rs, "CLOSED_AT");
            daily.computeIfAbsent(c.toLocalDate(), k -> new int[2])[0]++;
            if (cl != null) {
                daily.computeIfAbsent(cl.toLocalDate(), k -> new int[2])[1]++;
            }
            if (rs.getString(6) != null) {
                perScreen.merge(rs.getString(6), 1, Integer::sum);
            }
        }, projectId);
        List<TrendPoint> trend = new ArrayList<>();
        if (!daily.isEmpty()) {
            int open = 0;
            LocalDate d = daily.firstKey();
            LocalDate end = PjSupport.today().isAfter(daily.lastKey()) ? PjSupport.today() : daily.lastKey();
            int guard = 0;
            while (!d.isAfter(end) && guard++ < 1000) {
                int[] v = daily.getOrDefault(d, new int[2]);
                open += v[0] - v[1];
                trend.add(new TrendPoint(d, v[0], v[1], open));
                d = d.plusDays(1);
            }
        }
        List<RoundProgress> progress = new ArrayList<>();
        int caseCount = jdbc.queryForObject("SELECT COUNT(*) FROM QA_TEST_CASE WHERE PROJECT_ID = ?", Integer.class,
                projectId);
        jdbc.query("SELECT ROUND_ID, NAME FROM QA_TEST_ROUND WHERE PROJECT_ID = ? ORDER BY CREATED_AT", rs -> {
            Map<String, Integer> c = new HashMap<>();
            jdbc.query("SELECT RESULT_CD, COUNT(*) FROM QA_TEST_RESULT WHERE ROUND_ID = ? GROUP BY RESULT_CD", r2 -> {
                c.put(r2.getString(1), r2.getInt(2));
            }, rs.getString(1));
            int pass = c.getOrDefault("PASS", 0);
            int fail = c.getOrDefault("FAIL", 0);
            int block = c.getOrDefault("BLOCK", 0);
            progress.add(new RoundProgress(rs.getString(1), rs.getString(2), caseCount, pass, fail, block,
                    Math.max(0, caseCount - pass - fail - block)));
        }, projectId);
        List<ScreenDefects> top = perScreen.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(10)
                .map(e -> new ScreenDefects(e.getKey(), jdbc.query("SELECT SCREEN_CODE || ' ' || NAME FROM AS_SCREEN"
                        + " WHERE SCREEN_ID = ?", (rs, i) -> rs.getString(1), e.getKey()).stream().findFirst()
                        .orElse(e.getKey()), e.getValue())).toList();
        double reopenRate = total[0] == 0 ? 0 : Math.round(reopened[0] * 1000.0 / total[0]) / 10.0;
        return new QualityReport(trend, bySeverity, byStatus, reopenRate, progress, top);
    }
}
