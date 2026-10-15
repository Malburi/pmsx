package kr.pmsx.sm;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
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

/** 장애·문제 관리 (FR-SM-006·007, docs/13 §4). */
@RestController
@RequestMapping("/api/v1")
public class IncidentController {

    public record TimelineItem(String itemId, OffsetDateTime at, String content) {
    }

    public record Incident(String incidentId, String incidentNo, String title, int grade, String status,
                           OffsetDateTime startedAt, OffsetDateTime detectedAt, OffsetDateTime recoveredAt,
                           String impact, List<String> systemIds, List<TimelineItem> timeline, String cause,
                           String rca, String prevention, List<String> srIds, List<SrRef> srs,
                           Long durationMinutes, OffsetDateTime updatedAt) {
    }

    public record IncidentRequest(String title, Integer grade, String status, OffsetDateTime startedAt,
                                  OffsetDateTime detectedAt, OffsetDateTime recoveredAt, String impact,
                                  List<String> systemIds, String cause, String rca, String prevention,
                                  List<String> srIds, List<TimelineItem> timeline) {
    }

    public record TimelineRequest(OffsetDateTime at, String content) {
    }

    public record Problem(String problemId, String problemNo, String title, String rootCause, String workaround,
                          boolean knownError, String status, List<String> srIds, List<String> incidentIds,
                          OffsetDateTime updatedAt) {
    }

    public record ProblemRequest(String title, String rootCause, String workaround, Boolean knownError,
                                 String status, List<String> srIds, List<String> incidentIds) {
    }

    public record CandidateSr(String srId, String srNo, String title, OffsetDateTime createdAt) {
    }

    public record CandidateIncident(String incidentId, String incidentNo, String title) {
    }

    /** 문제 후보 묶음 (docs/13 v0.3): reason = SAME_ELEMENT / SAME_SCREEN / SIMILAR_TITLE */
    public record Candidate(String key, String reason, String label, int count, String screenId, String elementId,
                            List<CandidateSr> srs, List<CandidateIncident> incidents) {
    }

    /** SR 참조 (장애·변경 응답의 srs) */
    public record SrRef(String srId, String srNo, String title) {
    }

    private record RefItem(String id, String no, String title, OffsetDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ProjectSeq seq;
    private final AuditService audit;

    public IncidentController(JdbcTemplate jdbc, ProjectAccess access, ProjectSeq seq, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.seq = seq;
        this.audit = audit;
    }

    // ───────────────────────── 장애 ─────────────────────────

    private static final String INC_COLS = """
            SELECT INCIDENT_ID, INCIDENT_NO, TITLE, GRADE, STATUS, STARTED_AT, DETECTED_AT, RECOVERED_AT, IMPACT,
                   SYSTEM_IDS, CAUSE, RCA, PREVENTION, SR_IDS, UPDATED_AT, PROJECT_ID FROM SM_INCIDENT""";

    private Incident mapIncident(java.sql.ResultSet rs, boolean withTimeline) throws java.sql.SQLException {
        String id = rs.getString("INCIDENT_ID");
        OffsetDateTime started = Times.read(rs, "STARTED_AT");
        OffsetDateTime recovered = Times.read(rs, "RECOVERED_AT");
        return new Incident(id, rs.getString("INCIDENT_NO"), rs.getString("TITLE"), rs.getInt("GRADE"),
                rs.getString("STATUS"), started, Times.read(rs, "DETECTED_AT"), recovered, rs.getString("IMPACT"),
                PjSupport.csv(rs.getString("SYSTEM_IDS")), withTimeline ? timeline(id) : List.of(),
                rs.getString("CAUSE"), rs.getString("RCA"), rs.getString("PREVENTION"),
                PjSupport.csv(rs.getString("SR_IDS")), srRefs(PjSupport.csv(rs.getString("SR_IDS"))),
                recovered == null || started == null ? null : Duration.between(started, recovered).toMinutes(),
                Times.read(rs, "UPDATED_AT"));
    }

    public List<TimelineItem> timeline(String incidentId) {
        return jdbc.query("SELECT ITEM_ID, EVENT_AT, CONTENT FROM SM_INCIDENT_TIMELINE WHERE INCIDENT_ID = ?"
                + " ORDER BY EVENT_AT, ITEM_ID", (rs, i) -> new TimelineItem(rs.getString(1),
                Times.read(rs, "EVENT_AT"), rs.getString(3)), incidentId);
    }

    @GetMapping("/projects/{projectId}/incidents")
    public List<Incident> incidents(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String status) {
        access.forProject(user, projectId);
        List<Object> args = new ArrayList<>(List.of(projectId));
        String where = " WHERE PROJECT_ID = ?";
        if (status != null && !status.isBlank()) {
            where += " AND STATUS = ?";
            args.add(status);
        }
        return jdbc.query(INC_COLS + where + " ORDER BY STARTED_AT DESC", (rs, i) -> mapIncident(rs, false),
                args.toArray());
    }

    public Incident incident(String id) {
        return jdbc.query(INC_COLS + " WHERE INCIDENT_ID = ?", (rs, i) -> mapIncident(rs, true), id).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("장애"));
    }

