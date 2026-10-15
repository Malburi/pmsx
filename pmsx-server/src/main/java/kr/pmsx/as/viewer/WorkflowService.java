package kr.pmsx.as.viewer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import kr.pmsx.as.viewer.GraphReader.EdgeRow;
import kr.pmsx.as.viewer.GraphReader.ElementRow;
import kr.pmsx.as.viewer.GraphReader.NodeRow;
import kr.pmsx.as.viewer.GraphReader.ScreenRow;
import kr.pmsx.as.viewer.ViewerDtos.CrudMatrix;
import kr.pmsx.as.viewer.ViewerDtos.CrudRow;
import kr.pmsx.as.viewer.ViewerDtos.EdgeRef;
import kr.pmsx.as.viewer.ViewerDtos.NodeDetail;
import kr.pmsx.as.viewer.ViewerDtos.TableRef;
import kr.pmsx.as.viewer.ViewerDtos.UsedBy;
import kr.pmsx.as.viewer.ViewerDtos.Workflow;
import kr.pmsx.as.viewer.ViewerDtos.WorkflowEvent;
import kr.pmsx.as.viewer.ViewerDtos.WorkflowStep;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 버튼 워크플로우·화면 CRUD·노드 상세(역참조) — AS_EDGE 그래프 탐색. */
@Service
public class WorkflowService {

    static final int MAX_DEPTH = 8;
    private static final int USED_BY_LIMIT = 100;
    /** 화면(프론트) 쪽 언어. 그 외(java, cs, vb ...)는 SERVER (11 명세 · 수집기 R1 신규 lang 포함) */
    static final Set<String> FRONT_LANGS = Set.of("js", "ts", "jsx", "tsx", "vue", "jsp", "html", "htm", "xfdl",
            "xjs", "xml", "aspx", "cshtml", "razor");
    private static final String SAME_FLOW = "(위와 동일 흐름)";

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ObjectMapper om;

    public WorkflowService(JdbcTemplate jdbc, ProjectAccess access, ObjectMapper om) {
        this.jdbc = jdbc;
        this.access = access;
        this.om = om;
    }

    // ───────────────────────── 워크플로우 ─────────────────────────

