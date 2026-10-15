package kr.pmsx.as.impact;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kr.pmsx.as.AsScope;
import kr.pmsx.as.UsageIndex;
import kr.pmsx.as.viewer.GraphReader;
import kr.pmsx.as.viewer.GraphReader.EdgeRow;
import kr.pmsx.as.viewer.SystemStructure;
import kr.pmsx.as.viewer.WorkflowService;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 영향도 분석 (FR-AS-028, SCR-AS-009, docs/13 §8). 대상 노드에서 활성 연결선을 거꾸로 따라가 영향받는 화면·요소·API·서버 함수·배치,
 * 그 화면의 최근 1년 SR·결함, 분석 담당·SR 처리자를 모은다. 컬럼 단위면 첫 단계에서 그 컬럼을 쓰는 accesses 엣지만 따른다.
 */
@RestController
@RequestMapping("/api/v1")
public class ImpactController {

    static final int MAX_DEPTH = 10;
    static final int MAX_NODES = 2000;
    /** 거꾸로 따라가는 엣지: 흐름 엣지 + 요소/화면 이벤트 (화면 이동·바인딩 제외) */
    static final Set<String> REVERSE = Set.of("calls", "request", "handles", "executes", "accesses", "event");

    public record Target(String nodeId, String nodeType, String name, String column) {
    }

    public record ImpactElement(String elementId, String label, String crud, List<String> path) {
    }

    public record ImpactScreen(String screenId, String screenCode, String name, List<String> menuPath, String owner,
                               boolean onLoad, List<ImpactElement> elements) {
    }

    public record NodeRef(String nodeId, String name) {
    }

    public record FnRef(String nodeId, String name, String file) {
    }

    public record SrRef(String srId, String srNo, String title, String status, OffsetDateTime createdAt) {
    }

    public record DefectRef(String defectId, String defectNo, String title, String status) {
    }

    public record Owner(String userId, String name, String reason) {
    }

    public record Summary(int screens, int elements, int apis, int srs, boolean truncated) {
    }

    public record ImpactResult(Target target, List<ImpactScreen> screens, List<NodeRef> apis,
                               List<FnRef> serverFunctions, List<FnRef> batches, List<SrRef> srs,
                               List<DefectRef> defects, List<Owner> owners, Summary summary) {
    }

    private final JdbcTemplate jdbc;
    private final AsScope scope;

    public ImpactController(JdbcTemplate jdbc, AsScope scope) {
        this.jdbc = jdbc;
        this.scope = scope;
    }

    /** 역추적 상태: 대상 쪽 다음 키, 경로상 CRUD */
    private record Step(String next, String crud, int depth) {
    }

    @GetMapping("/systems/{systemId}/impact")
    public ImpactResult impact(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestParam String targetId, @RequestParam(required = false) String column) {
        scope.read(user, systemId);
        return compute(systemId, targetId, column);
    }