    public String incidentProject(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_INCIDENT WHERE INCIDENT_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("장애"));
    }

    @PostMapping("/projects/{projectId}/incidents")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Incident createIncident(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody IncidentRequest req) {
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        return insertIncident(projectId, req, user);
    }

    public Incident insertIncident(String projectId, IncidentRequest req, AuthUser user) {
        Object[] v = validateIncident(projectId, req, null);
        String id = Ulid.next();
        String no = seq.next(projectId, "INC");
        OffsetDateTime now = Times.now();
        jdbc.update("""
                INSERT INTO SM_INCIDENT (INCIDENT_ID, PROJECT_ID, INCIDENT_NO, TITLE, GRADE, STATUS, STARTED_AT,
                                         DETECTED_AT, RECOVERED_AT, IMPACT, SYSTEM_IDS, CAUSE, RCA, PREVENTION, SR_IDS,
                                         CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no, v[0], v[1], v[2],
                v[3], req.detectedAt(), req.recoveredAt(), req.impact(), v[4], req.cause(), req.rca(), req.prevention(),
                v[5], now, user.userId(), now, user.userId());
        if (req.timeline() != null) {
            for (TimelineItem t : req.timeline()) {
                addTimeline(id, t.at(), t.content(), user);
            }
        }
        audit.log(Action.CREATE, "incident:" + id, "장애 등록 " + no, projectId);
        return incident(id);
    }

    /** [title, grade, status, startedAt, systemIds, srIds] */
    private Object[] validateIncident(String projectId, IncidentRequest req, Incident cur) {
        String title = PjSupport.required("title", req.title(), 300);
        int grade = req.grade() == null ? (cur == null ? 3 : cur.grade()) : req.grade();
        if (grade < 1 || grade > 4) {
            throw PjSupport.validation("grade", "등급은 1~4 입니다.");
        }
        String status = PjSupport.oneOf("status", req.status(), cur == null ? "OPEN" : cur.status(), "OPEN",
                "RECOVERED", "CLOSED");
        OffsetDateTime started = req.startedAt() != null ? req.startedAt() : cur != null ? cur.startedAt() : Times.now();
        if (req.recoveredAt() != null && req.recoveredAt().isBefore(started)) {
            throw PjSupport.validation("recoveredAt", "복구 시각은 발생 시각 이후여야 합니다.");
        }
        return new Object[] {title, grade, status, started, ids("systemIds", projectId, req.systemIds(),
                "SELECT COUNT(*) FROM AS_SYSTEM WHERE PROJECT_ID = ? AND SYSTEM_ID = ?"),
                ids("srIds", projectId, req.srIds(), "SELECT COUNT(*) FROM SM_SR WHERE PROJECT_ID = ? AND SR_ID = ?")};
    }

    /** 같은 프로젝트 소속인지 확인한 ID 목록(콤마) */
    String ids(String field, String projectId, List<String> ids, String countSql) {
        if (ids == null || ids.isEmpty()) {
            return null;
        }
        for (String id : ids) {
            Integer n = jdbc.queryForObject(countSql, Integer.class, projectId, id);
            if (n == null || n == 0) {
                throw PjSupport.validation(field, "이 프로젝트의 항목이 아닙니다: " + id);
            }
        }
        return PjSupport.csv(ids);
    }

    @GetMapping("/incidents/{incidentId}")
    public Incident getIncident(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId) {
        access.forProject(user, incidentProject(incidentId));
        return incident(incidentId);
    }

    @PutMapping("/incidents/{incidentId}")
    @Transactional
    public Incident updateIncident(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
            @RequestBody IncidentRequest req) {
        String projectId = incidentProject(incidentId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        Incident cur = incident(incidentId);
        Object[] v = validateIncident(projectId, req, cur);
        OffsetDateTime recovered = req.recoveredAt() != null ? req.recoveredAt()
                : "RECOVERED".equals(v[2]) && cur.recoveredAt() == null ? Times.now() : cur.recoveredAt();
        jdbc.update("""
                UPDATE SM_INCIDENT SET TITLE = ?, GRADE = ?, STATUS = ?, STARTED_AT = ?, DETECTED_AT = ?, RECOVERED_AT = ?,
                       IMPACT = ?, SYSTEM_IDS = ?, CAUSE = ?, RCA = ?, PREVENTION = ?, SR_IDS = ?, UPDATED_AT = ?,
                       UPDATED_BY = ? WHERE INCIDENT_ID = ?""", v[0], v[1], v[2], v[3], req.detectedAt(), recovered,
                req.impact(), v[4], req.cause(), req.rca(), req.prevention(), v[5], Times.now(), user.userId(),
                incidentId);
        if (req.timeline() != null) {
            jdbc.update("DELETE FROM SM_INCIDENT_TIMELINE WHERE INCIDENT_ID = ?", incidentId);
            for (TimelineItem t : req.timeline()) {
                addTimeline(incidentId, t.at(), t.content(), user);
            }
        }
        audit.log(Action.UPDATE, "incident:" + incidentId, "장애 수정 " + cur.incidentNo(), projectId);
        return incident(incidentId);
    }

    @PostMapping("/incidents/{incidentId}/timeline")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Incident timeline(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId,
            @RequestBody TimelineRequest req) {
        String projectId = incidentProject(incidentId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        addTimeline(incidentId, req.at(), req.content(), user);
        jdbc.update("UPDATE SM_INCIDENT SET UPDATED_AT = ?, UPDATED_BY = ? WHERE INCIDENT_ID = ?", Times.now(),
                user.userId(), incidentId);
        return incident(incidentId);
    }

    private void addTimeline(String incidentId, OffsetDateTime at, String content, AuthUser user) {
        jdbc.update("INSERT INTO SM_INCIDENT_TIMELINE (ITEM_ID, INCIDENT_ID, EVENT_AT, CONTENT, CREATED_BY)"
                + " VALUES (?, ?, ?, ?, ?)", Ulid.next(), incidentId, at == null ? Times.now() : at,
                PjSupport.required("content", content, 2000), user.userId());
    }

    @DeleteMapping("/incidents/{incidentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteIncident(@AuthenticationPrincipal AuthUser user, @PathVariable String incidentId) {
        String projectId = incidentProject(incidentId);
        access.forProject(user, projectId).require(java.util.Set.of("PM"));
        jdbc.update("DELETE FROM SM_INCIDENT_TIMELINE WHERE INCIDENT_ID = ?", incidentId);
        jdbc.update("DELETE FROM SM_INCIDENT WHERE INCIDENT_ID = ?", incidentId);
        audit.log(Action.DELETE, "incident:" + incidentId, "장애 삭제", projectId);
    }

    // ───────────────────────── 문제 ─────────────────────────

    private static final String PRB_COLS = """
            SELECT PROBLEM_ID, PROBLEM_NO, TITLE, ROOT_CAUSE, WORKAROUND, KNOWN_ERROR, STATUS, SR_IDS, INCIDENT_IDS,
                   UPDATED_AT FROM SM_PROBLEM""";

    private static Problem mapProblem(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Problem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getBoolean(6), rs.getString(7), PjSupport.csv(rs.getString(8)), PjSupport.csv(rs.getString(9)),
                Times.read(rs, "UPDATED_AT"));
    }

    @GetMapping("/projects/{projectId}/problems")
    public List<Problem> problems(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query(PRB_COLS + " WHERE PROJECT_ID = ? ORDER BY PROBLEM_NO DESC", (rs, i) -> mapProblem(rs),
                projectId);
    }

    private Problem problem(String id) {
        return jdbc.query(PRB_COLS + " WHERE PROBLEM_ID = ?", (rs, i) -> mapProblem(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("문제"));
    }

    private String problemProject(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_PROBLEM WHERE PROBLEM_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("문제"));
    }

    @PostMapping("/projects/{projectId}/problems")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Problem createProblem(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody ProblemRequest req) {
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        String id = Ulid.next();
        String no = seq.next(projectId, "PRB");
        OffsetDateTime now = Times.now();
        jdbc.update("""
                INSERT INTO SM_PROBLEM (PROBLEM_ID, PROJECT_ID, PROBLEM_NO, TITLE, ROOT_CAUSE, WORKAROUND, KNOWN_ERROR,
                                        STATUS, SR_IDS, INCIDENT_IDS, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no,
                PjSupport.required("title", req.title(), 300), req.rootCause(), req.workaround(),
                Boolean.TRUE.equals(req.knownError()), PjSupport.oneOf("status", req.status(), "OPEN", "OPEN",
                        "ANALYZING", "RESOLVED", "CLOSED"),
                ids("srIds", projectId, req.srIds(), "SELECT COUNT(*) FROM SM_SR WHERE PROJECT_ID = ? AND SR_ID = ?"),
                ids("incidentIds", projectId, req.incidentIds(),
                        "SELECT COUNT(*) FROM SM_INCIDENT WHERE PROJECT_ID = ? AND INCIDENT_ID = ?"),
                now, user.userId(), now, user.userId());
        audit.log(Action.CREATE, "problem:" + id, "문제 등록 " + no, projectId);
        return problem(id);
    }

    @GetMapping("/problems/{problemId}")
    public Problem getProblem(@AuthenticationPrincipal AuthUser user, @PathVariable String problemId) {
        access.forProject(user, problemProject(problemId));
        return problem(problemId);
    }

    @PutMapping("/problems/{problemId}")
    @Transactional
    public Problem updateProblem(@AuthenticationPrincipal AuthUser user, @PathVariable String problemId,
            @RequestBody ProblemRequest req) {
        String projectId = problemProject(problemId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        Problem cur = problem(problemId);
        jdbc.update("""
                UPDATE SM_PROBLEM SET TITLE = ?, ROOT_CAUSE = ?, WORKAROUND = ?, KNOWN_ERROR = ?, STATUS = ?, SR_IDS = ?,
                       INCIDENT_IDS = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE PROBLEM_ID = ?""",
                PjSupport.required("title", req.title(), 300), req.rootCause(), req.workaround(),
                req.knownError() == null ? cur.knownError() : req.knownError(),
                PjSupport.oneOf("status", req.status(), cur.status(), "OPEN", "ANALYZING", "RESOLVED", "CLOSED"),
                ids("srIds", projectId, req.srIds(), "SELECT COUNT(*) FROM SM_SR WHERE PROJECT_ID = ? AND SR_ID = ?"),
                ids("incidentIds", projectId, req.incidentIds(),
                        "SELECT COUNT(*) FROM SM_INCIDENT WHERE PROJECT_ID = ? AND INCIDENT_ID = ?"),
                Times.now(), user.userId(), problemId);
        audit.log(Action.UPDATE, "problem:" + problemId, "문제 수정 " + cur.problemNo(), projectId);
        return problem(problemId);
    }

    /**
     * 문제 후보 (최근 90일): 같은 요소 → 같은 화면 → 제목 2-gram 자카드 ≥ 0.45 묶음 순으로 3건 이상.
     * 이미 문제에 연결된 SR·장애와, 먼저 묶인 SR 은 다시 쓰지 않는다.
     */
    @GetMapping("/projects/{projectId}/problems/candidates")
    public List<Candidate> candidates(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        OffsetDateTime since = Times.now().minusDays(90);
        Set<String> linked = new HashSet<>();
        jdbc.query("SELECT SR_IDS, INCIDENT_IDS FROM SM_PROBLEM WHERE PROJECT_ID = ?", rs -> {
            linked.addAll(PjSupport.csv(rs.getString(1)));
            linked.addAll(PjSupport.csv(rs.getString(2)));
        }, projectId);
        record S(String id, String no, String title, String screenId, String screenName, String elementId,
                 String elementLabel, OffsetDateTime at) {
        }
        List<S> srs = new ArrayList<>();
        jdbc.query("""
                SELECT R.SR_ID, R.SR_NO, R.TITLE, R.SCREEN_ID, S.NAME, R.ELEMENT_ID, COALESCE(E.LABEL, E.ELEMENT_CODE),
                       R.CREATED_AT
                  FROM SM_SR R LEFT JOIN AS_SCREEN S ON S.SCREEN_ID = R.SCREEN_ID LEFT JOIN AS_ELEMENT E ON E.ELEMENT_ID = R.ELEMENT_ID
                 WHERE R.PROJECT_ID = ? AND R.CREATED_AT >= ? AND R.STATUS <> 'REJECTED' ORDER BY R.CREATED_AT""", rs -> {
            if (!linked.contains(rs.getString(1))) {
                srs.add(new S(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), Times.read(rs, "CREATED_AT")));
            }
        }, projectId, since);
        List<RefItem> incidents = new ArrayList<>();
        jdbc.query("SELECT INCIDENT_ID, INCIDENT_NO, TITLE, STARTED_AT FROM SM_INCIDENT WHERE PROJECT_ID = ?"
                + " AND STARTED_AT >= ? ORDER BY STARTED_AT", rs -> {
                    if (!linked.contains(rs.getString(1))) {
                        incidents.add(new RefItem(rs.getString(1), rs.getString(2), rs.getString(3),
                                Times.read(rs, "STARTED_AT")));
                    }
                }, projectId, since);
        List<Candidate> out = new ArrayList<>();
        Set<String> used = new HashSet<>();
        // 1) 같은 요소, 2) 같은 화면
        for (String mode : List.of("ELEMENT", "SCREEN")) {
            Map<String, List<S>> groups = new LinkedHashMap<>();
            for (S s : srs) {
                String k = "ELEMENT".equals(mode) ? s.elementId() : s.screenId();
                if (k != null && !used.contains(s.id())) {
                    groups.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
                }
            }
            groups.forEach((k, g) -> {
                if (g.size() >= 3) {
                    S f = g.get(0);
                    List<RefItem> rel = incidents.stream().filter(inc -> g.stream()
                            .anyMatch(s -> Similarity.jaccard(s.title(), inc.title()) >= 0.3)).toList();
                    out.add(candidate("ELEMENT".equals(mode) ? "SAME_ELEMENT" : "SAME_SCREEN", k,
                            "ELEMENT".equals(mode) ? f.screenName() + " > " + f.elementLabel() : f.screenName(),
                            f.screenId(), "ELEMENT".equals(mode) ? f.elementId() : null,
                            g.stream().map(s -> new RefItem(s.id(), s.no(), s.title(), s.at())).toList(), rel));
                    g.forEach(s -> used.add(s.id()));
                }
            });
        }
        // 3) 제목 유사 (탐욕 묶음) — 장애도 같은 기준으로 붙인다
        List<S> rest = srs.stream().filter(s -> !used.contains(s.id())).toList();
        Set<String> taken = new HashSet<>();
        for (S seed : rest) {
            if (taken.contains(seed.id())) {
                continue;
            }
            List<S> g = new ArrayList<>();
            for (S s : rest) {
                if (!taken.contains(s.id()) && Similarity.jaccard(seed.title(), s.title()) >= 0.45) {
                    g.add(s);
                }
            }
            List<RefItem> rel = incidents.stream().filter(inc -> Similarity.jaccard(seed.title(), inc.title()) >= 0.45)
                    .toList();
            if (g.size() + rel.size() >= 3) {
                g.forEach(s -> taken.add(s.id()));
                out.add(candidate("SIMILAR_TITLE", seed.id(), seed.title(), null, null,
                        g.stream().map(s -> new RefItem(s.id(), s.no(), s.title(), s.at())).toList(), rel));
            }
        }
        return out;
    }

    private static Candidate candidate(String reason, String key, String label, String screenId, String elementId,
            List<RefItem> srs, List<RefItem> incidents) {
        return new Candidate(reason + ":" + key, reason, label, srs.size() + incidents.size(), screenId, elementId,
                srs.stream().map(r -> new CandidateSr(r.id(), r.no(), r.title(), r.createdAt())).toList(),
                incidents.stream().map(r -> new CandidateIncident(r.id(), r.no(), r.title())).toList());
    }

    /** SR ID 목록 → { srId, srNo, title } (장애·변경 응답) */
    public List<SrRef> srRefs(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<String, SrRef> m = new LinkedHashMap<>();
        String in = String.join(",", ids.stream().map(x -> "?").toList());
        jdbc.query("SELECT SR_ID, SR_NO, TITLE FROM SM_SR WHERE SR_ID IN (" + in + ")", rs -> {
            m.put(rs.getString(1), new SrRef(rs.getString(1), rs.getString(2), rs.getString(3)));
        }, ids.toArray());
        return ids.stream().map(m::get).filter(java.util.Objects::nonNull).toList();
    }
}
