package kr.pmsx.as.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import kr.pmsx.as.AsScope;
import kr.pmsx.as.UsageIndex;
import kr.pmsx.as.viewer.GraphReader;
import kr.pmsx.as.viewer.GraphReader.EdgeRow;
import kr.pmsx.as.viewer.GraphReader.NodeRow;
import kr.pmsx.as.viewer.SystemStructure;
import kr.pmsx.as.viewer.ViewerDtos.CrudMatrix;
import kr.pmsx.as.viewer.ViewerDtos.CrudRow;
import kr.pmsx.as.viewer.ViewerDtos.TableRef;
import kr.pmsx.as.viewer.WorkflowService;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 시스템 단위 분석 뷰: CRUD 매트릭스·화면 이동 맵·호출 트리·공통 함수 사용처·회차 diff 상세 (11 명세 §4). */
@Service
public class SystemViewService {

    public record NavNode(String screenId, String screenCode, String name, String kind, String status) {
    }

    public record NavLink(String fromScreenId, String toScreenId, String via, String kind) {
    }

    public record Navigation(List<NavNode> nodes, List<NavLink> links) {
    }

    public record CallTreeNode(String nodeId, String nodeType, String name, String file, String edgeKind,
                               boolean truncated, List<CallTreeNode> children) {
    }

    public record Usage(String screenId, String screenName, String elementId, String elementLabel,
                        List<String> path) {
    }

    public record Ref(String id, String key, String label) {
    }

    public record Change(List<Ref> added, List<Ref> removed) {
    }

    public record EdgeSample(String from, String to, String kind, String change) {
    }

    public record EdgeChange(int added, int removed, List<EdgeSample> samples) {
    }

    public record RunDiff(String runId, String prevRunId, Change screens, Change elements, EdgeChange edges) {
    }

    static final int TREE_MAX_DEPTH = 6;
    static final int TREE_MAX_NODES = 200;
    static final int USAGE_LIMIT = 100;

    private final JdbcTemplate jdbc;
    private final AsScope scope;
    private final UsageIndex usage;
    private final ObjectMapper om;

