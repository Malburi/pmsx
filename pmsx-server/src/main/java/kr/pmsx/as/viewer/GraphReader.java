package kr.pmsx.as.viewer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 한 시스템의 연결 그래프(AS_EDGE + 노드/화면/요소)를 요청 단위로 읽고 캐시한다.
 * 전체 그래프를 메모리에 올리지 않고 필요한 키만 조회한다(연결선 100만 건 규모 대비).
 * 활성 엣지 = STATUS 'active' 이면서 최신 수집 회차에 있거나 수동·수정·확정 엣지.
 */
public final class GraphReader {

    public record EdgeRow(String id, String from, String to, String kind, String event, Integer ord, String cond,
                          String crud, String columns, String source, double confidence, String evidence,
                          boolean confirmed, String status) {

        public boolean rejected() {
            return "rejected".equals(status);
        }
    }

    public record NodeRow(String id, String key, String type, String name, String lang, String file,
                          Integer lineStart, Integer lineEnd, boolean hasSnippet, boolean common, String metaJson,
                          String tableComment) {
    }

    public record ScreenRow(String id, String key, String code, String name, String kind) {
    }

    public record ElementRow(String id, String key, String screenId, int seqNo, String code, String label) {
    }

    /** 워크플로우·CRUD·역추적에서 따라가는 엣지 종류 (event 는 요소 기점에서만, binds 는 제외) */
    public static final List<String> FLOW_KINDS = List.of("calls", "request", "handles", "executes", "accesses",
            "navigates");

    private static final String EDGE_COLS = "EDGE_ID, FROM_KEY, TO_KEY, KIND, EVENT, ORD, COND, CRUD, COLUMNS_TXT,"
            + " SOURCE, CONFIDENCE, EVIDENCE, CONFIRMED, STATUS";

    private final JdbcTemplate jdbc;
    private final String systemId;
    private final String runFilter;
    private final List<Object> runArgs;
    private final boolean includeRejected;
    private final Map<String, List<EdgeRow>> fromCache = new HashMap<>();
    private final Map<String, Optional<NodeRow>> nodeCache = new HashMap<>();
    private final Map<String, Optional<ScreenRow>> screenCache = new HashMap<>();
    private final Map<String, Optional<ElementRow>> elementCache = new HashMap<>();

    public GraphReader(JdbcTemplate jdbc, String systemId) {
        this(jdbc, systemId, false);
    }

    /** includeRejected=true: from() 이 기각 엣지도 돌려준다 (워크플로우 표시용, 탐색은 호출자가 멈춘다). */
    public GraphReader(JdbcTemplate jdbc, String systemId, boolean includeRejected) {
        this.jdbc = jdbc;
        this.systemId = systemId;
        this.includeRejected = includeRejected;
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        if (latest == null) {
            runFilter = "";
            runArgs = List.of();
        } else {
            runFilter = " AND (LAST_RUN_ID = ? OR SOURCE = 'manual' OR EDITED = TRUE OR CONFIRMED = TRUE OR LAST_RUN_ID IS NULL)";
            runArgs = List.of(latest);
        }
    }

    /** 활성 엣지 조건 (AS_EDGE 별칭 없이). activeArgs() 를 순서대로 바인딩한다. */
    public String activeClause() {
        return " AND STATUS = 'active'" + runFilter;
    }

    public List<Object> activeArgs() {
        return runArgs;
    }

    public String systemId() {
        return systemId;
    }

    public List<EdgeRow> from(String key) {
        return fromCache.computeIfAbsent(key, k -> {
            List<Object> args = new ArrayList<>(List.of(systemId, k));
            args.addAll(runArgs);
            String status = includeRejected ? " AND STATUS IN ('active', 'rejected')" + runFilter : activeClause();
            return jdbc.query("SELECT " + EDGE_COLS + " FROM AS_EDGE WHERE SYSTEM_ID = ? AND FROM_KEY = ?"
                    + status
                    + " ORDER BY CASE WHEN ORD IS NULL THEN 1 ELSE 0 END, ORD, MODEL_SEQ, TO_KEY",
                    (rs, i) -> edge(rs), args.toArray());
        });
    }

    /** TO_KEY 가 keys 중 하나인 활성 엣지 (역추적용). */
    public List<EdgeRow> to(Collection<String> keys) {
        List<EdgeRow> out = new ArrayList<>();
        List<String> list = new ArrayList<>(keys);
        for (int i = 0; i < list.size(); i += 500) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + 500));
            String in = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(systemId);
            args.addAll(chunk);
            args.addAll(runArgs);
            out.addAll(jdbc.query("SELECT " + EDGE_COLS + " FROM AS_EDGE WHERE SYSTEM_ID = ?"
                    + " AND TO_KEY IN (" + in + ")" + activeClause() + " ORDER BY FROM_KEY",
                    (rs, n) -> edge(rs), args.toArray()));
        }
        return out;
    }

    public Optional<NodeRow> node(String key) {
        return nodeCache.computeIfAbsent(key, k -> jdbc.query("""
                SELECT N.NODE_ID, N.NODE_KEY, N.NODE_TYPE, N.NAME, N.LANG, N.FILE_PATH, N.LINE_START, N.LINE_END,
                       CASE WHEN N.SNIPPET IS NULL THEN 0 ELSE 1 END AS HAS_SNIP, N.COMMON, N.META_JSON,
                       COALESCE(T.COMMENT_MANUAL, T.COMMENT_TXT)
                  FROM AS_NODE N LEFT JOIN AS_TABLE T ON T.TABLE_ID = N.NODE_ID
                 WHERE N.SYSTEM_ID = ? AND N.NODE_KEY = ?""",
                (rs, i) -> new NodeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), (Integer) rs.getObject(7, Integer.class),
                        (Integer) rs.getObject(8, Integer.class), rs.getInt(9) == 1, rs.getBoolean(10),
                        rs.getString(11), rs.getString(12)), systemId, k).stream().findFirst());
    }

    public Optional<ScreenRow> screen(String key) {
        return screenCache.computeIfAbsent(key, k -> jdbc.query(
                "SELECT SCREEN_ID, SCREEN_KEY, SCREEN_CODE, NAME, KIND FROM AS_SCREEN WHERE SYSTEM_ID = ?"
                        + " AND SCREEN_KEY = ?",
                (rs, i) -> new ScreenRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), systemId, k).stream().findFirst());
    }

    public Optional<ElementRow> element(String key) {
        return elementCache.computeIfAbsent(key, k -> jdbc.query(
                "SELECT ELEMENT_ID, ELEMENT_KEY, SCREEN_ID, SEQ_NO, ELEMENT_CODE, LABEL FROM AS_ELEMENT"
                        + " WHERE SYSTEM_ID = ? AND ELEMENT_KEY = ?",
                (rs, i) -> new ElementRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getString(6)), systemId, k).stream().findFirst());
    }

    private static EdgeRow edge(java.sql.ResultSet rs) throws java.sql.SQLException {
        String event = rs.getString(5);     // Oracle·Tibero 는 빈 문자열을 NULL 로 돌려준다
        return new EdgeRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), event == null ? "" : event,
                (Integer) rs.getObject(6, Integer.class), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), rs.getDouble(11), rs.getString(12), rs.getBoolean(13), rs.getString(14));
    }
}
