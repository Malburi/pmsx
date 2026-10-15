package kr.pmsx.as.table;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
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
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 테이블 정의서·주제영역·ERD·관계 보정 (11 명세 §3). 테이블 ID = TABLE 노드 ID. */
@Service
public class TableService {

    public record TableSummary(String tableId, String schema, String name, String comment, String subjectArea,
                               int columnCount, int usedByScreens) {
    }

    public record ColumnDto(String name, String dataType, boolean nullable, boolean pk, boolean fk, String defaultValue,
                            String comment) {
    }

    public record IndexDto(String name, List<String> columns, boolean unique) {
    }

    public record Relation(String relationId, String childTableId, String parentTableId, String childName,
                           String parentName, List<List<String>> columns, String kind, Integer evidenceCount,
                           Double confidence, String decision) {
    }

    public record UsedBy(String screenId, String screenName, String elementId, String elementLabel, String crud,
                         String sqlName) {
    }

    public record TableDetail(String tableId, String schema, String name, String comment, String subjectArea,
                              int columnCount, int usedByScreens, List<ColumnDto> columns, List<IndexDto> indexes,
                              List<Relation> relations, List<UsedBy> usedBy) {
    }

    public record TableUpdate(String subjectArea, String comment) {
    }

    public record SubjectArea(String name, int tableCount) {
    }

    public record ErdColumn(String name, String dataType, boolean pk, boolean fk) {
    }

    public record ErdTable(String tableId, String name, String comment, String subjectArea, List<ErdColumn> columns) {
    }

    public record Erd(List<ErdTable> tables, List<Relation> relations) {
    }

    public record RelationCreate(String childTableId, String parentTableId, List<List<String>> columns) {
    }

    public record RelationPatch(String decision) {
    }

    static final int ERD_LIMIT = 300;
    static final int USED_BY_LIMIT = 500;
    private static final Set<String> DECISIONS = Set.of("pending", "confirmed", "rejected");

    private final JdbcTemplate jdbc;
    private final AsScope scope;
    private final UsageIndex usage;
    private final ObjectMapper om;
    private final AuditService audit;