    public Workflow workflow(AuthUser user, String elementId) {
        var el = jdbc.query("SELECT SYSTEM_ID, ELEMENT_KEY, ELEMENT_CODE, LABEL FROM AS_ELEMENT WHERE ELEMENT_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                elementId).stream().findFirst().orElseThrow(() -> ApiException.notFound("요소"));
        access.forSystem(user, el[0]);
        GraphReader g = new GraphReader(jdbc, el[0], true);     // 기각 엣지도 표시 (11 v0.2)
        return new Workflow(elementId, events(g, el[1], el[2], el[3]));
    }

    /** 요소(또는 화면 로드 키)에서 시작하는 이벤트별 워크플로우. 산출물 생성처럼 같은 GraphReader 를 재사용할 때 쓴다. */
    public List<WorkflowEvent> events(GraphReader g, String startKey, String code, String labelOrNull) {
        String[] el = {null, startKey, code, labelOrNull};
        Map<String, List<EdgeRow>> byEvent = new LinkedHashMap<>();
        for (EdgeRow e : g.from(el[1])) {
            if ("binds".equals(e.kind())) {
                continue;
            }
            String ev = e.event() == null || e.event().isBlank() ? "click" : e.event();
            byEvent.computeIfAbsent(ev, k -> new ArrayList<>()).add(e);
        }
        String label = el[3] == null || el[3].isBlank() ? el[2] : el[3];
        List<WorkflowEvent> events = new ArrayList<>();
        for (var en : byEvent.entrySet()) {
            List<WorkflowStep> steps = new ArrayList<>();
            steps.add(new WorkflowStep(null, "UI", "[" + label + "] " + en.getKey(), el[2] + "." + en.getKey(), 0,
                    null, null, false, null));
            Set<String> visited = new HashSet<>();
            visited.add(el[1]);
            Set<String> screenStems = screenStems(g.systemId(), startKey);
            for (EdgeRow e : en.getValue()) {
                walk(g, e, 1, visited, steps, screenStems);
            }
            events.add(new WorkflowEvent(en.getKey(), steps));
        }
        return events;
    }

    private void walk(GraphReader g, EdgeRow via, int depth, Set<String> visited, List<WorkflowStep> steps,
            Set<String> screenStems) {
        String key = via.to();
        boolean revisit = !via.rejected() && !visited.add(key);
        Optional<NodeRow> node = g.node(key);
        Optional<ScreenRow> screen = node.isPresent() ? Optional.empty() : g.screen(key);

        String lane;
        String title;
        String subtitle;
        String nodeId = null;
        boolean hasSource = false;
        boolean expand = true;
        if (node.isPresent()) {
            NodeRow n = node.get();
            nodeId = n.id();
            hasSource = n.hasSnippet();
            lane = lane(n.type(), n.lang());
            if ("SERVER".equals(lane) && n.lang() != null && SCREEN_CODE_LANGS.contains(n.lang().toLowerCase())
                    && n.file() != null && screenStems.contains(stem(n.file()))) {
                lane = "FRONT";   // WinForms·WebForms 화면 코드(코드비하인드)
            }
            title = title(n);
            subtitle = subtitle(n);
            expand = !n.common();
        } else if (screen.isPresent()) {
            ScreenRow s = screen.get();
            lane = "UI";
            title = ("popup".equals(s.kind()) ? "팝업: " : "화면: ") + s.name();
            subtitle = s.code();
        } else {
            lane = laneByPrefix(key);
            title = key;
            subtitle = "수집 데이터에 정의되지 않은 대상";
            expand = false;
        }
        if (revisit) {
            subtitle = subtitle == null ? SAME_FLOW : subtitle + " " + SAME_FLOW;
        }
        String crud = "accesses".equals(via.kind()) ? via.crud() : null;
        List<String> columns = via.columns() == null ? null : Arrays.asList(via.columns().split(","));
        steps.add(new WorkflowStep(nodeId, lane, title, subtitle, depth, crud, columns, hasSource,
                new EdgeRef(via.id(), via.kind(), via.source(), via.confidence(), via.cond(), via.evidence(),
                        via.confirmed(), via.status())));

        // 기각된 연결은 표시만 하고 그 아래는 따라가지 않는다
        if (revisit || !expand || via.rejected() || depth >= MAX_DEPTH) {
            return;
        }
        for (EdgeRow child : g.from(key)) {
            if (GraphReader.FLOW_KINDS.contains(child.kind())) {
                walk(g, child, depth + 1, visited, steps, screenStems);
            }
        }
    }

    /** 화면 코드비하인드가 될 수 있는 언어: 화면 소스 파일과 같은 파일이면 FRONT */
    static final Set<String> SCREEN_CODE_LANGS = Set.of("cs", "vb");

    /** 시작 요소(또는 화면 키)의 화면 소스 파일 줄기 이름 (EmpForm.Designer.cs, EmpForm.cs → empform) */
    private Set<String> screenStems(String systemId, String startKey) {
        List<String> files;
        if (startKey != null && startKey.startsWith("scr:")) {
            files = jdbc.queryForList("SELECT SOURCE_FILE FROM AS_SCREEN WHERE SYSTEM_ID = ? AND SCREEN_KEY = ?",
                    String.class, systemId, startKey);
        } else {
            files = jdbc.queryForList("""
                    SELECT S.SOURCE_FILE FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID
                     WHERE E.SYSTEM_ID = ? AND E.ELEMENT_KEY = ?""", String.class, systemId, startKey);
        }
        Set<String> out = new HashSet<>();
        for (String f : files) {
            if (f != null && !f.isBlank()) {
                out.add(stem(f));
            }
        }
        return out;
    }

    /** 경로와 확장자(코드비하인드 접미사 포함)를 뗀 소문자 파일명. 화면 url 형식(winforms:A.B.EmpForm)이면 마지막 이름. */
    public static String stem(String file) {
        String f = file.replace('\\', '/');
        if (f.startsWith("winforms:")) {
            f = f.substring("winforms:".length());
            int dot = f.lastIndexOf('.');
            return (dot >= 0 ? f.substring(dot + 1) : f).toLowerCase();
        }
        f = f.substring(f.lastIndexOf('/') + 1).toLowerCase();
        for (String suffix : new String[] {".designer.cs", ".designer.vb", ".aspx.cs", ".aspx.vb", ".ascx.cs",
                ".ascx.vb", ".cshtml.cs", ".cs", ".vb", ".aspx", ".ascx", ".cshtml", ".resx"}) {
            if (f.endsWith(suffix)) {
                return f.substring(0, f.length() - suffix.length());
            }
        }
        return f;
    }

    public static String lane(String type, String lang) {
        return switch (type) {
            case "FN" -> lang != null && FRONT_LANGS.contains(lang.toLowerCase()) ? "FRONT" : "SERVER";
            case "API" -> "API";
            case "SQL", "SP" -> "SQL";
            case "TABLE" -> "DB";
            default -> "UI";
        };
    }

    private static String laneByPrefix(String key) {
        if (key.startsWith("api:")) {
            return "API";
        }
        if (key.startsWith("sql:") || key.startsWith("sp:")) {
            return "SQL";
        }
        if (key.startsWith("tbl:")) {
            return "DB";
        }
        if (key.startsWith("fn:")) {
            String[] p = key.split(":", 3);
            return p.length > 1 && FRONT_LANGS.contains(p[1].toLowerCase()) ? "FRONT" : "SERVER";
        }
        return "UI";
    }

    public static String title(NodeRow n) {
        if ("FN".equals(n.type()) && !n.name().contains("(")) {
            return n.name() + "()";
        }
        return n.name();
    }

    private String subtitle(NodeRow n) {
        JsonNode meta = meta(n.metaJson());
        switch (n.type()) {
            case "FN":
                if (n.file() == null) {
                    return null;
                }
                String file = n.file().substring(n.file().lastIndexOf('/') + 1);
                return n.lineStart() == null ? file : file + ":" + n.lineStart();
            case "API":
                if (meta.hasNonNull("serviceId")) {
                    return "서비스 ID: " + meta.get("serviceId").asText();
                }
                if (meta.has("observedParams") && meta.get("observedParams").isArray()
                        && !meta.get("observedParams").isEmpty()) {
                    List<String> ps = new ArrayList<>();
                    meta.get("observedParams").forEach(p -> ps.add(p.asText()));
                    return "param: " + String.join(", ", ps);
                }
                return null;
            case "SQL":
            case "SP":
                String kind = meta.path("kind").asText("").toUpperCase();
                String f = n.file() == null ? null : n.file().substring(n.file().lastIndexOf('/') + 1);
                return f == null ? (kind.isEmpty() ? null : kind) : kind + " · " + f;
            case "TABLE":
                return n.tableComment();
            default:
                return null;
        }
    }

    private JsonNode meta(String json) {
        if (json == null) {
            return om.createObjectNode();
        }
        try {
            return om.readTree(json);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }

    // ───────────────────────── 화면 CRUD ─────────────────────────

    public CrudMatrix crud(AuthUser user, String screenId) {
        String systemId = jdbc.query("SELECT SYSTEM_ID FROM AS_SCREEN WHERE SCREEN_ID = ?", (rs, i) -> rs.getString(1),
                screenId).stream().findFirst().orElseThrow(() -> ApiException.notFound("화면"));
        access.forSystem(user, systemId);
        GraphReader g = new GraphReader(jdbc, systemId);
        var elements = jdbc.query("SELECT ELEMENT_ID, ELEMENT_KEY, SEQ_NO, ELEMENT_CODE, LABEL FROM AS_ELEMENT"
                        + " WHERE SCREEN_ID = ? ORDER BY SEQ_NO",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3)),
                        rs.getString(4), rs.getString(5)}, screenId);