    public SystemViewService(JdbcTemplate jdbc, AsScope scope, UsageIndex usage, ObjectMapper om) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.usage = usage;
        this.om = om;
    }

    // ───────────────────────── CRUD 매트릭스 ─────────────────────────

    private record El(String id, String key, String screenId, int seqNo, String label) {
    }

    public CrudMatrix crud(AuthUser user, String systemId, String by, String menuId) {
        scope.read(user, systemId);
        boolean byScreen = by == null || by.isBlank() || "screen".equals(by);
        if (!byScreen && !"element".equals(by)) {
            throw AsScope.validation("by", "screen 또는 element 여야 합니다.");
        }
        return crudMatrix(systemId, byScreen, menuId, null);
    }

    /** 산출물에서도 쓴다. screenIds 가 있으면 그 화면만. */
    public CrudMatrix crudMatrix(String systemId, boolean byScreen, String menuId, Set<String> screenIds) {
        SystemStructure st = new SystemStructure(jdbc, systemId);
        Set<String> allowedKeys = menuId == null || menuId.isBlank() ? null : st.screensUnderMenu(menuId);
        Map<String, SystemStructure.ScreenRow> screenById = new LinkedHashMap<>();
        Map<String, SystemStructure.ScreenRow> screenByKey = new HashMap<>();
        for (SystemStructure.ScreenRow s : st.screens()) {
            if ((allowedKeys == null || allowedKeys.contains(s.key()))
                    && (screenIds == null || screenIds.contains(s.id()))) {
                screenById.put(s.id(), s);
                screenByKey.put(s.key(), s);
            }
        }
        if (byScreen) {
            return crudByScreen(systemId, screenById);
        }
        Map<String, El> elements = new HashMap<>();
        jdbc.query("""
                SELECT E.ELEMENT_ID, E.ELEMENT_KEY, E.SCREEN_ID, E.SEQ_NO, COALESCE(E.LABEL, E.ELEMENT_CODE)
                  FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID
                 WHERE E.SYSTEM_ID = ? AND E.HIDDEN = FALSE AND (E.MANUAL_YN = 'Y' OR E.LAST_RUN_ID = S.LAST_RUN_ID)""",
                rs -> {
                    if (screenById.containsKey(rs.getString(3))) {
                        elements.put(rs.getString(2), new El(rs.getString(1), rs.getString(2), rs.getString(3),
                                rs.getInt(4), rs.getString(5)));
                    }
                }, systemId);
        Map<String, String[]> tableByKey = new HashMap<>();
        jdbc.query("SELECT NODE_KEY, NODE_ID, NAME FROM AS_NODE WHERE SYSTEM_ID = ? AND NODE_TYPE = 'TABLE'", rs -> {
            tableByKey.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
        }, systemId);

        UsageIndex.Index idx = usage.get(systemId);
        Map<String, TableRef> tables = new TreeMap<>();
        // 행 키 → 정렬키, 라벨, 셀
        Map<String, Object[]> rows = new LinkedHashMap<>();
        Map<Integer, String[]> tableOfIdx = new HashMap<>();
        List<Map.Entry<String, Map<Integer, Integer>>> starts = new ArrayList<>();
        idx.forEachStart((k, v) -> starts.add(Map.entry(k, v)));
        for (var en : starts) {
            String start = en.getKey();
            String screenId;
            String rowId;
            int seq;
            String label;
            if (start.startsWith("el:")) {
                El el = elements.get(start);
                if (el == null) {
                    continue;
                }
                screenId = el.screenId();
                rowId = byScreen ? screenId : el.id();
                seq = byScreen ? 0 : el.seqNo();
                label = byScreen ? screenById.get(screenId).name() : el.label();
            } else {
                SystemStructure.ScreenRow s = screenByKey.get(start);
                if (s == null) {
                    continue;
                }
                screenId = s.id();
                rowId = byScreen ? screenId : null;
                seq = 0;
                label = byScreen ? s.name() : "(화면 로드) " + s.name();
            }
            SystemStructure.ScreenRow s = screenById.get(screenId);
            String rowKey = rowId == null ? "load:" + screenId : rowId;
            Object[] row = rows.computeIfAbsent(rowKey, k -> new Object[] {s.code() + String.format("%06d", seq),
                    rowId, seq, label, new LinkedHashMap<String, String>(), s.id(), byScreen ? s.code() : s.name()});
            @SuppressWarnings("unchecked")
            Map<String, String> cells = (Map<String, String>) row[4];
            en.getValue().forEach((ti, c) -> {
                String[] t = tableOfIdx.computeIfAbsent(ti, i -> {
                    String[] v = tableByKey.get(idx.tableKey(i));
                    return v == null ? new String[0] : v;
                });
                if (t.length == 0) {
                    return;
                }
                tables.putIfAbsent(t[1] + "\u0001" + t[0], new TableRef(t[0], t[1]));
                cells.merge(t[0], UsageIndex.crud(c), UsageIndex::merge);
            });
        }
        List<Object[]> sorted = new ArrayList<>(rows.values());
        sorted.sort((a, b) -> ((String) a[0]).compareTo((String) b[0]));
        List<CrudRow> out = new ArrayList<>();
        for (Object[] r : sorted) {
            @SuppressWarnings("unchecked")
            Map<String, String> cells = (Map<String, String>) r[4];
            if (!cells.isEmpty()) {
                out.add(new CrudRow((String) r[1], (Integer) r[2], (String) r[3], cells, (String) r[5],
                        (String) r[6]));
            }
        }
        return new CrudMatrix(new ArrayList<>(tables.values()), out);
    }

    /**
     * 화면 축 빠른 경로: UsageIndex 가 미리 합쳐 둔 화면별 CRUD 를 그대로 쓴다(요소 20만 건을 다시 읽지 않음).
     * 결과는 요소 축 결과를 화면으로 합친 것과 같다.
     */
    private CrudMatrix crudByScreen(String systemId, Map<String, SystemStructure.ScreenRow> screenById) {
        Map<String, String[]> tableByKey = new HashMap<>();
        jdbc.query("SELECT NODE_KEY, NODE_ID, NAME FROM AS_NODE WHERE SYSTEM_ID = ? AND NODE_TYPE = 'TABLE'", rs -> {
            tableByKey.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
        }, systemId);
        UsageIndex.Index idx = usage.get(systemId);
        Map<String, TableRef> tables = new TreeMap<>();
        Map<Integer, String[]> tableOfIdx = new HashMap<>();
        List<SystemStructure.ScreenRow> screens = new ArrayList<>(screenById.values());
        screens.sort((a, b) -> a.code().compareTo(b.code()));
        List<CrudRow> out = new ArrayList<>();
        for (SystemStructure.ScreenRow sc : screens) {
            Map<Integer, Integer> raw = idx.screenCrud(sc.key());
            if (raw.isEmpty()) {
                continue;
            }
            Map<String, String> cells = new LinkedHashMap<>();
            raw.forEach((ti, c) -> {
                String[] t = tableOfIdx.computeIfAbsent(ti, i -> {
                    String[] v = tableByKey.get(idx.tableKey(i));
                    return v == null ? new String[0] : v;
                });
                if (t.length == 0) {
                    return;
                }
                tables.putIfAbsent(t[1] + "" + t[0], new TableRef(t[0], t[1]));
                cells.merge(t[0], UsageIndex.crud(c), UsageIndex::merge);
            });
            if (!cells.isEmpty()) {
                out.add(new CrudRow(sc.id(), 0, sc.name(), cells, sc.id(), sc.code()));
            }
        }
        return new CrudMatrix(new ArrayList<>(tables.values()), out);
    }

    // ───────────────────────── 화면 이동 맵 ─────────────────────────

    public Navigation navigation(AuthUser user, String systemId) {
        scope.read(user, systemId);
        SystemStructure st = new SystemStructure(jdbc, systemId);
        GraphReader g = new GraphReader(jdbc, systemId);
        List<NavNode> nodes = new ArrayList<>();
        for (SystemStructure.ScreenRow s : st.screens()) {
            nodes.add(new NavNode(s.id(), s.code(), s.name(), s.kind(), s.status()));
        }
        List<Object> args = new ArrayList<>(List.of(systemId));
        args.addAll(g.activeArgs());
        List<String[]> navs = jdbc.query("SELECT FROM_KEY, TO_KEY FROM AS_EDGE WHERE SYSTEM_ID = ?"
                        + " AND KIND = 'navigates'" + g.activeClause() + " ORDER BY FROM_KEY, TO_KEY",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, args.toArray());
        Map<String, NavLink> links = new LinkedHashMap<>();
        Map<String, String> callerCache = new HashMap<>();
        for (String[] n : navs) {
            SystemStructure.ScreenRow target = st.screen(n[1]);
            if (target == null) {
                continue;
            }
            String callerKey = callerCache.computeIfAbsent(n[0],
                    k -> Optional.ofNullable(SystemStructure.callerScreenKey(jdbc, systemId, k)).orElse(""));
            SystemStructure.ScreenRow caller = st.screen(callerKey);
            if (caller == null || caller.key().equals(target.key())) {
                continue;
            }
            String via = null;
            if (n[0].startsWith("el:")) {
                via = g.element(n[0]).map(e -> e.label() == null ? e.code() : e.label()).orElse(null);
            } else if (!n[0].startsWith("scr:")) {
                via = g.node(n[0]).map(WorkflowService::title).orElse(null);
            }
            String kind = "popup".equals(target.kind()) || "dialog".equals(target.kind()) ? "popup"
                    : ("tab".equals(target.kind()) ? "tab" : "link");
            links.putIfAbsent(caller.id() + "\u0001" + target.id() + "\u0001" + via,
                    new NavLink(caller.id(), target.id(), via, kind));
        }
        return new Navigation(nodes, new ArrayList<>(links.values()));
    }

    // ───────────────────────── 호출 트리·사용처 ─────────────────────────

    public CallTreeNode callTree(AuthUser user, String nodeId, String direction, Integer depth) {
        var n = scope.node(nodeId);
        scope.read(user, n.systemId());
        boolean up = "up".equals(direction);
        if (direction != null && !direction.isBlank() && !up && !"down".equals(direction)) {
            throw AsScope.validation("direction", "down 또는 up 이어야 합니다.");
        }
        int max = Math.max(1, Math.min(TREE_MAX_DEPTH, depth == null ? 3 : depth));
        GraphReader g = new GraphReader(jdbc, n.systemId());
        int[] count = {1};
        Set<String> path = new HashSet<>(List.of(n.nodeKey()));
        NodeRow root = g.node(n.nodeKey()).orElseThrow(() -> ApiException.notFound("노드"));
        List<CallTreeNode> children = children(g, n.nodeKey(), up, 1, max, count, path);
        return new CallTreeNode(root.id(), root.type(), WorkflowService.title(root), root.file(), null,
                false, children);
    }

    private List<CallTreeNode> children(GraphReader g, String key, boolean up, int depth, int max, int[] count,
            Set<String> path) {
        List<CallTreeNode> out = new ArrayList<>();
        List<EdgeRow> edges = up ? g.to(List.of(key)) : g.from(key);
        for (EdgeRow e : edges) {
            if ("binds".equals(e.kind())) {
                continue;
            }
            if (count[0] >= TREE_MAX_NODES) {
                break;
            }
            String other = up ? e.from() : e.to();
            CallTreeNode base = describe(g, other, e.kind());
            if (base == null) {
                continue;
            }
            count[0]++;
            boolean cycle = path.contains(other);
            boolean expandable = !cycle && !"ELEMENT".equals(base.nodeType()) && !"SCREEN".equals(base.nodeType());
            List<CallTreeNode> kids = List.of();
            boolean truncated = false;
            if (expandable) {
                boolean hasMore = up ? !g.to(List.of(other)).isEmpty() : !g.from(other).isEmpty();
                if (depth >= max || count[0] >= TREE_MAX_NODES) {
                    truncated = hasMore;
                } else {
                    path.add(other);
                    kids = children(g, other, up, depth + 1, max, count, path);
                    path.remove(other);
                }
            } else if (cycle) {
                truncated = true;
            }
            out.add(new CallTreeNode(base.nodeId(), base.nodeType(), base.name(), base.file(), e.kind(), truncated,
                    kids));
        }
        return out;
    }

    private CallTreeNode describe(GraphReader g, String key, String kind) {
        Optional<NodeRow> n = g.node(key);
        if (n.isPresent()) {
            return new CallTreeNode(n.get().id(), n.get().type(), WorkflowService.title(n.get()), n.get().file(), kind,
                    false, List.of());
        }
        if (key.startsWith("el:")) {
            return g.element(key).map(e -> new CallTreeNode(e.id(), "ELEMENT",
                    e.label() == null ? e.code() : e.label(), null, kind, false, List.<CallTreeNode>of())).orElse(null);
        }
        if (key.startsWith("scr:")) {
            return g.screen(key).map(s -> new CallTreeNode(s.id(), "SCREEN", s.name() + " (" + s.code() + ")", null,
                    kind, false, List.<CallTreeNode>of())).orElse(null);
        }
        return null;
    }

    /** 노드에 도달하는 요소(·화면 로드)들과 그 경로 — 공통 함수 사용처 (FR-AS-016) */
    public List<Usage> usage(AuthUser user, String nodeId) {
        var n = scope.node(nodeId);
        scope.read(user, n.systemId());
        GraphReader g = new GraphReader(jdbc, n.systemId());
        Map<String, String> parent = new HashMap<>();   // key → 아래쪽(대상 쪽) 키
        Set<String> visited = new HashSet<>(List.of(n.nodeKey()));
        Set<String> frontier = new LinkedHashSet<>(List.of(n.nodeKey()));
        List<Usage> out = new ArrayList<>();
        Map<String, String> screenNames = new HashMap<>();
        for (int depth = 0; depth < 12 && !frontier.isEmpty() && out.size() < USAGE_LIMIT; depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (EdgeRow e : g.to(frontier)) {
                if ("binds".equals(e.kind()) || !visited.add(e.from())) {
                    continue;
                }
                parent.put(e.from(), e.to());
                if (e.from().startsWith("el:")) {
                    var el = g.element(e.from());
                    if (el.isPresent()) {
                        String sname = screenNames.computeIfAbsent(el.get().screenId(), id -> jdbc.queryForObject(
                                "SELECT NAME FROM AS_SCREEN WHERE SCREEN_ID = ?", String.class, id));
                        out.add(new Usage(el.get().screenId(), sname, el.get().id(),
                                el.get().label() == null ? el.get().code() : el.get().label(), path(g, e.from(),
                                        parent)));
                    }
                } else if (e.from().startsWith("scr:")) {
                    var s = g.screen(e.from());
                    s.ifPresent(sr -> out.add(new Usage(sr.id(), sr.name(), null, null, path(g, e.from(), parent))));
                } else {
                    next.add(e.from());
                }
                if (out.size() >= USAGE_LIMIT) {
                    break;
                }
            }
            frontier = next;
        }
        return out;
    }

    private List<String> path(GraphReader g, String start, Map<String, String> parent) {
        List<String> out = new ArrayList<>();
        Set<String> guard = new HashSet<>();
        for (String k = parent.get(start); k != null && guard.add(k); k = parent.get(k)) {
            out.add(g.node(k).map(WorkflowService::title).orElse(k));
        }
        return out;
    }

    // ───────────────────────── 회차 diff ─────────────────────────

    public RunDiff diff(AuthUser user, String runId) {
        var row = jdbc.query("SELECT SYSTEM_ID, DIFF_DETAIL_JSON, DIFF_JSON FROM AS_COLLECT_RUN WHERE RUN_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, runId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("수집 회차"));
        scope.read(user, row[0]);
        JsonNode d = read(row[1]);
        JsonNode counts = read(row[2]);
        String systemId = row[0];
        Change screens = new Change(refs(systemId, d.path("screensAdded")), refs(systemId, d.path("screensRemoved")));
        Change elements = new Change(refs(systemId, d.path("elementsAdded")),
                refs(systemId, d.path("elementsRemoved")));
        List<EdgeSample> samples = new ArrayList<>();
        d.path("edgeSamples").forEach(s -> samples.add(new EdgeSample(s.path("from").asText(),
                s.path("to").asText(), s.path("kind").asText(), s.path("change").asText())));
        EdgeChange edges = new EdgeChange(counts.path("edgesAdded").asInt(0), counts.path("edgesRemoved").asInt(0),
                samples);
        return new RunDiff(runId, d.path("prevRunId").isTextual() ? d.path("prevRunId").asText() : null, screens,
                elements, edges);
    }

    private List<Ref> refs(String systemId, JsonNode keys) {
        List<Ref> out = new ArrayList<>();
        for (JsonNode k : keys) {
            String key = k.asText();
            if (key.startsWith("scr:")) {
                out.add(jdbc.query("SELECT SCREEN_ID, NAME, SCREEN_CODE FROM AS_SCREEN WHERE SYSTEM_ID = ?"
                                + " AND SCREEN_KEY = ?",
                        (rs, i) -> new Ref(rs.getString(1), key, rs.getString(2) + " (" + rs.getString(3) + ")"),
                        systemId, key).stream().findFirst().orElse(new Ref(null, key, key)));
            } else {
                out.add(jdbc.query("SELECT E.ELEMENT_ID, COALESCE(E.LABEL, E.ELEMENT_CODE), S.SCREEN_CODE"
                                + " FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID"
                                + " WHERE E.SYSTEM_ID = ? AND E.ELEMENT_KEY = ?",
                        (rs, i) -> new Ref(rs.getString(1), key, rs.getString(2) + " · " + rs.getString(3)),
                        systemId, key).stream().findFirst().orElse(new Ref(null, key, key)));
            }
        }
        return out;
    }

    private JsonNode read(String json) {
        try {
            return json == null ? om.createObjectNode() : om.readTree(json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
