package kr.pmsx.dl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 추적 링크·추적성 매트릭스·요소 연결 이력 (FR-DL-004·005, FR-AS-027, docs/12 §5). */
@RestController
@RequestMapping("/api/v1")
public class TraceController {

    public record TraceLink(String linkId, String fromType, String fromId, String fromLabel, String toType, String toId,
                            String toLabel, String linkType) {
    }

    public record LinkRequest(String fromType, String fromId, String toType, String toId, String linkType) {
    }

    public record Item(String id, String label) {
    }

    public record TestItem(String id, String label, String lastResult) {
    }

    public record StatusItem(String id, String label, String status) {
    }

    public record ReqRef(String reqId, String reqNo, String title, String status) {
    }

    public record ElementDefect(String defectId, String defectNo, String title, String status, String severity) {
    }

    public record ElementIssue(String issueId, String issueNo, String title, String status) {
    }

    /** 요소 연결 이력 (12 v0.2) */
    public record ElementLinks(List<TraceLink> links, List<ElementDefect> defects, List<ElementIssue> issues) {
    }

    public record TraceRow(ReqRef req, List<Item> screens, List<TestItem> tests, List<StatusItem> defects,
                           List<Item> crs, List<String> gaps) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final TraceRegistry registry;
    private final AuditService audit;

    public TraceController(JdbcTemplate jdbc, ProjectAccess access, TraceRegistry registry, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.registry = registry;
        this.audit = audit;
    }

    @GetMapping("/trace-links")
    public List<TraceLink> links(@AuthenticationPrincipal AuthUser user, @RequestParam String type,
            @RequestParam String id) {
        TraceRegistry.Ref ref = registry.require("", type, id);
        access.forProject(user, ref.projectId());
        return linksOf(type, id);
    }

    public List<TraceLink> linksOf(String type, String id) {
        return jdbc.query("""
                SELECT LINK_ID, FROM_TYPE, FROM_ID, TO_TYPE, TO_ID, LINK_TYPE FROM DL_TRACE_LINK
                 WHERE (FROM_TYPE = ? AND FROM_ID = ?) OR (TO_TYPE = ? AND TO_ID = ?) ORDER BY CREATED_AT""",
                (rs, i) -> new TraceLink(rs.getString(1), rs.getString(2), rs.getString(3),
                        registry.label(rs.getString(2), rs.getString(3)), rs.getString(4), rs.getString(5),
                        registry.label(rs.getString(4), rs.getString(5)), rs.getString(6)), type, id, type, id);
    }