    public TableService(JdbcTemplate jdbc, AsScope scope, UsageIndex usage, ObjectMapper om, AuditService audit) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.usage = usage;
        this.om = om;
        this.audit = audit;
    }

    // ───────────────────────── 목록·상세 ─────────────────────────

    private record TableRow(String id, String key, String schema, String name, String comment, String subjectArea,
                            int columnCount, String indexesJson) {
    }

    private static final String TABLE_SELECT = """
            SELECT N.NODE_ID, N.NODE_KEY, T.SCHEMA_NM, COALESCE(T.TABLE_NM, N.NAME) AS TNAME,
                   COALESCE(T.COMMENT_MANUAL, T.COMMENT_TXT) AS TCOMMENT, T.SUBJECT_AREA,
                   (SELECT COUNT(*) FROM AS_COLUMN C WHERE C.TABLE_ID = N.NODE_ID) AS COL_CNT, T.INDEXES_JSON
              FROM AS_NODE N LEFT JOIN AS_TABLE T ON T.TABLE_ID = N.NODE_ID
             WHERE N.SYSTEM_ID = ? AND N.NODE_TYPE = 'TABLE'""";

    private static TableRow tableRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new TableRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getInt(7), rs.getString(8));
    }

    /** 최신 회차 테이블 + 수동 테이블 조건 */
    private static String run(String latest) {
        return latest == null ? "" : " AND (N.MANUAL_YN = 'Y' OR N.LAST_RUN_ID = ? OR N.LAST_RUN_ID IS NULL)";
    }

    public PageDto<TableSummary> tables(AuthUser user, String systemId, String q, String subjectArea, Integer page,
            Integer size) {
        scope.read(user, systemId);
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        StringBuilder sql = new StringBuilder(TABLE_SELECT).append(run(latest));
        List<Object> args = new ArrayList<>(List.of(systemId));
        if (latest != null) {
            args.add(latest);
        }
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                    + "%";
            sql.append(" AND (LOWER(COALESCE(T.TABLE_NM, N.NAME)) LIKE ? ESCAPE '\\'"
                    + " OR LOWER(COALESCE(T.COMMENT_MANUAL, T.COMMENT_TXT, '')) LIKE ? ESCAPE '\\')");
            args.add(like);
            args.add(like);
        }
        if (subjectArea != null && !subjectArea.isEmpty()) {
            if ("-".equals(subjectArea)) {
                sql.append(" AND T.SUBJECT_AREA IS NULL");
            } else {
                sql.append(" AND T.SUBJECT_AREA = ?");
                args.add(subjectArea);
            }
        }
        int[] ps = PageDto.normalize(page, size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + sql + ") X", Long.class, args.toArray());
        args.add(ps[1]);
        args.add((long) ps[0] * ps[1]);
        List<TableRow> rows = jdbc.query(sql + " ORDER BY TNAME, N.NODE_KEY LIMIT ? OFFSET ?",
                (rs, i) -> tableRow(rs), args.toArray());
        Map<String, Integer> screenCounts = screenCounts(systemId, rows.stream().map(TableRow::key).toList());
        List<TableSummary> content = rows.stream().map(r -> new TableSummary(r.id(), r.schema(), r.name(),
                r.comment(), r.subjectArea(), r.columnCount(), screenCounts.getOrDefault(r.key(), 0))).toList();
        return new PageDto<>(content, ps[0], ps[1], total == null ? 0 : total);
    }

    /** 테이블 키 → 사용 화면 수 (UsageIndex 기준: 요소 → 그 화면, 화면 로드 → 그 화면) */
    private Map<String, Integer> screenCounts(String systemId, Collection<String> tableKeys) {
        UsageIndex.Index idx = usage.get(systemId);
        Map<String, Integer> out = new HashMap<>();
        tableKeys.forEach(t -> out.put(t, idx.screenCount(t)));
        return out;
    }

    private TableRow tableRow(String tableId) {
        return jdbc.query("""
                SELECT N.NODE_ID, N.NODE_KEY, T.SCHEMA_NM, COALESCE(T.TABLE_NM, N.NAME) AS TNAME,
                       COALESCE(T.COMMENT_MANUAL, T.COMMENT_TXT) AS TCOMMENT, T.SUBJECT_AREA,
                       (SELECT COUNT(*) FROM AS_COLUMN C WHERE C.TABLE_ID = N.NODE_ID) AS COL_CNT, T.INDEXES_JSON
                  FROM AS_NODE N LEFT JOIN AS_TABLE T ON T.TABLE_ID = N.NODE_ID
                 WHERE N.NODE_ID = ? AND N.NODE_TYPE = 'TABLE'""", (rs, i) -> tableRow(rs), tableId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("테이블"));
    }

    private String systemOf(String tableId) {
        return jdbc.query("SELECT SYSTEM_ID FROM AS_NODE WHERE NODE_ID = ? AND NODE_TYPE = 'TABLE'",
                (rs, i) -> rs.getString(1), tableId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("테이블"));
    }

    public TableDetail table(AuthUser user, String tableId) {
        String systemId = systemOf(tableId);
        scope.read(user, systemId);
        TableRow t = tableRow(tableId);
        List<Relation> relations = relationsOf(systemId, Set.of(t.key()), false, true);
        Set<String> fkCols = fkColumns(t.key(), relations, tableId);
        List<ColumnDto> columns = jdbc.query("SELECT COLUMN_NM, DATA_TYPE, NULLABLE, PK, DEFAULT_VAL, COMMENT_TXT"
                        + " FROM AS_COLUMN WHERE TABLE_ID = ? ORDER BY ORD",
                (rs, i) -> new ColumnDto(rs.getString(1), rs.getString(2),
                        rs.getObject(3) == null || rs.getBoolean(3), rs.getBoolean(4),
                        fkCols.contains(rs.getString(1).toUpperCase()), rs.getString(5), rs.getString(6)), tableId);
        List<IndexDto> indexes = new ArrayList<>();
        if (t.indexesJson() != null) {
            try {
                for (JsonNode ix : om.readTree(t.indexesJson())) {
                    List<String> cols = new ArrayList<>();
                    ix.path("columns").forEach(c -> cols.add(c.asText()));
                    indexes.add(new IndexDto(ix.path("name").asText(null), cols, ix.path("unique").asBoolean(false)));
                }
            } catch (Exception ignore) {
                // 수집 데이터의 인덱스 형식이 다르면 생략
            }
        }
        List<UsedBy> usedBy = usedBy(systemId, t.key());
        int screens = (int) usedBy.stream().map(UsedBy::screenId).distinct().count();
        return new TableDetail(t.id(), t.schema(), t.name(), t.comment(), t.subjectArea(), t.columnCount(), screens,
                columns, indexes, relations, usedBy);
    }

    private static Set<String> fkColumns(String tableKey, List<Relation> relations, String tableId) {
        Set<String> out = new HashSet<>();
        for (Relation r : relations) {
            boolean counts = "fk".equals(r.kind()) || "manual".equals(r.kind()) || "confirmed".equals(r.decision());
            if (counts && tableId.equals(r.childTableId()) && !"rejected".equals(r.decision())) {
                r.columns().forEach(p -> out.add(p.get(0).toUpperCase()));
            }
        }
        return out;
    }

    /** 테이블을 접근하는 SQL 에서 거꾸로 올라가 요소·화면 로드까지 (SQL 이름·CRUD 포함) */
    List<UsedBy> usedBy(String systemId, String tableKey) {
        GraphReader g = new GraphReader(jdbc, systemId);
        Map<String, UsedBy> found = new LinkedHashMap<>();
        Map<String, String> screenNames = new HashMap<>();
        for (EdgeRow acc : g.to(List.of(tableKey))) {
            if (!"accesses".equals(acc.kind())) {
                continue;
            }
            String sqlName = g.node(acc.from()).map(GraphReader.NodeRow::name).orElse(acc.from());
            Set<String> visited = new HashSet<>(List.of(acc.from()));
            Set<String> frontier = new LinkedHashSet<>(List.of(acc.from()));
            for (int depth = 0; depth < 12 && !frontier.isEmpty() && visited.size() < 2000; depth++) {
                Set<String> next = new LinkedHashSet<>();
                for (EdgeRow e : g.to(frontier)) {
                    if ("binds".equals(e.kind()) || !visited.add(e.from())) {
                        continue;
                    }
                    if (e.from().startsWith("el:")) {
                        var el = g.element(e.from());
                        if (el.isPresent()) {
                            String sid = el.get().screenId();
                            String sname = screenNames.computeIfAbsent(sid, id -> jdbc.queryForObject(
                                    "SELECT NAME FROM AS_SCREEN WHERE SCREEN_ID = ?", String.class, id));
                            String k = el.get().id() + "\u0001" + sqlName;
                            UsedBy prev = found.get(k);
                            found.put(k, new UsedBy(sid, sname, el.get().id(),
                                    el.get().label() == null ? el.get().code() : el.get().label(),
                                    prev == null ? acc.crud() : UsageIndex.merge(prev.crud(), acc.crud()), sqlName));
                        }
                    } else if (e.from().startsWith("scr:")) {
                        var s = g.screen(e.from());
                        if (s.isPresent()) {
                            String k = s.get().id() + "\u0001" + sqlName;
                            UsedBy prev = found.get(k);
                            found.put(k, new UsedBy(s.get().id(), s.get().name(), null, null,
                                    prev == null ? acc.crud() : UsageIndex.merge(prev.crud(), acc.crud()), sqlName));
                        }
                    } else {
                        next.add(e.from());
                    }
                    if (found.size() >= USED_BY_LIMIT) {
                        break;
                    }
                }
                frontier = next;
            }
            if (found.size() >= USED_BY_LIMIT) {
                break;
            }
        }
        List<UsedBy> out = new ArrayList<>(found.values());
        out.sort((a, b) -> {
            int c = a.screenName().compareTo(b.screenName());
            return c != 0 ? c : String.valueOf(a.elementLabel()).compareTo(String.valueOf(b.elementLabel()));
        });
        return out;
    }

    @Transactional
    public TableSummary update(AuthUser user, String tableId, TableUpdate req) {
        String systemId = systemOf(tableId);
        scope.requireEdit(user, systemId, null, ProjectAccess.RELATION_EDIT);
        if (req == null || (req.subjectArea() == null && req.comment() == null)) {
            throw AsScope.validation("body", "변경할 값을 보내주세요.");
        }
        if (req.subjectArea() != null && req.subjectArea().length() > 100) {
            throw AsScope.validation("subjectArea", "100자 이내로 입력해주세요.");
        }
        if (req.comment() != null && req.comment().length() > 1000) {
            throw AsScope.validation("comment", "1000자 이내로 입력해주세요.");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM AS_TABLE WHERE TABLE_ID = ?", Integer.class,
                tableId);
        if (exists == null || exists == 0) {
            String name = jdbc.queryForObject("SELECT NAME FROM AS_NODE WHERE NODE_ID = ?", String.class, tableId);
            jdbc.update("INSERT INTO AS_TABLE (TABLE_ID, SYSTEM_ID, TABLE_NM, KIND) VALUES (?, ?, ?, 'table')", tableId,
                    systemId, name);
        }
        if (req.subjectArea() != null) {
            jdbc.update("UPDATE AS_TABLE SET SUBJECT_AREA = ? WHERE TABLE_ID = ?",
                    req.subjectArea().isBlank() ? null : req.subjectArea().trim(), tableId);
        }
        if (req.comment() != null) {
            jdbc.update("UPDATE AS_TABLE SET COMMENT_MANUAL = ? WHERE TABLE_ID = ?",
                    req.comment().isBlank() ? null : req.comment().trim(), tableId);
        }
        audit.log(Action.UPDATE, "table:" + tableId, req.toString(), scope.projectId(systemId));
        TableRow t = tableRow(tableId);
        int screens = screenCounts(systemId, List.of(t.key())).getOrDefault(t.key(), 0);
        return new TableSummary(t.id(), t.schema(), t.name(), t.comment(), t.subjectArea(), t.columnCount(), screens);
    }

    public List<SubjectArea> subjectAreas(AuthUser user, String systemId) {
        scope.read(user, systemId);
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        List<Object> args = new ArrayList<>(List.of(systemId));
        if (latest != null) {
            args.add(latest);
        }
        return jdbc.query("SELECT T.SUBJECT_AREA, COUNT(*) FROM AS_NODE N JOIN AS_TABLE T ON T.TABLE_ID = N.NODE_ID"
                        + " WHERE N.SYSTEM_ID = ? AND N.NODE_TYPE = 'TABLE'" + run(latest)
                        + " AND T.SUBJECT_AREA IS NOT NULL GROUP BY T.SUBJECT_AREA ORDER BY T.SUBJECT_AREA",
                (rs, i) -> new SubjectArea(rs.getString(1), rs.getInt(2)), args.toArray());
    }

    // ───────────────────────── ERD ─────────────────────────

    public Erd erd(AuthUser user, String systemId, String subjectArea, String tableIds, boolean includeInferred) {
        scope.read(user, systemId);
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        StringBuilder sql = new StringBuilder(TABLE_SELECT).append(run(latest));
        List<Object> args = new ArrayList<>(List.of(systemId));
        if (latest != null) {
            args.add(latest);
        }
        if (tableIds != null && !tableIds.isBlank()) {
            List<String> ids = List.of(tableIds.split(",")).stream().map(String::trim).filter(s -> !s.isEmpty())
                    .limit(ERD_LIMIT).toList();
            sql.append(" AND N.NODE_ID IN (").append(String.join(",", Collections.nCopies(ids.size(), "?")))
                    .append(")");
            args.addAll(ids);
        } else if (subjectArea != null && !subjectArea.isEmpty()) {
            if ("-".equals(subjectArea)) {
                sql.append(" AND T.SUBJECT_AREA IS NULL");
            } else {
                sql.append(" AND T.SUBJECT_AREA = ?");
                args.add(subjectArea);
            }
        }
        sql.append(" ORDER BY TNAME FETCH FIRST ").append(ERD_LIMIT).append(" ROWS ONLY");
        List<TableRow> rows = new ArrayList<>(jdbc.query(sql.toString(), (rs, i) -> tableRow(rs), args.toArray()));
        Set<String> keys = new LinkedHashSet<>();
        rows.forEach(r -> keys.add(r.key()));
        if (tableIds != null && !tableIds.isBlank()) {
            // 선택 테이블 + 1단계 관계 테이블 (11 v0.2)
            Set<String> neighbors = new LinkedHashSet<>();
            for (Relation r : relationsOf(systemId, keys, false, includeInferred)) {
                neighbors.add(r.childTableId());
                neighbors.add(r.parentTableId());
            }
            rows.forEach(r -> neighbors.remove(r.id()));
            List<String> extra = new ArrayList<>(neighbors);
            for (int i = 0; i < extra.size() && rows.size() < ERD_LIMIT; i++) {
                TableRow t = tableRow(extra.get(i));
                rows.add(t);
                keys.add(t.key());
            }
        }
        List<Relation> relations = relationsOf(systemId, keys, true, includeInferred);
        Map<String, Set<String>> fkByTable = new HashMap<>();
        for (Relation r : relations) {
            boolean counts = "fk".equals(r.kind()) || "manual".equals(r.kind()) || "confirmed".equals(r.decision());
            if (counts && !"rejected".equals(r.decision())) {
                r.columns().forEach(p -> fkByTable.computeIfAbsent(r.childTableId(), k -> new HashSet<>())
                        .add(p.get(0).toUpperCase()));
            }
        }
        Map<String, List<ErdColumn>> cols = new HashMap<>();
        List<String> ids = rows.stream().map(TableRow::id).toList();
        for (int i = 0; i < ids.size(); i += 500) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
            jdbc.query("SELECT TABLE_ID, COLUMN_NM, DATA_TYPE, PK FROM AS_COLUMN WHERE TABLE_ID IN ("
                    + String.join(",", Collections.nCopies(chunk.size(), "?")) + ") ORDER BY TABLE_ID, ORD", rs -> {
                        String t = rs.getString(1);
                        cols.computeIfAbsent(t, k -> new ArrayList<>()).add(new ErdColumn(rs.getString(2),
                                rs.getString(3), rs.getBoolean(4),
                                fkByTable.getOrDefault(t, Set.of()).contains(rs.getString(2).toUpperCase())));
                    }, chunk.toArray());
        }
        List<ErdTable> tables = rows.stream().map(r -> new ErdTable(r.id(), r.name(), r.comment(), r.subjectArea(),
                cols.getOrDefault(r.id(), List.of()))).toList();
        return new Erd(tables, relations);
    }

    /**
     * keys 와 관련된 관계. bothEnds=true 면 양 끝이 모두 keys 안(ERD), false 면 한쪽만 걸려도(상세).
     * includeInferred=false 면 추정 관계는 확정된 것만.
     */
    private List<Relation> relationsOf(String systemId, Set<String> keys, boolean bothEnds, boolean includeInferred) {
        if (keys.isEmpty()) {
            return List.of();
        }
        List<Relation> out = new ArrayList<>();
        Map<String, String[]> nodeByKey = new HashMap<>();   // key → [id, name]
        List<String> list = new ArrayList<>(keys);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i += 400) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + 400));
            String in = String.join(",", Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>(List.of(systemId));
            args.addAll(chunk);
            args.addAll(chunk);
            jdbc.query("SELECT REL_ID, CHILD_KEY, PARENT_KEY, COLUMN_PAIRS, KIND, EVIDENCE_CNT, CONFIDENCE, DECISION"
                    + " FROM AS_RELATION WHERE SYSTEM_ID = ? AND (CHILD_KEY IN (" + in + ") OR PARENT_KEY IN (" + in
                    + ")) ORDER BY CHILD_KEY, PARENT_KEY", rs -> {
                        String relId = rs.getString(1);
                        String child = rs.getString(2);
                        String parent = rs.getString(3);
                        String kind = rs.getString(5);
                        String decision = rs.getString(8);
                        if (!seen.add(relId) || (bothEnds && !(keys.contains(child) && keys.contains(parent)))) {
                            return;
                        }
                        if (!includeInferred && "inferred".equals(kind) && !"confirmed".equals(decision)) {
                            return;
                        }
                        String[] c = nodeByKey.computeIfAbsent(child, k -> node(systemId, k));
                        String[] p = nodeByKey.computeIfAbsent(parent, k -> node(systemId, k));
                        if (c == null || p == null) {
                            return;
                        }
                        out.add(new Relation(relId, c[0], p[0], c[1], p[1], pairs(rs.getString(4)), kind,
                                (Integer) rs.getObject(6, Integer.class), (Double) rs.getObject(7, Double.class),
                                decision));
                    }, args.toArray());
        }
        return out;
    }

    private String[] node(String systemId, String key) {
        return jdbc.query("SELECT NODE_ID, NAME FROM AS_NODE WHERE SYSTEM_ID = ? AND NODE_KEY = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, systemId, key).stream().findFirst()
                .orElse(null);
    }

    private List<List<String>> pairs(String json) {
        List<List<String>> out = new ArrayList<>();
        try {
            for (JsonNode p : om.readTree(json)) {
                out.add(List.of(p.get(0).asText(), p.get(1).asText()));
            }
        } catch (Exception ignore) {
            // 형식 오류 시 빈 목록
        }
        return out;
    }

    // ───────────────────────── 관계 보정 ─────────────────────────

    @Transactional
    public Relation createRelation(AuthUser user, RelationCreate req) {
        if (req == null || req.childTableId() == null || req.parentTableId() == null) {
            throw AsScope.validation("childTableId", "자식·부모 테이블을 지정해주세요.");
        }
        String systemId = systemOf(req.childTableId());
        if (!systemId.equals(systemOf(req.parentTableId()))) {
            throw AsScope.validation("parentTableId", "같은 시스템의 테이블이어야 합니다.");
        }
        scope.requireEdit(user, systemId, null, ProjectAccess.RELATION_EDIT);
        if (req.columns() == null || req.columns().isEmpty()) {
            throw AsScope.validation("columns", "연결 컬럼을 하나 이상 지정해주세요.");
        }
        Set<String> childCols = columnNames(req.childTableId());
        Set<String> parentCols = columnNames(req.parentTableId());
        List<List<String>> pairs = new ArrayList<>();
        for (List<String> p : req.columns()) {
            if (p == null || p.size() != 2 || p.get(0) == null || p.get(1) == null) {
                throw AsScope.validation("columns", "[자식 컬럼, 부모 컬럼] 형식이어야 합니다.");
            }
            if (!childCols.isEmpty() && !childCols.contains(p.get(0).toUpperCase())) {
                throw AsScope.validation("columns", "자식 테이블에 컬럼 " + p.get(0) + " 이 없습니다.");
            }
            if (!parentCols.isEmpty() && !parentCols.contains(p.get(1).toUpperCase())) {
                throw AsScope.validation("columns", "부모 테이블에 컬럼 " + p.get(1) + " 이 없습니다.");
            }
            pairs.add(List.of(p.get(0), p.get(1)));
        }
        String childKey = jdbc.queryForObject("SELECT NODE_KEY FROM AS_NODE WHERE NODE_ID = ?", String.class,
                req.childTableId());
        String parentKey = jdbc.queryForObject("SELECT NODE_KEY FROM AS_NODE WHERE NODE_ID = ?", String.class,
                req.parentTableId());
        String id = Ulid.next();
        try {
            jdbc.update("INSERT INTO AS_RELATION (REL_ID, SYSTEM_ID, CHILD_KEY, PARENT_KEY, COLUMN_PAIRS, KIND, DECISION,"
                            + " CREATED_BY, UPDATED_AT, UPDATED_BY) VALUES (?, ?, ?, ?, ?, 'manual', 'confirmed', ?, ?, ?)",
                    id, systemId, childKey, parentKey, om.writeValueAsString(pairs), user.userId(), Times.now(),
                    user.userId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "같은 관계가 이미 있습니다.");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        audit.log(Action.CREATE, "relation:" + childKey + " → " + parentKey, null, scope.projectId(systemId));
        return relation(id);
    }

    @Transactional
    public Relation patchRelation(AuthUser user, String relationId, RelationPatch req) {
        String systemId = jdbc.query("SELECT SYSTEM_ID FROM AS_RELATION WHERE REL_ID = ?", (rs, i) -> rs.getString(1),
                relationId).stream().findFirst().orElseThrow(() -> ApiException.notFound("관계"));
        scope.requireEdit(user, systemId, null, ProjectAccess.RELATION_EDIT);
        if (req == null || req.decision() == null || !DECISIONS.contains(req.decision())) {
            throw AsScope.validation("decision", "confirmed, rejected, pending 중 하나여야 합니다.");
        }
        jdbc.update("UPDATE AS_RELATION SET DECISION = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE REL_ID = ?",
                req.decision(), Times.now(), user.userId(), relationId);
        audit.log(Action.UPDATE, "relation:" + relationId, req.decision(), scope.projectId(systemId));
        return relation(relationId);
    }

    private Relation relation(String relationId) {
        var row = jdbc.query("SELECT SYSTEM_ID, CHILD_KEY FROM AS_RELATION WHERE REL_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, relationId).get(0);
        return relationsOf(row[0], Set.of(row[1]), false, true).stream()
                .filter(r -> r.relationId().equals(relationId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("관계"));
    }

    private Set<String> columnNames(String tableId) {
        Set<String> out = new HashSet<>();
        jdbc.queryForList("SELECT COLUMN_NM FROM AS_COLUMN WHERE TABLE_ID = ?", String.class, tableId)
                .forEach(c -> out.add(c.toUpperCase()));
        return out;
    }
}