        Map<String, TableRef> tables = new TreeMap<>();
        List<CrudRow> rows = new ArrayList<>();
        for (String[] el : elements) {
            Map<String, Set<Character>> acc = new LinkedHashMap<>();
            Set<String> visited = new HashSet<>();
            visited.add(el[1]);
            for (EdgeRow e : g.from(el[1])) {
                if (!"binds".equals(e.kind())) {
                    collectCrud(g, e, 1, visited, acc);
                }
            }
            if (acc.isEmpty()) {
                continue;
            }
            Map<String, String> cells = new LinkedHashMap<>();
            for (var en : acc.entrySet()) {
                Optional<NodeRow> t = g.node(en.getKey());
                if (t.isEmpty()) {
                    continue;
                }
                tables.putIfAbsent(t.get().name() + "\u0001" + t.get().id(), new TableRef(t.get().id(), t.get().name()));
                cells.put(t.get().id(), crudString(en.getValue()));
            }
            if (!cells.isEmpty()) {
                rows.add(new CrudRow(el[0], Integer.parseInt(el[2]), el[4] == null ? el[3] : el[4], cells, screenId,
                        null));
            }
        }
        return new CrudMatrix(new ArrayList<>(tables.values()), rows);
    }

    private void collectCrud(GraphReader g, EdgeRow via, int depth, Set<String> visited,
            Map<String, Set<Character>> acc) {
        if ("accesses".equals(via.kind()) && via.crud() != null) {
            Set<Character> s = acc.computeIfAbsent(via.to(), k -> new LinkedHashSet<>());
            for (char c : via.crud().toCharArray()) {
                s.add(c);
            }
        }
        if (!visited.add(via.to()) || depth >= MAX_DEPTH) {
            return;
        }
        Optional<NodeRow> n = g.node(via.to());
        if (n.isPresent() && n.get().common()) {
            return;
        }
        for (EdgeRow child : g.from(via.to())) {
            if (GraphReader.FLOW_KINDS.contains(child.kind())) {
                collectCrud(g, child, depth + 1, visited, acc);
            }
        }
    }

    private static String crudString(Set<Character> s) {
        StringBuilder sb = new StringBuilder();
        for (char c : new char[] {'C', 'R', 'U', 'D'}) {
            if (s.contains(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ───────────────────────── 노드 상세 ─────────────────────────

    public NodeDetail node(AuthUser user, String nodeId) {
        var row = jdbc.query("""
                SELECT SYSTEM_ID, NODE_ID, NODE_TYPE, NODE_KEY, NAME, LANG, FILE_PATH, LINE_START, LINE_END, SNIPPET
                  FROM AS_NODE WHERE NODE_ID = ?""",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getObject(8, Integer.class),
                        rs.getObject(9, Integer.class), rs.getString(10)}, nodeId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("노드"));
        String systemId = (String) row[0];
        var a = access.forSystem(user, systemId);
        // 고객은 소스 스니펫 비공개 (docs/06 소스 스니펫 조회: CUSTOMER –)
        boolean hideSource = "CUSTOMER".equals(a.projectRole()) && !a.sysAdmin() && !a.pmo();
        List<UsedBy> usedBy = usedBy(new GraphReader(jdbc, systemId), (String) row[3]);
        return new NodeDetail((String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
                (String) row[6], (Integer) row[7], (Integer) row[8], hideSource ? null : (String) row[9], usedBy);
    }

    /** 이 노드에 도달하는 요소들을 역방향으로 찾는다 (최대 USED_BY_LIMIT 건). */
    private List<UsedBy> usedBy(GraphReader g, String key) {
        Map<String, UsedBy> found = new LinkedHashMap<>();
        Set<String> visited = new HashSet<>();
        visited.add(key);
        Set<String> frontier = new LinkedHashSet<>(List.of(key));
        Map<String, String> screenNames = new java.util.HashMap<>();
        for (int depth = 0; depth < MAX_DEPTH + 2 && !frontier.isEmpty() && found.size() < USED_BY_LIMIT; depth++) {
            Set<String> next = new LinkedHashSet<>();
            for (EdgeRow e : g.to(frontier)) {
                if ("binds".equals(e.kind())) {
                    continue;
                }
                if (!visited.add(e.from())) {
                    continue;
                }
                Optional<ElementRow> el = g.element(e.from());
                if (el.isPresent()) {
                    ElementRow r = el.get();
                    String screenName = screenNames.computeIfAbsent(r.screenId(), id -> jdbc.queryForObject(
                            "SELECT NAME FROM AS_SCREEN WHERE SCREEN_ID = ?", String.class, id));
                    found.putIfAbsent(r.id(), new UsedBy(r.screenId(), screenName, r.id(),
                            r.label() == null ? r.code() : r.label()));
                    if (found.size() >= USED_BY_LIMIT) {
                        break;
                    }
                } else {
                    next.add(e.from());
                }
            }
            frontier = next;
        }
        return new ArrayList<>(found.values());
    }
}