    @PostMapping("/trace-links")
    @ResponseStatus(HttpStatus.CREATED)
    public TraceLink create(@AuthenticationPrincipal AuthUser user, @RequestBody LinkRequest req) {
        TraceRegistry.Ref from = registry.require("from", req.fromType(), req.fromId());
        TraceRegistry.Ref to = registry.require("to", req.toType(), req.toId());
        if (!from.projectId().equals(to.projectId())) {
            throw PjSupport.validation("toId", "같은 프로젝트의 대상만 연결할 수 있습니다.");
        }
        if (from.type().equals(to.type()) && from.id().equals(to.id())) {
            throw PjSupport.validation("toId", "자기 자신과 연결할 수 없습니다.");
        }
        access.forProject(user, from.projectId()).require(Perm.TRACE_EDIT);
        Integer reverse = jdbc.queryForObject("SELECT COUNT(*) FROM DL_TRACE_LINK WHERE FROM_TYPE = ? AND FROM_ID = ?"
                + " AND TO_TYPE = ? AND TO_ID = ?", Integer.class, to.type(), to.id(), from.type(), from.id());
        if (reverse != null && reverse > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 연결되어 있습니다.");
        }
        String id = Ulid.next();
        try {
            jdbc.update("""
                    INSERT INTO DL_TRACE_LINK (LINK_ID, PROJECT_ID, FROM_TYPE, FROM_ID, TO_TYPE, TO_ID, LINK_TYPE, CREATED_AT,
                                               CREATED_BY)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, from.projectId(), from.type(), from.id(), to.type(),
                    to.id(), PjSupport.optional("linkType", req.linkType(), 50), Times.now(), user.userId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 연결되어 있습니다.");
        }
        audit.log(Action.CREATE, "trace:" + id, from.label() + " → " + to.label(), from.projectId());
        return new TraceLink(id, from.type(), from.id(), from.label(), to.type(), to.id(), to.label(), req.linkType());
    }

    @DeleteMapping("/trace-links/{linkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String linkId) {
        String projectId = jdbc.query("SELECT PROJECT_ID FROM DL_TRACE_LINK WHERE LINK_ID = ?",
                (rs, i) -> rs.getString(1), linkId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("추적 링크"));
        access.forProject(user, projectId).require(Perm.TRACE_EDIT);
        jdbc.update("DELETE FROM DL_TRACE_LINK WHERE LINK_ID = ?", linkId);
        audit.log(Action.DELETE, "trace:" + linkId, "추적 링크 삭제", projectId);
    }

    /** 요소에 연결된 추적 링크 + 그 요소에 등록된 결함·이슈 (FR-AS-027, 12 v0.2) */
    @GetMapping("/elements/{elementId}/links")
    public ElementLinks elementLinks(@AuthenticationPrincipal AuthUser user, @PathVariable String elementId) {
        TraceRegistry.Ref el = registry.require("", "ELEMENT", elementId);
        access.forProject(user, el.projectId());
        List<ElementDefect> defects = jdbc.query("SELECT DEFECT_ID, DEFECT_NO, TITLE, STATUS, SEVERITY FROM QA_DEFECT"
                + " WHERE ELEMENT_ID = ? ORDER BY CREATED_AT DESC", (rs, i) -> new ElementDefect(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)), elementId);
        List<ElementIssue> issues = jdbc.query("SELECT ISSUE_ID, ISSUE_NO, TITLE, STATUS FROM QA_ISSUE"
                + " WHERE ELEMENT_ID = ? ORDER BY CREATED_AT DESC", (rs, i) -> new ElementIssue(rs.getString(1),
                rs.getString(2), rs.getString(3), rs.getString(4)), elementId);
        return new ElementLinks(linksOf("ELEMENT", elementId), defects, issues);
    }

    // ───────────────────────── 추적성 매트릭스 ─────────────────────────

    @GetMapping("/projects/{projectId}/trace-matrix")
    public List<TraceRow> matrix(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return matrixRows(projectId);
    }

    public List<TraceRow> matrixRows(String projectId) {
        Map<String, ReqRef> reqs = new LinkedHashMap<>();
        jdbc.query("SELECT REQ_ID, REQ_NO, TITLE, STATUS FROM DL_REQUIREMENT WHERE PROJECT_ID = ? AND STATUS <> 'DROPPED'"
                + " ORDER BY REQ_NO", rs -> {
            reqs.put(rs.getString(1), new ReqRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
        }, projectId);
        // 링크 (양방향) : req → (type → ids)
        Map<String, Map<String, Set<String>>> linked = new HashMap<>();
        jdbc.query("SELECT FROM_TYPE, FROM_ID, TO_TYPE, TO_ID FROM DL_TRACE_LINK WHERE PROJECT_ID = ?"
                + " AND (FROM_TYPE = 'REQ' OR TO_TYPE = 'REQ')", rs -> {
            boolean fromReq = "REQ".equals(rs.getString(1));
            String req = fromReq ? rs.getString(2) : rs.getString(4);
            String type = fromReq ? rs.getString(3) : rs.getString(1);
            String id = fromReq ? rs.getString(4) : rs.getString(2);
            linked.computeIfAbsent(req, k -> new HashMap<>()).computeIfAbsent(type, k -> new LinkedHashSet<>()).add(id);
        }, projectId);
        // 테스트 케이스: 링크 + REQ_IDS
        Map<String, String[]> cases = new HashMap<>();   // caseId → [label, reqIds]
        jdbc.query("SELECT CASE_ID, TC_NO || ' ' || TITLE, REQ_IDS FROM QA_TEST_CASE WHERE PROJECT_ID = ?", rs -> {
            cases.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
            for (String r : PjSupport.csv(rs.getString(3))) {
                linked.computeIfAbsent(r, k -> new HashMap<>()).computeIfAbsent("TC", k -> new LinkedHashSet<>())
                        .add(rs.getString(1));
            }
        }, projectId);
        Map<String, String> lastResult = new HashMap<>();
        jdbc.query("""
                SELECT R.CASE_ID, R.RESULT_CD FROM QA_TEST_RESULT R JOIN QA_TEST_ROUND D ON D.ROUND_ID = R.ROUND_ID
                 WHERE D.PROJECT_ID = ? ORDER BY D.CREATED_AT, D.ROUND_ID""", rs -> {
            lastResult.put(rs.getString(1), rs.getString(2));
        }, projectId);
        Map<String, String[]> defects = new HashMap<>();   // id → [label, status, caseId]
        jdbc.query("SELECT DEFECT_ID, DEFECT_NO || ' ' || TITLE, STATUS, CASE_ID FROM QA_DEFECT WHERE PROJECT_ID = ?",
                rs -> {
                    defects.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3), rs.getString(4)});
                }, projectId);
        Map<String, String> crLabel = new HashMap<>();
        jdbc.query("SELECT CR_ID, CR_NO || ' ' || TITLE, REQ_IDS FROM QA_CR WHERE PROJECT_ID = ?", rs -> {
            crLabel.put(rs.getString(1), rs.getString(2));
            for (String r : PjSupport.csv(rs.getString(3))) {
                linked.computeIfAbsent(r, k -> new HashMap<>()).computeIfAbsent("CR", k -> new LinkedHashSet<>())
                        .add(rs.getString(1));
            }
        }, projectId);
        Map<String, String> elementScreen = new HashMap<>();
        List<TraceRow> out = new ArrayList<>();
        for (ReqRef req : reqs.values()) {
            Map<String, Set<String>> l = linked.getOrDefault(req.reqId(), Map.of());
            // 화면: SCREEN + ELEMENT 의 화면
            Map<String, Item> screens = new LinkedHashMap<>();
            for (String sid : l.getOrDefault("SCREEN", Set.of())) {
                screens.put(sid, new Item(sid, registry.label("SCREEN", sid)));
            }
            for (String eid : l.getOrDefault("ELEMENT", Set.of())) {
                String sid = elementScreen.computeIfAbsent(eid, k -> jdbc.query(
                        "SELECT SCREEN_ID FROM AS_ELEMENT WHERE ELEMENT_ID = ?", (rs, i) -> rs.getString(1), k).stream()
                        .findFirst().orElse(null));
                if (sid != null) {
                    screens.putIfAbsent(sid, new Item(sid, registry.label("SCREEN", sid)));
                }
            }
            List<TestItem> tests = new ArrayList<>();
            for (String cid : l.getOrDefault("TC", Set.of())) {
                String[] c = cases.get(cid);
                tests.add(new TestItem(cid, c == null ? registry.label("TC", cid) : c[0], lastResult.get(cid)));
            }
            Map<String, StatusItem> defs = new LinkedHashMap<>();
            for (String did : l.getOrDefault("DEFECT", Set.of())) {
                String[] d = defects.get(did);
                if (d != null) {
                    defs.put(did, new StatusItem(did, d[0], d[1]));
                }
            }
            Set<String> caseIds = new java.util.HashSet<>(l.getOrDefault("TC", Set.of()));
            defects.forEach((did, d) -> {
                if (d[2] != null && caseIds.contains(d[2])) {
                    defs.putIfAbsent(did, new StatusItem(did, d[0], d[1]));
                }
            });
            List<Item> crs = l.getOrDefault("CR", Set.of()).stream()
                    .map(cid -> new Item(cid, crLabel.getOrDefault(cid, registry.label("CR", cid)))).toList();
            List<String> gaps = new ArrayList<>();
            if (screens.isEmpty()) {
                gaps.add("NO_SCREEN");
            }
            if (tests.isEmpty()) {
                gaps.add("NO_TEST");
            }
            if (tests.stream().anyMatch(t -> "FAIL".equals(t.lastResult()))) {
                gaps.add("TEST_FAILED");
            }
            if (defs.values().stream().anyMatch(d -> !"CLOSED".equals(d.status()))) {
                gaps.add("OPEN_DEFECT");
            }
            out.add(new TraceRow(req, new ArrayList<>(screens.values()), tests, new ArrayList<>(defs.values()), crs,
                    gaps));
        }
        return out;
    }
}
