package kr.pmsx.as.deliverable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.as.AsScope;
import kr.pmsx.as.UsageIndex;
import kr.pmsx.as.viewer.GraphReader;
import kr.pmsx.as.viewer.GraphReader.EdgeRow;
import kr.pmsx.as.viewer.SystemStructure;
import kr.pmsx.as.viewer.ViewerDtos.WorkflowEvent;
import kr.pmsx.as.viewer.ViewerDtos.WorkflowStep;
import kr.pmsx.as.viewer.WorkflowService;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 산출물(11 §5)·엑셀 템플릿 데이터셋이 함께 쓰는 행 데이터 생성기. */
@Component
public class DeliverableData {

    public static final int SCREEN_LIMIT = 300;

    public record ScreenPick(String screenId, String key, String code, String name, String status, String owner,
                             List<String> menuPath) {
    }

    public record ElementInfo(String elementId, String key, int seqNo, String code, String label, String type,
                              String binding, Double x, Double y, Double w, Double h, List<String> events) {
    }

    /** 이벤트 1건의 프로그램 사양 (워크플로우 요약) */
    public record ProgramRow(String screenCode, String screenName, int seqNo, String element, String event,
                             String handler, String apis, String serverFunctions, String sqls, String tables) {

        Map<String, Object> map() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("screenCode", screenCode);
            m.put("screenName", screenName);
            m.put("seqNo", seqNo == 0 ? null : seqNo);
            m.put("element", element);
            m.put("event", event);
            m.put("handler", handler);
            m.put("apis", apis);
            m.put("serverFunctions", serverFunctions);
            m.put("sqls", sqls);
            m.put("tables", tables);
            return m;
        }
    }

    private final JdbcTemplate jdbc;
    private final AsScope scope;
    private final WorkflowService workflow;
    private final com.fasterxml.jackson.databind.ObjectMapper om;
    private final kr.pmsx.as.system.SystemViewService systemView;

    public DeliverableData(JdbcTemplate jdbc, AsScope scope, WorkflowService workflow,
            com.fasterxml.jackson.databind.ObjectMapper om, kr.pmsx.as.system.SystemViewService systemView) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.workflow = workflow;
        this.om = om;
        this.systemView = systemView;
    }

    /** 화면정의서 템플릿용: 화면 × 요소 행 */
    public List<Map<String, Object>> screenSpecRows(AuthUser user, String systemId, String screenIds, String status) {
        boolean onlyDone = checkAccess(user, systemId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScreenPick s : screens(systemId, screenIds, status, onlyDone)) {
            for (ElementInfo e : elements(s.screenId())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("screenCode", s.code());
                m.put("screenName", s.name());
                m.put("menuPath", String.join(" › ", s.menuPath()));
                m.put("seqNo", e.seqNo());
                m.put("label", e.label());
                m.put("elementCode", e.code());
                m.put("type", e.type());
                m.put("binding", e.binding());
                m.put("events", String.join("\n", e.events()));
                rows.add(m);
            }
        }
        return rows;
    }

    /** CRUD 매트릭스 템플릿용: 화면 × 테이블 세로형 행 */
    public List<Map<String, Object>> crudRows(AuthUser user, String systemId, String screenIds, String status) {
        boolean onlyDone = checkAccess(user, systemId);
        Map<String, ScreenPick> picks = new LinkedHashMap<>();
        screens(systemId, screenIds, status, onlyDone).forEach(s -> picks.put(s.screenId(), s));
        var m = systemView.crudMatrix(systemId, true, null, picks.keySet());
        Map<String, String> names = new HashMap<>();
        m.tables().forEach(t -> names.put(t.nodeId(), t.name()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var r : m.rows()) {
            ScreenPick s = picks.get(r.elementId());
            r.cells().forEach((tableId, crud) -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("screenCode", s == null ? null : s.code());
                row.put("screenName", r.label());
                row.put("table", names.get(tableId));
                row.put("crud", crud);
                rows.add(row);
            });
        }
        return rows;
    }

    /** 권한 확인. CUSTOMER(그리고 고객 전용 사용자)는 확정 화면만 → true 반환 */
    public boolean checkAccess(AuthUser user, String systemId) {
        ProjectAccess.Access a = scope.read(user, systemId);
        a.require(ProjectAccess.DELIVERABLE);
        return "CUSTOMER".equals(a.projectRole()) && !a.sysAdmin() && !a.pmo();
    }

    public List<ScreenPick> screens(String systemId, String screenIds, String status, boolean onlyDone) {
        SystemStructure st = new SystemStructure(jdbc, systemId);
        Set<String> ids = screenIds == null || screenIds.isBlank() ? null
                : new HashSet<>(List.of(screenIds.split(","))).stream().map(String::trim)
                        .collect(java.util.stream.Collectors.toSet());
        String want = onlyDone ? "DONE" : (status == null || status.isBlank() ? null : status);
        Map<String, String> owners = new HashMap<>();
        jdbc.query("SELECT A.SCREEN_ID, U.NAME FROM AS_ANALYSIS A JOIN AS_SCREEN S ON S.SCREEN_ID = A.SCREEN_ID"
                + " LEFT JOIN CM_USER U ON U.USER_ID = A.OWNER_ID WHERE S.SYSTEM_ID = ?", rs -> {
                    owners.put(rs.getString(1), rs.getString(2));
                }, systemId);
        List<ScreenPick> out = new ArrayList<>();
        for (SystemStructure.ScreenRow s : st.screens()) {
            if ((ids != null && !ids.contains(s.id())) || (want != null && !want.equals(s.status()))) {
                continue;
            }
            out.add(new ScreenPick(s.id(), s.key(), s.code(), s.name(), s.status(), owners.get(s.id()),
                    st.path(s.key())));
        }
        if (out.size() > SCREEN_LIMIT) {
            throw ApiException.badRequest("VALIDATION", "산출물은 한 번에 " + SCREEN_LIMIT + "개 화면까지 만들 수 있습니다."
                    + " (선택된 화면 " + out.size() + "개) 화면을 나눠서 출력해주세요.");
        }
        return out;
    }

    public List<ElementInfo> elements(String screenId) {
        Map<String, List<String>> events = new HashMap<>();
        jdbc.query("""
                SELECT E.ELEMENT_ID, D.EVENT, N.NAME, D.TO_KEY FROM AS_ELEMENT E
                  JOIN AS_EDGE D ON D.SYSTEM_ID = E.SYSTEM_ID AND D.FROM_KEY = E.ELEMENT_KEY
                  LEFT JOIN AS_NODE N ON N.SYSTEM_ID = D.SYSTEM_ID AND N.NODE_KEY = D.TO_KEY
                 WHERE E.SCREEN_ID = ? AND D.KIND = 'event' AND D.STATUS = 'active'
                 ORDER BY E.SEQ_NO, D.ORD, D.MODEL_SEQ""", rs -> {
                    String ev = rs.getString(2) == null || rs.getString(2).isBlank() ? "click" : rs.getString(2);
                    String name = rs.getString(3) == null ? rs.getString(4) : rs.getString(3);
                    events.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(ev + " → " + name);
                }, screenId);
        return jdbc.query("""
                SELECT E.ELEMENT_ID, E.ELEMENT_KEY, E.SEQ_NO, E.ELEMENT_CODE, COALESCE(E.LABEL, E.ELEMENT_CODE), E.TYPE,
                       E.BINDING, E.BOX_X, E.BOX_Y, E.BOX_W, E.BOX_H
                  FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID
                 WHERE E.SCREEN_ID = ? AND E.HIDDEN = FALSE
                   AND (E.MANUAL_YN = 'Y' OR E.LAST_RUN_ID = S.LAST_RUN_ID OR S.LAST_RUN_ID IS NULL)
                 ORDER BY E.SEQ_NO""",
                (rs, i) -> new ElementInfo(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), (Double) rs.getObject(8, Double.class),
                        (Double) rs.getObject(9, Double.class), (Double) rs.getObject(10, Double.class),
                        (Double) rs.getObject(11, Double.class), events.getOrDefault(rs.getString(1), List.of())),
                screenId);
    }

    /** 화면 하나의 프로그램 사양 행 (화면 로드 → 요소 순) */
    public List<ProgramRow> program(GraphReader g, ScreenPick s, List<ElementInfo> elements) {
        List<ProgramRow> out = new ArrayList<>();
        for (WorkflowEvent ev : workflow.events(g, s.key(), s.code(), "화면 로드")) {
            out.add(summarize(s, 0, "(화면 로드)", ev));
        }
        for (ElementInfo e : elements) {
            for (WorkflowEvent ev : workflow.events(g, e.key(), e.code(), e.label())) {
                out.add(summarize(s, e.seqNo(), e.label() + " (" + e.code() + ")", ev));
            }
        }
        return out;
    }

    private static ProgramRow summarize(ScreenPick s, int seq, String element, WorkflowEvent ev) {
        String handler = null;
        Set<String> apis = new LinkedHashSet<>();
        Set<String> servers = new LinkedHashSet<>();
        Set<String> sqls = new LinkedHashSet<>();
        Map<String, String> tables = new LinkedHashMap<>();
        for (WorkflowStep st : ev.steps()) {
            switch (st.lane()) {
                case "FRONT" -> {
                    if (handler == null && st.depth() == 1) {
                        handler = st.title();
                    }
                }
                case "API" -> apis.add(st.title());
                case "SERVER" -> servers.add(st.title());
                case "SQL" -> sqls.add(st.title());
                case "DB" -> tables.merge(st.title(), st.crud() == null ? "" : st.crud(), UsageIndex::merge);
                default -> {
                }
            }
        }
        if (handler == null && ev.steps().size() > 1) {
            handler = ev.steps().get(1).title();
        }
        List<String> t = new ArrayList<>();
        tables.forEach((name, crud) -> t.add(crud.isEmpty() ? name : name + " (" + crud + ")"));
        return new ProgramRow(s.code(), s.name(), seq, element, ev.event(), handler, String.join("\n", apis),
                String.join("\n", servers), String.join("\n", sqls), String.join("\n", t));
    }

    public List<Map<String, Object>> programRows(AuthUser user, String systemId, String screenIds, String status) {
        boolean onlyDone = checkAccess(user, systemId);
        GraphReader g = new GraphReader(jdbc, systemId);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ScreenPick s : screens(systemId, screenIds, status, onlyDone)) {
            program(g, s, elements(s.screenId())).forEach(r -> rows.add(r.map()));
        }
        return rows;
    }

    // ───────────────────────── 테이블 정의서 ─────────────────────────

    public record TableInfo(String id, String key, String schema, String name, String comment, String subjectArea) {
    }

    public List<TableInfo> tables(String systemId) {
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        List<Object> args = new ArrayList<>(List.of(systemId));
        String run = "";
        if (latest != null) {
            run = " AND (N.MANUAL_YN = 'Y' OR N.LAST_RUN_ID = ? OR N.LAST_RUN_ID IS NULL)";
            args.add(latest);
        }
        return jdbc.query("""
                SELECT N.NODE_ID, N.NODE_KEY, T.SCHEMA_NM, COALESCE(T.TABLE_NM, N.NAME),
                       COALESCE(T.COMMENT_MANUAL, T.COMMENT_TXT), T.SUBJECT_AREA
                  FROM AS_NODE N LEFT JOIN AS_TABLE T ON T.TABLE_ID = N.NODE_ID
                 WHERE N.SYSTEM_ID = ? AND N.NODE_TYPE = 'TABLE'""" + run + " ORDER BY 4",
                (rs, i) -> new TableInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), args.toArray());
    }

    /** 테이블 키 → FK 컬럼(대문자) 집합 (fk·수동·확정 관계, 기각 제외) */
    public Map<String, Set<String>> fkColumns(String systemId) {
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.query("SELECT CHILD_KEY, COLUMN_PAIRS FROM AS_RELATION WHERE SYSTEM_ID = ? AND DECISION <> 'rejected'"
                + " AND (KIND IN ('fk', 'manual') OR DECISION = 'confirmed')", rs -> {
                    Set<String> cols = out.computeIfAbsent(rs.getString(1), k -> new HashSet<>());
                    try {
                        om.readTree(rs.getString(2)).forEach(p -> cols.add(p.get(0).asText().toUpperCase()));
                    } catch (Exception ignore) {
                        // 형식 오류는 무시
                    }
                }, systemId);
        return out;
    }

    public List<Map<String, Object>> tableColumnRows(AuthUser user, String systemId) {
        checkAccess(user, systemId);
        Map<String, Set<String>> fks = fkColumns(systemId);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<TableInfo> tables = tables(systemId);
        Map<String, TableInfo> byId = new HashMap<>();
        tables.forEach(t -> byId.put(t.id(), t));
        List<String> ids = tables.stream().map(TableInfo::id).toList();
        for (int i = 0; i < ids.size(); i += 500) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
            List<Map<String, Object>> part = jdbc.query("SELECT TABLE_ID, ORD, COLUMN_NM, DATA_TYPE, NULLABLE, PK,"
                    + " DEFAULT_VAL, COMMENT_TXT FROM AS_COLUMN WHERE TABLE_ID IN ("
                    + String.join(",", Collections.nCopies(chunk.size(), "?")) + ")", (rs, n) -> {
                        TableInfo t = byId.get(rs.getString(1));
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("table", t.name());
                        m.put("tableComment", t.comment());
                        m.put("subjectArea", t.subjectArea());
                        m.put("no", rs.getInt(2));
                        m.put("column", rs.getString(3));
                        m.put("dataType", rs.getString(4));
                        m.put("nullable", rs.getObject(5) == null || rs.getBoolean(5) ? "Y" : "N");
                        m.put("pk", rs.getBoolean(6) ? "Y" : "");
                        m.put("fk", fks.getOrDefault(t.key(), Set.of()).contains(rs.getString(3).toUpperCase())
                                ? "Y" : "");
                        m.put("default", rs.getString(7));
                        m.put("comment", rs.getString(8));
                        return m;
                    }, chunk.toArray());
            rows.addAll(part);
        }
        rows.sort((a, b) -> {
            int c = String.valueOf(a.get("table")).compareTo(String.valueOf(b.get("table")));
            return c != 0 ? c : Integer.compare((Integer) a.get("no"), (Integer) b.get("no"));
        });
        return rows;
    }

    // ───────────────────────── 인터페이스 목록 ─────────────────────────

    public List<Map<String, Object>> interfaceRows(AuthUser user, String systemId) {
        checkAccess(user, systemId);
        GraphReader g = new GraphReader(jdbc, systemId);
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        List<Object> args = new ArrayList<>(List.of(systemId));
        String run = "";
        if (latest != null) {
            run = " AND (MANUAL_YN = 'Y' OR LAST_RUN_ID = ? OR LAST_RUN_ID IS NULL)";
            args.add(latest);
        }
        List<String[]> apis = jdbc.query("SELECT NODE_KEY, NAME FROM AS_NODE WHERE SYSTEM_ID = ? AND NODE_TYPE = 'API'"
                + run + " ORDER BY NAME", (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, args.toArray());
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, String> screenNames = new HashMap<>();
        for (String[] api : apis) {
            Set<String> servers = new LinkedHashSet<>();
            for (EdgeRow e : g.from(api[0])) {
                if ("handles".equals(e.kind())) {
                    servers.add(g.node(e.to()).map(WorkflowService::title).orElse(e.to()));
                }
            }
            Set<String> callers = new LinkedHashSet<>();
            Set<String> visited = new HashSet<>(List.of(api[0]));
            Set<String> frontier = new LinkedHashSet<>(List.of(api[0]));
            for (int d = 0; d < 6 && !frontier.isEmpty() && callers.size() < 50; d++) {
                Set<String> next = new LinkedHashSet<>();
                for (EdgeRow e : g.to(frontier)) {
                    if ("binds".equals(e.kind()) || !visited.add(e.from())) {
                        continue;
                    }
                    if (e.from().startsWith("el:")) {
                        g.element(e.from()).ifPresent(el -> callers.add(screenNames.computeIfAbsent(el.screenId(),
                                id -> jdbc.queryForObject("SELECT NAME || ' (' || SCREEN_CODE || ')' FROM AS_SCREEN"
                                        + " WHERE SCREEN_ID = ?", String.class, id))));
                    } else if (e.from().startsWith("scr:")) {
                        g.screen(e.from()).ifPresent(s -> callers.add(s.name() + " (" + s.code() + ")"));
                    } else {
                        next.add(e.from());
                    }
                }
                frontier = next;
            }
            String[] mp = api[1].split("\\s+", 2);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", mp.length == 2 ? mp[0] : "");
            m.put("path", mp.length == 2 ? mp[1] : api[1]);
            m.put("serverFunctions", String.join("\n", servers));
            m.put("screens", String.join("\n", callers));
            m.put("screenCount", callers.size());
            rows.add(m);
        }
        return rows;
    }
}