    /** 권한 확인 없이 계산 (엑셀 데이터셋 — 호출자가 확인) */
    public ImpactResult compute(String systemId, String targetId, String column) {
        AsScope.NodeRef node = scope.node(targetId);
        if (!node.systemId().equals(systemId)) {
            throw ApiException.notFound("노드");
        }
        if (!Set.of("TABLE", "FN", "API", "SQL", "SP").contains(node.nodeType())) {
            throw PjSupport.validation("targetId", "테이블·함수·API·SQL·프로시저 노드만 분석할 수 있습니다.");
        }
        String col = column == null || column.isBlank() ? null : column.trim();
        if (col != null && !"TABLE".equals(node.nodeType())) {
            throw PjSupport.validation("column", "컬럼은 테이블 대상에서만 지정할 수 있습니다.");
        }
        GraphReader g = new GraphReader(jdbc, systemId);

        // ── 역방향 BFS ──
        Map<String, Step> seen = new LinkedHashMap<>();
        List<String[]> found = new ArrayList<>();          // [from, to, 대상 직접 엣지의 CRUD]
        seen.put(node.nodeKey(), new Step(null, null, 0));
        Deque<String> queue = new ArrayDeque<>(List.of(node.nodeKey()));
        boolean truncated = false;
        while (!queue.isEmpty()) {
            String key = queue.poll();
            Step cur = seen.get(key);
            if (cur.depth() >= MAX_DEPTH || key.startsWith("el:") || key.startsWith("scr:")) {
                continue;
            }
            for (EdgeRow e : g.to(List.of(key))) {
                if (!REVERSE.contains(e.kind())) {
                    continue;
                }
                if (col != null && cur.depth() == 0 && (!"accesses".equals(e.kind()) || !hasColumn(e.columns(), col))) {
                    continue;
                }
                found.add(new String[] {e.from(), key, cur.depth() == 0 ? e.crud() : null});
                if (seen.containsKey(e.from())) {
                    continue;
                }
                if (seen.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                String crud = cur.depth() == 0 ? e.crud() : cur.crud();
                seen.put(e.from(), new Step(key, crud, cur.depth() + 1));
                queue.add(e.from());
            }
        }

        // ── CRUD: 모든 경로의 합 (대상 직접 엣지에서 시작해 고정점까지 위로 전파) ──
        Map<String, String> crud = new HashMap<>();
        for (String[] f : found) {
            if (f[2] != null) {
                crud.merge(f[0], f[2], UsageIndex::merge);
            }
        }
        for (boolean changed = true; changed; ) {
            changed = false;
            for (String[] f : found) {
                String c = crud.get(f[1]);
                if (c != null && !f[1].equals(node.nodeKey())) {
                    String m = UsageIndex.merge(crud.get(f[0]), c);
                    if (!m.equals(crud.get(f[0]))) {
                        crud.put(f[0], m);
                        changed = true;
                    }
                }
            }
        }
        seen.replaceAll((k, v) -> new Step(v.next(), crud.get(k), v.depth()));

        // ── 분류 ──
        SystemStructure st = new SystemStructure(jdbc, systemId);
        Map<String, ImpactScreen> screens = new LinkedHashMap<>();
        Map<String, List<ImpactElement>> elementsByScreen = new LinkedHashMap<>();
        Set<String> onLoad = new LinkedHashSet<>();
        List<NodeRef> apis = new ArrayList<>();
        List<FnRef> server = new ArrayList<>();
        List<FnRef> batches = new ArrayList<>();
        Map<String, String> screenIdByKey = new HashMap<>();
        jdbc.query("SELECT SCREEN_KEY, SCREEN_ID FROM AS_SCREEN WHERE SYSTEM_ID = ?", rs -> {
            screenIdByKey.put(rs.getString(1), rs.getString(2));
        }, systemId);
        for (Map.Entry<String, Step> en : seen.entrySet()) {
            String key = en.getKey();
            if (key.equals(node.nodeKey())) {
                continue;
            }
            if (key.startsWith("el:")) {
                g.element(key).ifPresent(el -> elementsByScreen.computeIfAbsent(el.screenId(), k -> new ArrayList<>())
                        .add(new ImpactElement(el.id(), el.label() == null ? el.code() : el.label(), en.getValue().crud(),
                                path(g, seen, key))));
            } else if (key.startsWith("scr:")) {
                String id = screenIdByKey.get(key);
                if (id != null) {
                    onLoad.add(id);
                    elementsByScreen.computeIfAbsent(id, k -> new ArrayList<>());
                }
            } else {
                g.node(key).ifPresent(n -> {
                    if ("API".equals(n.type())) {
                        apis.add(new NodeRef(n.id(), n.name()));
                    } else if ("FN".equals(n.type()) && "SERVER".equals(WorkflowService.lane("FN", n.lang()))) {
                        FnRef f = new FnRef(n.id(), n.name(), n.file());
                        server.add(f);
                        boolean entry = g.to(List.of(key)).stream().noneMatch(x -> REVERSE.contains(x.kind()));
                        if (entry) {
                            batches.add(f);
                        }
                    }
                });
            }
        }
        Map<String, String[]> screenInfo = new HashMap<>();
        if (!elementsByScreen.isEmpty()) {
            List<String> ids = new ArrayList<>(elementsByScreen.keySet());
            String in = String.join(",", ids.stream().map(x -> "?").toList());
            jdbc.query("""
                    SELECT S.SCREEN_ID, S.SCREEN_KEY, S.SCREEN_CODE, S.NAME, U.NAME FROM AS_SCREEN S
                      LEFT JOIN AS_ANALYSIS A ON A.SCREEN_ID = S.SCREEN_ID LEFT JOIN CM_USER U ON U.USER_ID = A.OWNER_ID
                     WHERE S.SCREEN_ID IN (""" + in + ")", rs -> {
                screenInfo.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)});
            }, ids.toArray());
        }
        int elementCount = 0;
        for (Map.Entry<String, List<ImpactElement>> en : elementsByScreen.entrySet()) {
            String[] info = screenInfo.get(en.getKey());
            if (info == null) {
                continue;
            }
            elementCount += en.getValue().size();
            screens.put(en.getKey(), new ImpactScreen(en.getKey(), info[1], info[2], st.path(info[0]), info[3],
                    onLoad.contains(en.getKey()), en.getValue()));
        }

        // ── 최근 1년 SR·결함·담당자 ──
        List<String> screenIds = new ArrayList<>(screens.keySet());
        List<SrRef> srs = new ArrayList<>();
        List<DefectRef> defects = new ArrayList<>();
        Map<String, Owner> owners = new LinkedHashMap<>();
        if (!screenIds.isEmpty()) {
            String in = String.join(",", screenIds.stream().map(x -> "?").toList());
            List<Object> args = new ArrayList<>(screenIds);
            args.add(Times.now().minusYears(1));
            try {
                jdbc.query("SELECT R.SR_ID, R.SR_NO, R.TITLE, R.STATUS, R.CREATED_AT, R.ASSIGNEE_ID, U.NAME FROM SM_SR R"
                        + " LEFT JOIN CM_USER U ON U.USER_ID = R.ASSIGNEE_ID WHERE R.SCREEN_ID IN (" + in + ")"
                        + " AND R.CREATED_AT >= ? ORDER BY R.CREATED_AT DESC LIMIT 100", rs -> {
                            srs.add(new SrRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                    Times.read(rs, "CREATED_AT")));
                            if (rs.getString(6) != null) {
                                owners.putIfAbsent(rs.getString(6), new Owner(rs.getString(6), rs.getString(7),
                                        "최근 SR 처리: " + rs.getString(2)));
                            }
                        }, args.toArray());
            } catch (DataAccessException ignore) {
                // SM 테이블이 없는 구버전 DB
            }
            jdbc.query("SELECT DEFECT_ID, DEFECT_NO, TITLE, STATUS FROM QA_DEFECT WHERE SCREEN_ID IN (" + in + ")"
                    + " ORDER BY CREATED_AT DESC LIMIT 100", rs -> {
                        defects.add(new DefectRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
                    }, screenIds.toArray());
            jdbc.query("""
                    SELECT A.OWNER_ID, U.NAME, S.NAME FROM AS_ANALYSIS A JOIN CM_USER U ON U.USER_ID = A.OWNER_ID
                      JOIN AS_SCREEN S ON S.SCREEN_ID = A.SCREEN_ID WHERE A.SCREEN_ID IN (""" + in + ")", rs -> {
                owners.putIfAbsent(rs.getString(1), new Owner(rs.getString(1), rs.getString(2),
                        "화면 분석 담당: " + rs.getString(3)));
            }, screenIds.toArray());
        }
        List<Owner> ownerList = owners.values().stream().limit(20).toList();
        return new ImpactResult(new Target(node.nodeId(), node.nodeType(), node.name(), col),
                new ArrayList<>(screens.values()), apis, server, batches, srs, defects, ownerList,
                new Summary(screens.size(), elementCount, apis.size(), srs.size(), truncated));
    }

    static boolean hasColumn(String columns, String col) {
        if (columns == null || columns.isBlank()) {
            return false;
        }
        String c = col.toUpperCase(Locale.ROOT);
        return Arrays.stream(columns.split("[,\\s]+")).map(x -> {
            String v = x.trim().toUpperCase(Locale.ROOT);
            int dot = v.lastIndexOf('.');
            return dot >= 0 ? v.substring(dot + 1) : v;
        }).anyMatch(c::equals);
    }

    /** 요소에서 대상까지 이름 경로 */
    private static List<String> path(GraphReader g, Map<String, Step> seen, String from) {
        List<String> out = new ArrayList<>();
        String k = from;
        int guard = 0;
        while (k != null && guard++ < MAX_DEPTH + 2) {
            String key = k;
            if (key.startsWith("el:")) {
                out.add(g.element(key).map(e -> e.label() == null ? e.code() : e.label()).orElse(key));
            } else {
                out.add(g.node(key).map(n -> WorkflowService.title(n)).orElse(key));
            }
            Step s = seen.get(k);
            k = s == null ? null : s.next();
        }
        return out;
    }
}
