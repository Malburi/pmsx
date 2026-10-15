package kr.pmsx.as.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.pmsx.as.ingest.RunDtos.Diff;
import kr.pmsx.as.ingest.RunDtos.RunSummary;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import kr.pmsx.pj.ProjectAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import kr.pmsx.as.GraphChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

/**
 * 수집 결과 패키지 적재 (08 명세 §3).
 * - 키 기준 upsert, 대리 ID 유지
 * - source='manual' 엣지, MANUAL_YN='Y' 요소는 덮어쓰지 않음
 * - 이번 회차에 없는 객체는 삭제하지 않고 LAST_RUN_ID 미갱신으로 남김
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);
    private static final char SEP = '\u0001';

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final ModelValidator validator;
    private final FileStorageService files;
    private final ProjectAccess access;
    private final TransactionTemplate tx;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public IngestService(JdbcTemplate jdbc, ObjectMapper om, ModelValidator validator, FileStorageService files,
            ProjectAccess access, TransactionTemplate tx, AuditService audit, ApplicationEventPublisher events) {
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
        this.om = om;
        this.validator = validator;
        this.files = files;
        this.access = access;
        this.tx = tx;
    }

    public RunSummary ingest(AuthUser user, String systemId, MultipartFile file) {
        access.forSystem(user, systemId).require(ProjectAccess.UPLOAD);
        if (file == null || file.isEmpty()) {
            throw invalid("업로드된 파일이 비어 있습니다.");
        }
        byte[] zip;
        try {
            zip = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<String, byte[]> entries = PackageReader.read(zip);

        JsonNode model;
        try {
            model = om.readTree(entries.get("model.json"));
        } catch (JsonProcessingException e) {
            throw invalid("model.json 을 JSON 으로 읽을 수 없습니다: " + e.getOriginalMessage());
        } catch (IOException e) {
            throw invalid("model.json 을 읽을 수 없습니다.");
        }
        if (model == null || !model.isObject()) {
            throw invalid("model.json 형식이 올바르지 않습니다.");
        }
        List<String> errors = validator.validate(model, 10);
        if (!errors.isEmpty()) {
            throw invalid("model.json 이 스키마를 통과하지 못했습니다: " + String.join(" / ", errors));
        }

        String systemCode = jdbc.queryForObject("SELECT CODE FROM AS_SYSTEM WHERE SYSTEM_ID = ?", String.class,
                systemId);
        String modelCode = model.at("/system/code").asText();
        if (!Objects.equals(systemCode, modelCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SYSTEM_MISMATCH",
                    "패키지의 시스템 코드(" + modelCode + ")가 대상 시스템(" + systemCode + ")과 다릅니다.");
        }
        checkReferences(systemId, model, entries);

        // 파일 저장은 DB 트랜잭션 밖에서 먼저 하고, 적재가 실패하면 저장한 파일을 모두 지운다(고아 파일 방지).
        List<String> saved = new ArrayList<>();
        try {
            String original = file.getOriginalFilename() == null ? "package.zip" : file.getOriginalFilename();
            String packageFileId = track(saved, files.save(zip, original, "application/zip", user.userId()));
            String reportFileId = entries.containsKey("report.html")
                    ? track(saved, files.save(entries.get("report.html"), "report.html", "text/html", user.userId()))
                    : null;
            Map<String, String> captureFileIds = new HashMap<>();
            for (JsonNode s : model.path("screens")) {
                for (JsonNode c : s.path("captures")) {
                    String path = PackageReader.normalize(c.get("file").asText());
                    captureFileIds.computeIfAbsent(path, p -> track(saved, files.save(entries.get(p),
                            p.substring(p.lastIndexOf('/') + 1), mime(p), user.userId())));
                }
            }
            RunSummary summary;
            try {
                summary = tx.execute(
                        status -> load(user, systemId, model, packageFileId, reportFileId, captureFileIds));
            } catch (DataIntegrityViolationException e) {
                // 모델 안의 키 중복 등 — 수집기 결과 문제로 보고 400 으로 돌려준다
                log.warn("패키지 적재 중 무결성 오류", e);
                throw invalid("패키지 내용에 중복되거나 맞지 않는 키가 있어 적재하지 못했습니다: "
                        + e.getMostSpecificCause().getMessage().lines().findFirst().orElse(""));
            }
            events.publishEvent(new GraphChangedEvent(systemId));
            audit.log(AuditService.Action.UPLOAD, "run:" + summary.runId(),
                    original + " (" + summary.seq() + "회차)", projectId(systemId));
            return summary;
        } catch (RuntimeException e) {
            for (String fileId : saved) {
                try {
                    files.delete(fileId);
                } catch (RuntimeException cleanup) {
                    log.warn("적재 실패 후 파일 정리 실패: {}", fileId, cleanup);
                }
            }
            throw e;
        }
    }

    private static String track(List<String> saved, String fileId) {
        saved.add(fileId);
        return fileId;
    }

    private String projectId(String systemId) {
        return jdbc.queryForObject("SELECT PROJECT_ID FROM AS_SYSTEM WHERE SYSTEM_ID = ?", String.class, systemId);
    }

    // ───────────────────────── 사전 검증 ─────────────────────────

    private void checkReferences(String systemId, JsonNode model, Map<String, byte[]> entries) {
        Set<String> screenKeys = new HashSet<>();
        for (JsonNode s : model.path("screens")) {
            screenKeys.add(s.get("key").asText());
            for (JsonNode c : s.path("captures")) {
                String path = PackageReader.normalize(c.get("file").asText());
                if (!entries.containsKey(path)) {
                    throw invalid("캡처 파일이 패키지에 없습니다: " + path);
                }
            }
        }
        Set<String> known = new HashSet<>(jdbc.queryForList(
                "SELECT SCREEN_KEY FROM AS_SCREEN WHERE SYSTEM_ID = ?", String.class, systemId));
        known.addAll(screenKeys);
        for (JsonNode e : model.path("elements")) {
            String sk = e.get("screenKey").asText();
            if (!known.contains(sk)) {
                throw invalid("요소 " + e.get("key").asText() + " 의 화면(" + sk + ")이 없습니다.");
            }
        }
    }

    // ───────────────────────── 적재 ─────────────────────────

    private record Existing(String id, String lastRunId, String extra) {
    }

    private RunSummary load(AuthUser user, String systemId, JsonNode model, String packageFileId,
            String reportFileId, Map<String, String> captureFileIds) {
        JsonNode manifest = model.get("manifest");
        OffsetDateTime collectedAt = parseTime(manifest.path("collectedAt").asText());
        OffsetDateTime uploadedAt = Times.now();

        var prev = jdbc.query("SELECT RUN_ID, SEQ FROM AS_COLLECT_RUN WHERE SYSTEM_ID = ? ORDER BY SEQ DESC"
                + " FETCH FIRST 1 ROWS ONLY", (rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)}, systemId);
        String prevRunId = prev.isEmpty() ? null : (String) prev.get(0)[0];
        int seq = prev.isEmpty() ? 1 : (Integer) prev.get(0)[1] + 1;
        String runId = Ulid.next();

        Map<String, Number> stats = new LinkedHashMap<>();
        manifest.path("stats").fields().forEachRemaining(f -> stats.put(f.getKey(), f.getValue().numberValue()));

        jdbc.update("""
                INSERT INTO AS_COLLECT_RUN (RUN_ID, SYSTEM_ID, SEQ, RUN_KEY, COLLECTED_AT, UPLOADED_AT, UPLOADED_BY,
                    MODEL_VERSION, COLLECTOR_VERSION, PACKAGE_FILE_ID, REPORT_FILE_ID, STATS_JSON, BLOCKED_JSON,
                    FAILURES_JSON)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                runId, systemId, seq, manifest.path("runId").asText(), collectedAt, uploadedAt, user.userId(),
                manifest.path("modelVersion").asText(), manifest.path("collectorVersion").asText(),
                packageFileId, reportFileId, json(stats), json(model.path("blockedRequests")),
                json(model.path("failures")));

        // 화면
        Map<String, Existing> screens = existing("SELECT SCREEN_KEY, SCREEN_ID, LAST_RUN_ID, NULL FROM AS_SCREEN"
                + " WHERE SYSTEM_ID = ?", systemId);
        Map<String, String> screenIdByKey = new HashMap<>();
        screens.forEach((k, v) -> screenIdByKey.put(k, v.id()));
        List<Object[]> scrIns = new ArrayList<>();
        List<Object[]> scrUpd = new ArrayList<>();
        List<Object[]> anaIns = new ArrayList<>();
        Set<String> incomingScreens = new HashSet<>();
        for (JsonNode s : model.path("screens")) {
            String key = s.get("key").asText();
            incomingScreens.add(key);
            Object[] vals = {s.get("screenId").asText(), s.get("name").asText(), txt(s, "kind", "page"),
                    txt(s, "uiTech", null), txt(s, "sourceFile", null), txt(s, "url", null), runId};
            Existing ex = screens.get(key);
            if (ex != null) {
                scrUpd.add(concat(vals, ex.id()));
            } else {
                String id = Ulid.next();
                screenIdByKey.put(key, id);
                scrIns.add(concat(new Object[] {id, systemId, key}, vals));
                anaIns.add(new Object[] {id, uploadedAt, user.userId()});
            }
        }
        jdbc.batchUpdate("INSERT INTO AS_SCREEN (SCREEN_ID, SYSTEM_ID, SCREEN_KEY, SCREEN_CODE, NAME, KIND, UI_TECH,"
                + " SOURCE_FILE, URL, LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", scrIns);
        jdbc.batchUpdate("UPDATE AS_SCREEN SET SCREEN_CODE = ?, NAME = ?, KIND = ?, UI_TECH = ?, SOURCE_FILE = ?,"
                + " URL = ?, LAST_RUN_ID = ? WHERE SCREEN_ID = ?", scrUpd);
        jdbc.batchUpdate("INSERT INTO AS_ANALYSIS (SCREEN_ID, STATUS, UPDATED_AT, UPDATED_BY) VALUES (?, 'AUTO', ?, ?)",
                anaIns);

        // 메뉴
        Map<String, Existing> menus = existing("SELECT MENU_KEY, MENU_ID, LAST_RUN_ID, NULL FROM AS_MENU"
                + " WHERE SYSTEM_ID = ?", systemId);
        List<Object[]> menuIns = new ArrayList<>();
        List<Object[]> menuUpd = new ArrayList<>();
        for (JsonNode m : model.path("menus")) {
            String key = m.get("key").asText();
            Object[] vals = {txt(m, "parentKey", null), m.get("name").asText(), m.path("order").asInt(0),
                    txt(m, "screenKey", null), txt(m, "url", null), runId};
            Existing ex = menus.get(key);
            if (ex != null) {
                menuUpd.add(concat(vals, ex.id()));
            } else {
                menuIns.add(concat(new Object[] {Ulid.next(), systemId, key}, vals));
            }
        }
        jdbc.batchUpdate("INSERT INTO AS_MENU (MENU_ID, SYSTEM_ID, MENU_KEY, PARENT_KEY, NAME, SORT_ORD, SCREEN_KEY,"
                + " URL, LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", menuIns);
        jdbc.batchUpdate("UPDATE AS_MENU SET PARENT_KEY = ?, NAME = ?, SORT_ORD = ?, SCREEN_KEY = ?, URL = ?,"
                + " LAST_RUN_ID = ? WHERE MENU_ID = ?", menuUpd);

        // 캡처 (회차마다 새로 쌓음)
        List<Object[]> capIns = new ArrayList<>();
        for (JsonNode s : model.path("screens")) {
            String screenId = screenIdByKey.get(s.get("key").asText());
            int idx = 0;
            for (JsonNode c : s.path("captures")) {
                capIns.add(new Object[] {Ulid.next(), screenId, runId,
                        captureFileIds.get(PackageReader.normalize(c.get("file").asText())), idx++,
                        c.get("width").asInt(), c.get("height").asInt(), txt(c, "state", null),
                        c.path("masked").asBoolean(false), collectedAt,
                        c.hasNonNull("scale") ? c.get("scale").asDouble() : 1.0});
            }
        }
        jdbc.batchUpdate("INSERT INTO AS_CAPTURE (CAPTURE_ID, SCREEN_ID, RUN_ID, FILE_ID, IDX, WIDTH, HEIGHT, STATE,"
                + " MASKED, COLLECTED_AT, SCALE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", capIns);

        // 요소 (배지 번호는 최초 등록 순서로 고정)
        Map<String, Existing> elements = existing("SELECT ELEMENT_KEY, ELEMENT_ID, LAST_RUN_ID, MANUAL_YN"
                + " FROM AS_ELEMENT WHERE SYSTEM_ID = ?", systemId);
        Map<String, Integer> maxSeq = new HashMap<>();
        jdbc.query("SELECT SCREEN_ID, MAX(SEQ_NO) FROM AS_ELEMENT WHERE SYSTEM_ID = ? GROUP BY SCREEN_ID",
                rs -> {
                    maxSeq.put(rs.getString(1), rs.getInt(2));
                }, systemId);
        List<Object[]> elIns = new ArrayList<>();
        List<Object[]> elUpd = new ArrayList<>();
        List<Object[]> elTouch = new ArrayList<>();
        Set<String> incomingElements = new HashSet<>();
        for (JsonNode e : model.path("elements")) {
            String key = e.get("key").asText();
            if (!incomingElements.add(key)) {
                continue;
            }
            String screenId = screenIdByKey.get(e.get("screenKey").asText());
            JsonNode box = e.path("box");
            Object[] vals = {screenId, e.get("elementId").asText(), txt(e, "name", null), txt(e, "label", null),
                    e.get("type").asText(),
                    box.isObject() ? box.get("x").asDouble() : null, box.isObject() ? box.get("y").asDouble() : null,
                    box.isObject() ? box.get("w").asDouble() : null, box.isObject() ? box.get("h").asDouble() : null,
                    box.isObject() ? box.path("captureIndex").asInt(0) : 0, txt(e, "binding", null),
                    txt(e, "matched", "full"), e.path("danger").asBoolean(false), runId};
            Existing ex = elements.get(key);
            if (ex == null) {
                int next = maxSeq.merge(screenId, 1, Integer::sum);
                elIns.add(concat(new Object[] {Ulid.next(), systemId, key, next}, vals));
            } else if ("Y".equals(ex.extra())) {
                elTouch.add(new Object[] {runId, ex.id()});
            } else {
                elUpd.add(concat(vals, ex.id()));
            }
        }
        jdbc.batchUpdate("INSERT INTO AS_ELEMENT (ELEMENT_ID, SYSTEM_ID, ELEMENT_KEY, SEQ_NO, SCREEN_ID, ELEMENT_CODE,"
                + " NAME, LABEL, TYPE, BOX_X, BOX_Y, BOX_W, BOX_H, CAPTURE_INDEX, BINDING, MATCHED, DANGER,"
                + " LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", elIns);
        jdbc.batchUpdate("UPDATE AS_ELEMENT SET SCREEN_ID = ?, ELEMENT_CODE = ?, NAME = ?, LABEL = ?, TYPE = ?,"
                + " BOX_X = ?, BOX_Y = ?, BOX_W = ?, BOX_H = ?, CAPTURE_INDEX = ?, BINDING = ?, MATCHED = ?,"
                + " DANGER = ?, LAST_RUN_ID = ? WHERE ELEMENT_ID = ?", elUpd);
        jdbc.batchUpdate("UPDATE AS_ELEMENT SET LAST_RUN_ID = ? WHERE ELEMENT_ID = ?", elTouch);

        // 노드 (함수·API·SQL·SP·테이블)
        loadNodes(systemId, model, runId);

        // 관계
        loadRelations(systemId, model, runId);

        // 엣지
        Map<String, Existing> edges = existing("SELECT FROM_KEY || CHR(1) || TO_KEY || CHR(1) || KIND || CHR(1)"
                + " || EVENT, EDGE_ID, LAST_RUN_ID, CASE WHEN SOURCE = 'manual' OR EDITED = TRUE THEN 'manual' ELSE SOURCE END"
                + " FROM AS_EDGE WHERE SYSTEM_ID = ?", systemId);
        Map<String, JsonNode> incomingEdges = new LinkedHashMap<>();
        for (JsonNode e : model.path("edges")) {
            incomingEdges.put(edgeKey(e), e);
        }
        List<Object[]> edIns = new ArrayList<>();
        List<Object[]> edUpd = new ArrayList<>();
        List<Object[]> edTouch = new ArrayList<>();
        int modelSeq = 0;
        for (var en : incomingEdges.entrySet()) {
            JsonNode e = en.getValue();
            Object[] vals = {e.has("order") ? e.get("order").asInt() : null, modelSeq++, txt(e, "condition", null),
                    txt(e, "crud", null), joinArray(e.path("columns")), e.get("source").asText(),
                    e.get("confidence").asDouble(), txt(e, "evidence", null), runId};
            Existing ex = edges.get(en.getKey());
            if (ex == null) {
                edIns.add(concat(new Object[] {Ulid.next(), systemId, e.get("from").asText(), e.get("to").asText(),
                        e.get("kind").asText(), txt(e, "event", "")}, vals));
            } else if ("manual".equals(ex.extra())) {
                edTouch.add(new Object[] {runId, ex.id()});     // 수동 보정 내용은 유지, 존재 표시만 갱신
            } else {
                edUpd.add(concat(vals, ex.id()));
            }
        }
        jdbc.batchUpdate("UPDATE AS_EDGE SET LAST_RUN_ID = ? WHERE EDGE_ID = ?", edTouch);
        jdbc.batchUpdate("INSERT INTO AS_EDGE (EDGE_ID, SYSTEM_ID, FROM_KEY, TO_KEY, KIND, EVENT, ORD, MODEL_SEQ, COND,"
                + " CRUD, COLUMNS_TXT, SOURCE, CONFIDENCE, EVIDENCE, LAST_RUN_ID)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", edIns);
        jdbc.batchUpdate("UPDATE AS_EDGE SET ORD = ?, MODEL_SEQ = ?, COND = ?, CRUD = ?, COLUMNS_TXT = ?, SOURCE = ?, CONFIDENCE = ?,"
                + " EVIDENCE = ?, LAST_RUN_ID = ? WHERE EDGE_ID = ?", edUpd);

        Diff diff = new Diff(
                added(incomingScreens, screens, prevRunId), removed(incomingScreens, screens, prevRunId),
                added(incomingElements, elements, prevRunId), removed(incomingElements, elements, prevRunId),
                added(incomingEdges.keySet(), edges, prevRunId), removed(incomingEdges.keySet(), edges, prevRunId));
        jdbc.update("UPDATE AS_COLLECT_RUN SET DIFF_JSON = ?, DIFF_DETAIL_JSON = ? WHERE RUN_ID = ?", json(diff),
                json(diffDetail(prevRunId, incomingScreens, screens, incomingElements, elements, incomingEdges.keySet(),
                        edges)), runId);
        return new RunSummary(runId, systemId, seq, collectedAt, uploadedAt, stats, diff);
    }

    private void loadNodes(String systemId, JsonNode model, String runId) {
        Map<String, Existing> nodes = existing("SELECT NODE_KEY, NODE_ID, LAST_RUN_ID, NULL FROM AS_NODE"
                + " WHERE SYSTEM_ID = ?", systemId);
        Map<String, Object[]> rows = new LinkedHashMap<>();   // key → [type, name, lang, file, ls, le, snippet, common, meta]
        for (JsonNode c : model.path("codeUnits")) {
            rows.put(c.get("key").asText(), new Object[] {"FN", c.get("name").asText(), c.get("lang").asText(),
                    c.get("file").asText(), intOrNull(c, "lineStart"), intOrNull(c, "lineEnd"),
                    txt(c, "snippet", null), c.path("common").asBoolean(false), null});
        }
        for (JsonNode a : model.path("apis")) {
            ObjectNode meta = om.createObjectNode();
            meta.put("method", a.get("method").asText());
            meta.put("path", a.get("path").asText());
            if (a.hasNonNull("serviceId")) {
                meta.put("serviceId", a.get("serviceId").asText());
            }
            if (a.has("observedParams")) {
                meta.set("observedParams", a.get("observedParams"));
            }
            rows.put(a.get("key").asText(), new Object[] {"API",
                    a.get("method").asText().toUpperCase() + " " + a.get("path").asText(), null, null, null, null,
                    null, false, json(meta)});
        }
        for (JsonNode s : model.path("sqlStatements")) {
            String key = s.get("key").asText();
            String type = key.startsWith("sp:") ? "SP" : "SQL";
            ObjectNode meta = om.createObjectNode();
            meta.put("kind", s.get("kind").asText());
            if (s.hasNonNull("namespace")) {
                meta.put("namespace", s.get("namespace").asText());   // 스키마 v0.2
            }
            String lang = "SP".equals(type) ? "plsql" : "sql";
            rows.put(key, new Object[] {type, key.substring(key.indexOf(':') + 1), lang, txt(s, "file", null),
                    intOrNull(s, "line"), null, txt(s, "text", null), false, json(meta)});
        }
        List<JsonNode> tables = new ArrayList<>();
        for (JsonNode t : model.path("tables")) {
            tables.add(t);
            ObjectNode meta = om.createObjectNode();
            meta.put("schema", t.get("schema").asText());
            rows.put(t.get("key").asText(), new Object[] {"TABLE", t.get("name").asText(), null, null, null, null,
                    null, false, json(meta)});
        }

        Map<String, String> idByKey = new HashMap<>();
        List<Object[]> ins = new ArrayList<>();
        List<Object[]> upd = new ArrayList<>();
        for (var en : rows.entrySet()) {
            Existing ex = nodes.get(en.getKey());
            Object[] vals = concat(en.getValue(), runId);
            if (ex == null) {
                String id = Ulid.next();
                idByKey.put(en.getKey(), id);
                ins.add(concat(new Object[] {id, systemId, en.getKey()}, vals));
            } else {
                idByKey.put(en.getKey(), ex.id());
                upd.add(concat(vals, ex.id()));
            }
        }
        jdbc.batchUpdate("INSERT INTO AS_NODE (NODE_ID, SYSTEM_ID, NODE_KEY, NODE_TYPE, NAME, LANG, FILE_PATH,"
                + " LINE_START, LINE_END, SNIPPET, COMMON, META_JSON, LAST_RUN_ID)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", ins);
        jdbc.batchUpdate("UPDATE AS_NODE SET NODE_TYPE = ?, NAME = ?, LANG = ?, FILE_PATH = ?, LINE_START = ?,"
                + " LINE_END = ?, SNIPPET = ?, COMMON = ?, META_JSON = ?, LAST_RUN_ID = ? WHERE NODE_ID = ?", upd);

        // 테이블 상세·컬럼: 회차마다 교체
        for (JsonNode t : tables) {
            String id = idByKey.get(t.get("key").asText());
            // 사용자 보정값(주제영역·수동 코멘트)은 회차가 바뀌어도 유지
            String[] kept = jdbc.query("SELECT SUBJECT_AREA, COMMENT_MANUAL FROM AS_TABLE WHERE TABLE_ID = ?",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, id).stream().findFirst()
                    .orElse(new String[] {null, null});
            jdbc.update("DELETE FROM AS_COLUMN WHERE TABLE_ID = ?", id);
            jdbc.update("DELETE FROM AS_TABLE WHERE TABLE_ID = ?", id);
            jdbc.update("INSERT INTO AS_TABLE (TABLE_ID, SYSTEM_ID, SCHEMA_NM, TABLE_NM, COMMENT_TXT, KIND, ROW_COUNT,"
                            + " INDEXES_JSON, SUBJECT_AREA, COMMENT_MANUAL) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    id, systemId, t.get("schema").asText(), t.get("name").asText(), txt(t, "comment", null),
                    txt(t, "kind", "table"), t.hasNonNull("rowCount") ? t.get("rowCount").asLong() : null,
                    t.has("indexes") ? json(t.get("indexes")) : null, kept[0], kept[1]);
            List<Object[]> cols = new ArrayList<>();
            int ord = 0;
            Set<String> seen = new HashSet<>();
            for (JsonNode c : t.path("columns")) {
                if (!seen.add(c.get("name").asText())) {
                    continue;
                }
                cols.add(new Object[] {id, c.get("name").asText(), ++ord, c.get("dataType").asText(),
                        c.has("nullable") ? c.get("nullable").asBoolean() : null, c.path("pk").asBoolean(false),
                        txt(c, "default", null), txt(c, "comment", null)});
            }
            jdbc.batchUpdate("INSERT INTO AS_COLUMN (TABLE_ID, COLUMN_NM, ORD, DATA_TYPE, NULLABLE, PK, DEFAULT_VAL,"
                    + " COMMENT_TXT) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", cols);
        }
    }

    private void loadRelations(String systemId, JsonNode model, String runId) {
        Map<String, Existing> rels = existing("SELECT CHILD_KEY || CHR(1) || PARENT_KEY || CHR(1) || COLUMN_PAIRS,"
                + " REL_ID, LAST_RUN_ID, NULL FROM AS_RELATION WHERE SYSTEM_ID = ?", systemId);
        List<Object[]> ins = new ArrayList<>();
        List<Object[]> upd = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode r : model.path("relations")) {
            String pairs = json(r.get("columns"));
            String key = r.get("from").asText() + SEP + r.get("to").asText() + SEP + pairs;
            if (!seen.add(key)) {
                continue;
            }
            Object[] vals = {r.get("kind").asText(), intOrNull(r, "evidenceCount"),
                    r.hasNonNull("confidence") ? r.get("confidence").asDouble() : null, runId};
            Existing ex = rels.get(key);
            if (ex == null) {
                ins.add(concat(new Object[] {Ulid.next(), systemId, r.get("from").asText(), r.get("to").asText(),
                        pairs}, vals));
            } else {
                upd.add(concat(vals, ex.id()));     // DECISION(확정/기각)은 보존
            }
        }
        jdbc.batchUpdate("INSERT INTO AS_RELATION (REL_ID, SYSTEM_ID, CHILD_KEY, PARENT_KEY, COLUMN_PAIRS, KIND,"
                + " EVIDENCE_CNT, CONFIDENCE, LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", ins);
        jdbc.batchUpdate("UPDATE AS_RELATION SET KIND = CASE WHEN KIND = 'manual' THEN KIND ELSE ? END,"
                + " EVIDENCE_CNT = ?, CONFIDENCE = ?, LAST_RUN_ID = ? WHERE REL_ID = ?", upd);
    }

    // ───────────────────────── 회차 조회 ─────────────────────────

    public List<RunSummary> list(AuthUser user, String systemId) {
        access.forSystem(user, systemId);
        return jdbc.query("SELECT RUN_ID, SYSTEM_ID, SEQ, COLLECTED_AT, UPLOADED_AT, STATS_JSON, DIFF_JSON"
                        + " FROM AS_COLLECT_RUN WHERE SYSTEM_ID = ? ORDER BY SEQ DESC",
                (rs, i) -> new RunSummary(rs.getString("RUN_ID"), rs.getString("SYSTEM_ID"), rs.getInt("SEQ"),
                        Times.read(rs, "COLLECTED_AT"), Times.read(rs, "UPLOADED_AT"),
                        readMap(rs.getString("STATS_JSON")), readDiff(rs.getString("DIFF_JSON"))), systemId);
    }

    public FileStorageService.StoredFile report(AuthUser user, String runId) {
        var row = jdbc.query("SELECT SYSTEM_ID, REPORT_FILE_ID FROM AS_COLLECT_RUN WHERE RUN_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, runId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("수집 회차"));
        access.forSystem(user, row[0]);
        if (row[1] == null) {
            throw ApiException.notFound("실행 리포트");
        }
        audit.log(AuditService.Action.DOWNLOAD, "run-report:" + runId, null, projectId(row[0]));
        return files.load(row[1]);
    }

    // ───────────────────────── 유틸 ─────────────────────────

    private Map<String, Existing> existing(String sql, String systemId) {
        Map<String, Existing> map = new HashMap<>();
        jdbc.query(sql, rs -> {
            map.put(rs.getString(1), new Existing(rs.getString(2), rs.getString(3), rs.getString(4)));
        }, systemId);
        return map;
    }

    private static int added(Set<String> incoming, Map<String, Existing> before, String prevRunId) {
        int n = 0;
        for (String k : incoming) {
            Existing ex = before.get(k);
            if (ex == null || prevRunId == null || !prevRunId.equals(ex.lastRunId())) {
                n++;
            }
        }
        return n;
    }

    private static int removed(Set<String> incoming, Map<String, Existing> before, String prevRunId) {
        if (prevRunId == null) {
            return 0;
        }
        int n = 0;
        for (var en : before.entrySet()) {
            String extra = en.getValue().extra();
            if (prevRunId.equals(en.getValue().lastRunId()) && !incoming.contains(en.getKey())
                    && !"Y".equals(extra) && !"manual".equals(extra)) {
                n++;
            }
        }
        return n;
    }

    /** 회차 diff 상세: 추가·삭제된 화면·요소 키(각 최대 500), 엣지 샘플(최대 50). GET /runs/{id}/diff 에서 라벨을 붙인다. */
    private static Map<String, Object> diffDetail(String prevRunId, Set<String> inScreens, Map<String, Existing> screens,
            Set<String> inElements, Map<String, Existing> elements, Set<String> inEdges, Map<String, Existing> edges) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("prevRunId", prevRunId);
        d.put("screensAdded", keys(addedKeys(inScreens, screens, prevRunId), 500));
        d.put("screensRemoved", keys(removedKeys(inScreens, screens, prevRunId), 500));
        d.put("elementsAdded", keys(addedKeys(inElements, elements, prevRunId), 500));
        d.put("elementsRemoved", keys(removedKeys(inElements, elements, prevRunId), 500));
        List<Map<String, String>> samples = new ArrayList<>();
        for (String k : keys(addedKeys(inEdges, edges, prevRunId), 25)) {
            samples.add(edgeSample(k, "added"));
        }
        for (String k : keys(removedKeys(inEdges, edges, prevRunId), 25)) {
            samples.add(edgeSample(k, "removed"));
        }
        d.put("edgeSamples", samples);
        return d;
    }

    private static Map<String, String> edgeSample(String key, String change) {
        String[] p = key.split(String.valueOf(SEP), -1);
        Map<String, String> m = new LinkedHashMap<>();
        m.put("from", p[0]);
        m.put("to", p.length > 1 ? p[1] : "");
        m.put("kind", p.length > 2 ? p[2] : "");
        m.put("change", change);
        return m;
    }

    private static List<String> keys(List<String> all, int limit) {
        return all.size() > limit ? new ArrayList<>(all.subList(0, limit)) : all;
    }

    private static List<String> addedKeys(Set<String> incoming, Map<String, Existing> before, String prevRunId) {
        List<String> out = new ArrayList<>();
        for (String k : incoming) {
            Existing ex = before.get(k);
            if (ex == null || prevRunId == null || !prevRunId.equals(ex.lastRunId())) {
                out.add(k);
            }
        }
        out.sort(null);
        return out;
    }

    private static List<String> removedKeys(Set<String> incoming, Map<String, Existing> before, String prevRunId) {
        List<String> out = new ArrayList<>();
        if (prevRunId == null) {
            return out;
        }
        for (var en : before.entrySet()) {
            String extra = en.getValue().extra();
            if (prevRunId.equals(en.getValue().lastRunId()) && !incoming.contains(en.getKey())
                    && !"Y".equals(extra) && !"manual".equals(extra)) {
                out.add(en.getKey());
            }
        }
        out.sort(null);
        return out;
    }

    private static String edgeKey(JsonNode e) {
        return e.get("from").asText() + SEP + e.get("to").asText() + SEP + e.get("kind").asText() + SEP
                + txt(e, "event", "");
    }

    private static String txt(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asInt();
    }

    private static String joinArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        arr.forEach(v -> parts.add(v.asText()));
        return String.join(",", parts);
    }

    private static Object[] concat(Object[] a, Object... b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static OffsetDateTime parseTime(String v) {
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(Times.ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(v).atZone(Times.ZONE).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                throw invalid("manifest.collectedAt 형식이 올바르지 않습니다: " + v);
            }
        }
    }

    private static String mime(String path) {
        String p = path.toLowerCase();
        if (p.endsWith(".png")) {
            return "image/png";
        }
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (p.endsWith(".webp")) {
            return "image/webp";
        }
        return "application/octet-stream";
    }

    private String json(Object v) {
        if (v == null || (v instanceof JsonNode n && n.isMissingNode())) {
            return null;
        }
        try {
            return om.writeValueAsString(v);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Number> readMap(String v) {
        if (v == null) {
            return Map.of();
        }
        try {
            return om.readValue(v, new TypeReference<LinkedHashMap<String, Number>>() {
            });
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private Diff readDiff(String v) {
        if (v == null) {
            return new Diff(0, 0, 0, 0, 0, 0);
        }
        try {
            return om.readValue(v, Diff.class);
        } catch (JsonProcessingException e) {
            return new Diff(0, 0, 0, 0, 0, 0);
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.badRequest("PACKAGE_INVALID", message);
    }
}
