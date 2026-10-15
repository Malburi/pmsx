package kr.pmsx.as.edit;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.imageio.ImageIO;
import kr.pmsx.as.AsScope;
import kr.pmsx.as.AsScope.ElementRef;
import kr.pmsx.as.AsScope.ScreenRef;
import kr.pmsx.as.GraphChangedEvent;
import kr.pmsx.as.viewer.SystemStructure;
import kr.pmsx.as.viewer.ViewerDtos.Box;
import kr.pmsx.as.viewer.ViewerDtos.Capture;
import kr.pmsx.as.viewer.ViewerDtos.ElementSummary;
import kr.pmsx.as.viewer.ViewerDtos.EventRef;
import kr.pmsx.as.viewer.ViewerService;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 핫스팟 편집기·연결 보정·수동 캡처·노드 검색 (11 명세 §1). 수동 변경은 재수집해도 보존된다. */
@Service
public class EditService {

    public record ElementRequest(String elementCode, String label, String type, Box box, String binding) {
    }

    public record NodeSummary(String nodeId, String nodeType, String key, String name, String file) {
    }

    public record EdgeFrom(String elementId, String nodeId, String screenId) {
    }

    public record NewNode(String nodeType, String name, String file) {
    }

    public record EdgeTo(String nodeId, NewNode newNode) {
    }

    public record EdgeCreate(EdgeFrom from, EdgeTo to, String kind, String event, String condition, String crud,
                             Integer order) {
    }

    public record EdgePatch(String status, Boolean confirmed, String condition, String crud) {
    }

    public record EdgeInfo(String edgeId, String fromId, String toId, String kind, String event, String source,
                           double confidence, String status, boolean confirmed, String condition, String crud) {
    }

    static final Set<String> ELEMENT_TYPES = Set.of("button", "input", "combo", "check", "radio", "date", "grid",
            "tab", "tree", "popup", "link", "file", "other");
    static final Set<String> EDGE_KINDS = Set.of("event", "calls", "request", "handles", "executes", "accesses",
            "navigates", "binds");
    static final Set<String> NODE_TYPES = Set.of("FN", "API", "SQL", "SP", "TABLE");
    private static final long MAX_CAPTURE = 20L * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final AsScope scope;
    private final ViewerService viewer;
    private final FileStorageService files;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public EditService(JdbcTemplate jdbc, AsScope scope, ViewerService viewer, FileStorageService files,
            AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.viewer = viewer;
        this.files = files;
        this.audit = audit;
        this.events = events;
    }

    // ───────────────────────── 요소 ─────────────────────────

    @Transactional
    public ElementSummary createElement(AuthUser user, String screenId, ElementRequest req) {
        ScreenRef s = scope.screen(screenId);
        scope.requireEdit(user, s.systemId(), screenId, ProjectAccess.ASIS_EDIT);
        String code = req == null || req.elementCode() == null ? "" : req.elementCode().trim();
        if (code.isEmpty() || code.length() > 200) {
            throw AsScope.validation("elementCode", "요소 ID를 200자 이내로 입력해주세요.");
        }
        validateType(req.type());
        validateBox(req.box(), true);
        String key = "el:" + s.screenKey().substring(s.screenKey().indexOf(':') + 1) + "#" + code;
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM AS_ELEMENT WHERE SYSTEM_ID = ? AND ELEMENT_KEY = ?",
                Integer.class, s.systemId(), key);
        if (dup != null && dup > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "같은 요소 ID가 이 화면에 이미 있습니다.",
                    Map.of("elementCode", "중복된 요소 ID입니다."));
        }
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(SEQ_NO), 0) FROM AS_ELEMENT WHERE SCREEN_ID = ?",
                Integer.class, screenId);
        String id = Ulid.next();
        Box b = req.box();
        jdbc.update("""
                INSERT INTO AS_ELEMENT (ELEMENT_ID, SYSTEM_ID, SCREEN_ID, ELEMENT_KEY, SEQ_NO, ELEMENT_CODE, LABEL, TYPE,
                    BOX_X, BOX_Y, BOX_W, BOX_H, BINDING, MATCHED, MANUAL_YN, ORIGIN, LAST_RUN_ID, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'full', 'Y', 'manual', ?, ?, ?)""",
                id, s.systemId(), screenId, key, (max == null ? 0 : max) + 1, code, blankToNull(req.label()),
                req.type(), b.x(), b.y(), b.w(), b.h(), blankToNull(req.binding()), s.lastRunId(), Times.now(),
                user.userId());
        changed(s.systemId(), Action.CREATE, "element:" + key, null);
        return summary(id);
    }

    @Transactional
    public ElementSummary updateElement(AuthUser user, String elementId, ElementRequest req) {
        ElementRef e = scope.element(elementId);
        scope.requireEdit(user, e.systemId(), e.screenId(), ProjectAccess.ASIS_EDIT);
        if (req == null) {
            throw AsScope.validation("body", "변경할 값을 보내주세요.");
        }
        if (req.type() != null) {
            validateType(req.type());
        }
        if (req.box() != null) {
            validateBox(req.box(), false);
        }
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (req.label() != null) {
            sets.add("LABEL = ?");
            args.add(blankToNull(req.label()));
        }
        if (req.type() != null) {
            sets.add("TYPE = ?");
            args.add(req.type());
        }
        if (req.box() != null) {
            sets.add("BOX_X = ?, BOX_Y = ?, BOX_W = ?, BOX_H = ?");
            args.addAll(List.of(req.box().x(), req.box().y(), req.box().w(), req.box().h()));
        }
        if (req.binding() != null) {
            sets.add("BINDING = ?");
            args.add(blankToNull(req.binding()));
        }
        sets.add("MANUAL_YN = 'Y', UPDATED_AT = ?, UPDATED_BY = ?");
        args.add(Times.now());
        args.add(user.userId());
        args.add(elementId);
        jdbc.update("UPDATE AS_ELEMENT SET " + String.join(", ", sets) + " WHERE ELEMENT_ID = ?", args.toArray());
        changed(e.systemId(), Action.UPDATE, "element:" + e.elementKey(), null);
        return summary(elementId);
    }

    /** 수동 요소는 삭제, 수집 요소는 숨김(재수집해도 유지). */
    @Transactional
    public void deleteElement(AuthUser user, String elementId) {
        ElementRef e = scope.element(elementId);
        scope.requireEdit(user, e.systemId(), e.screenId(), ProjectAccess.ASIS_EDIT);
        String origin = jdbc.queryForObject("SELECT ORIGIN FROM AS_ELEMENT WHERE ELEMENT_ID = ?", String.class,
                elementId);
        boolean manualOnly = "manual".equals(origin);
        if (manualOnly) {
            jdbc.update("DELETE FROM AS_EDGE WHERE SYSTEM_ID = ? AND FROM_KEY = ? AND SOURCE = 'manual'", e.systemId(),
                    e.elementKey());
            jdbc.update("DELETE FROM AS_NOTE WHERE TARGET_TYPE = 'ELEMENT' AND TARGET_ID = ?", elementId);
            jdbc.update("DELETE FROM AS_ELEMENT WHERE ELEMENT_ID = ?", elementId);
        } else {
            jdbc.update("UPDATE AS_ELEMENT SET HIDDEN = TRUE, UPDATED_AT = ?, UPDATED_BY = ? WHERE ELEMENT_ID = ?",
                    Times.now(), user.userId(), elementId);
        }
        changed(e.systemId(), Action.DELETE, "element:" + e.elementKey(), manualOnly ? "삭제" : "숨김");
    }

    @Transactional
    public ElementSummary restoreElement(AuthUser user, String elementId) {
        ElementRef e = scope.element(elementId);
        scope.requireEdit(user, e.systemId(), e.screenId(), ProjectAccess.ASIS_EDIT);
        jdbc.update("UPDATE AS_ELEMENT SET HIDDEN = FALSE, UPDATED_AT = ?, UPDATED_BY = ? WHERE ELEMENT_ID = ?",
                Times.now(), user.userId(), elementId);
        changed(e.systemId(), Action.UPDATE, "element:" + e.elementKey(), "숨김 해제");
        return summary(elementId);
    }

    private ElementSummary summary(String elementId) {
        List<EventRef> events = new ArrayList<>();
        jdbc.query("""
                SELECT D.EVENT, D.TO_KEY, N.NAME, N.NODE_TYPE FROM AS_ELEMENT E
                  JOIN AS_EDGE D ON D.SYSTEM_ID = E.SYSTEM_ID AND D.FROM_KEY = E.ELEMENT_KEY
                  LEFT JOIN AS_NODE N ON N.SYSTEM_ID = D.SYSTEM_ID AND N.NODE_KEY = D.TO_KEY
                 WHERE E.ELEMENT_ID = ? AND D.KIND = 'event' AND D.STATUS = 'active'
                 ORDER BY D.ORD, D.MODEL_SEQ, D.TO_KEY""", rs -> {
                    String name = rs.getString(3);
                    String handler = name == null ? rs.getString(2)
                            : ("FN".equals(rs.getString(4)) && !name.contains("(") ? name + "()" : name);
                    String ev = rs.getString(1);
                    events.add(new EventRef(ev == null || ev.isBlank() ? "click" : ev, handler));
                }, elementId);
        return jdbc.query("""
                SELECT ELEMENT_ID, SEQ_NO, ELEMENT_CODE, LABEL, TYPE, BOX_X, BOX_Y, BOX_W, BOX_H, BINDING, MATCHED,
                       DANGER, MANUAL_YN, HIDDEN FROM AS_ELEMENT WHERE ELEMENT_ID = ?""",
                (rs, i) -> ViewerService.element(rs, events), elementId).get(0);
    }

    // ───────────────────────── 수동 캡처 ─────────────────────────

    @Transactional
    public Capture uploadCapture(AuthUser user, String screenId, MultipartFile file, Integer width, Integer height) {
        ScreenRef s = scope.screen(screenId);
        scope.requireEdit(user, s.systemId(), screenId, ProjectAccess.ASIS_EDIT);
        if (file == null || file.isEmpty()) {
            throw AsScope.validation("file", "캡처 이미지를 선택해주세요.");
        }
        if (file.getSize() > MAX_CAPTURE) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "캡처 이미지는 20MB 이하만 올릴 수 있습니다.");
        }
        String name = file.getOriginalFilename() == null ? "capture.png" : file.getOriginalFilename();
        String ext = FileStorageService.extensionOf(name).toLowerCase(Locale.ROOT).replace(".", "");
        String mime = switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_TYPE_NOT_ALLOWED",
                    "캡처는 PNG·JPG 이미지만 올릴 수 있습니다.");
        };
        byte[] bytes;
        BufferedImage img;
        try {
            bytes = file.getBytes();
            img = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw AsScope.validation("file", "이미지를 읽을 수 없습니다.");
        }
        if (img == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "FILE_TYPE_NOT_ALLOWED", "이미지 파일이 아닙니다.");
        }
        int w = width != null && width > 0 ? width : img.getWidth();
        int h = height != null && height > 0 ? height : img.getHeight();
        double scale = (double) img.getWidth() / w;
        String fileId = files.save(bytes, name, mime, user.userId());
        String captureId = Ulid.next();
        jdbc.update("""
                INSERT INTO AS_CAPTURE (CAPTURE_ID, SCREEN_ID, RUN_ID, FILE_ID, IDX, WIDTH, HEIGHT, STATE, MASKED,
                    COLLECTED_AT, SCALE, MANUAL, UPLOADED_BY)
                VALUES (?, ?, NULL, ?, 0, ?, ?, 'manual', FALSE, ?, ?, TRUE, ?)""",
                captureId, screenId, fileId, w, h, Times.now(), scale, user.userId());
        audit.log(Action.UPLOAD, "capture:" + captureId, s.code() + " 수동 캡처", scope.projectId(s.systemId()));
        return viewer.latestCapture(screenId);
    }

    // ───────────────────────── 노드 검색 ─────────────────────────

    public PageDto<NodeSummary> nodes(AuthUser user, String systemId, String q, String type, Integer page,
            Integer size) {
        scope.read(user, systemId);
        if (type != null && !type.isBlank() && !NODE_TYPES.contains(type) && !"SCREEN".equals(type)) {
            throw AsScope.validation("type", "FN, API, SQL, SP, TABLE, SCREEN 중 하나여야 합니다.");
        }
        int[] ps = PageDto.normalize(page, size);
        String latest = SystemStructure.latestRunId(jdbc, systemId);
        String term = q == null ? "" : q.trim().toLowerCase();
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String nodeRun = latest == null ? "" : " AND (MANUAL_YN = 'Y' OR LAST_RUN_ID = ? OR LAST_RUN_ID IS NULL)";
        String screenRun = latest == null ? "" : " AND LAST_RUN_ID = ?";
        String union = "SELECT NODE_ID AS NID, NODE_TYPE AS NTYPE, NODE_KEY AS NKEY, NAME AS NNAME, FILE_PATH AS NFILE"
                + " FROM AS_NODE WHERE SYSTEM_ID = ?" + nodeRun
                + " UNION ALL SELECT SCREEN_ID, 'SCREEN', SCREEN_KEY, NAME || ' (' || SCREEN_CODE || ')', SOURCE_FILE"
                + " FROM AS_SCREEN WHERE SYSTEM_ID = ?" + screenRun;
        StringBuilder where = new StringBuilder(
                " WHERE (LOWER(NNAME) LIKE ? ESCAPE '\\' OR LOWER(NKEY) LIKE ? ESCAPE '\\')");
        List<Object> args = new ArrayList<>();
        args.add(systemId);
        if (latest != null) {
            args.add(latest);
        }
        args.add(systemId);
        if (latest != null) {
            args.add(latest);
        }
        args.add(like);
        args.add(like);
        if (type != null && !type.isBlank()) {
            where.append(" AND NTYPE = ?");
            args.add(type);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + union + ") X" + where, Long.class,
                args.toArray());
        args.add(ps[1]);
        args.add((long) ps[0] * ps[1]);
        List<NodeSummary> content = jdbc.query("SELECT NID, NTYPE, NKEY, NNAME, NFILE FROM (" + union + ") X" + where
                        + " ORDER BY NTYPE, NNAME, NKEY LIMIT ? OFFSET ?",
                (rs, i) -> new NodeSummary(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), args.toArray());
        return new PageDto<>(content, ps[0], ps[1], total == null ? 0 : total);
    }

    // ───────────────────────── 엣지 ─────────────────────────

    private record Endpoint(String key, String id, String screenId) {
    }

    @Transactional
    public EdgeInfo createEdge(AuthUser user, EdgeCreate req) {
        if (req == null || req.from() == null || req.to() == null) {
            throw AsScope.validation("from", "연결의 출발·도착을 지정해주세요.");
        }
        String kind = req.kind();
        if (kind == null || !EDGE_KINDS.contains(kind)) {
            throw AsScope.validation("kind", "연결 종류가 올바르지 않습니다.");
        }
        String systemId;
        Endpoint from;
        EdgeFrom f = req.from();
        int given = (f.elementId() != null ? 1 : 0) + (f.nodeId() != null ? 1 : 0) + (f.screenId() != null ? 1 : 0);
        if (given != 1) {
            throw AsScope.validation("from", "elementId, nodeId, screenId 중 하나만 지정해주세요.");
        }
        if (f.elementId() != null) {
            ElementRef e = scope.element(f.elementId());
            systemId = e.systemId();
            from = new Endpoint(e.elementKey(), e.elementId(), e.screenId());
        } else if (f.screenId() != null) {
            ScreenRef s = scope.screen(f.screenId());
            systemId = s.systemId();
            from = new Endpoint(s.screenKey(), s.screenId(), s.screenId());
        } else {
            var n = scope.node(f.nodeId());
            systemId = n.systemId();
            from = new Endpoint(n.nodeKey(), n.nodeId(), null);
        }
        scope.requireEdit(user, systemId, from.screenId(), ProjectAccess.ASIS_EDIT);
        Endpoint to = resolveTo(user, systemId, req.to());
        String event = "event".equals(kind) ? (req.event() == null || req.event().isBlank() ? "click" : req.event())
                : (req.event() == null ? "" : req.event());
        String crud = normalizeCrud(kind, req.crud());

        var existing = jdbc.query("SELECT EDGE_ID, STATUS FROM AS_EDGE WHERE SYSTEM_ID = ? AND FROM_KEY = ?"
                        + " AND TO_KEY = ? AND KIND = ? AND COALESCE(EVENT, '~') = COALESCE(?, '~')",   // Oracle: ''=NULL
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, systemId, from.key(), to.key(), kind,
                event);
        String edgeId;
        if (!existing.isEmpty()) {
            if ("active".equals(existing.get(0)[1])) {
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "같은 연결이 이미 있습니다.");
            }
            edgeId = existing.get(0)[0];     // 기각된 연결을 다시 살림
            jdbc.update("UPDATE AS_EDGE SET STATUS = 'active', CONFIRMED = TRUE, EDITED = TRUE, COND = ?, CRUD = ?,"
                    + " ORD = COALESCE(?, ORD), UPDATED_AT = ?, UPDATED_BY = ? WHERE EDGE_ID = ?",
                    blankToNull(req.condition()), crud, req.order(), Times.now(), user.userId(), edgeId);
        } else {
            edgeId = Ulid.next();
            jdbc.update("""
                    INSERT INTO AS_EDGE (EDGE_ID, SYSTEM_ID, FROM_KEY, TO_KEY, KIND, EVENT, ORD, COND, CRUD, SOURCE,
                        CONFIDENCE, EVIDENCE, STATUS, CONFIRMED, EDITED, UPDATED_AT, UPDATED_BY)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'manual', 1.0, ?, 'active', TRUE, FALSE, ?, ?)""",
                    edgeId, systemId, from.key(), to.key(), kind, event, req.order(), blankToNull(req.condition()),
                    crud, "수동 추가: " + user.name(), Times.now(), user.userId());
        }
        changed(systemId, Action.CREATE, "edge:" + from.key() + " → " + to.key(), kind);
        return edge(edgeId);
    }

    private Endpoint resolveTo(AuthUser user, String systemId, EdgeTo to) {
        boolean hasId = to.nodeId() != null && !to.nodeId().isBlank();
        if (hasId == (to.newNode() != null)) {
            throw AsScope.validation("to", "nodeId 또는 newNode 중 하나만 지정해주세요.");
        }
        if (hasId) {
            var node = jdbc.query("SELECT NODE_KEY FROM AS_NODE WHERE NODE_ID = ? AND SYSTEM_ID = ?",
                    (rs, i) -> rs.getString(1), to.nodeId(), systemId);
            if (!node.isEmpty()) {
                return new Endpoint(node.get(0), to.nodeId(), null);
            }
            var screen = jdbc.query("SELECT SCREEN_KEY FROM AS_SCREEN WHERE SCREEN_ID = ? AND SYSTEM_ID = ?",
                    (rs, i) -> rs.getString(1), to.nodeId(), systemId);
            if (!screen.isEmpty()) {
                return new Endpoint(screen.get(0), to.nodeId(), to.nodeId());
            }
            throw ApiException.notFound("연결 대상");
        }
        NewNode n = to.newNode();
        String type = n.nodeType();
        String name = n.name() == null ? "" : n.name().trim();
        if (type == null || !NODE_TYPES.contains(type)) {
            throw AsScope.validation("to.newNode.nodeType", "FN, API, SQL, SP, TABLE 중 하나여야 합니다.");
        }
        if (name.isEmpty() || name.length() > 500) {
            throw AsScope.validation("to.newNode.name", "이름을 500자 이내로 입력해주세요.");
        }
        String key = switch (type) {
            case "API" -> "api:" + name;
            case "SQL" -> "sql:" + name;
            case "SP" -> "sp:" + name;
            case "TABLE" -> jdbc.query("SELECT NODE_KEY FROM AS_NODE WHERE SYSTEM_ID = ? AND NODE_TYPE = 'TABLE'"
                    + " AND UPPER(NAME) = UPPER(?) ORDER BY NODE_KEY", (rs, i) -> rs.getString(1), systemId, name)
                    .stream().findFirst().orElse("tbl:manual." + name);
            default -> "fn:manual:" + Ulid.next();
        };
        String existingId = scope.idOfKey(systemId, key);
        if (existingId != null) {
            return new Endpoint(key, existingId, null);      // 같은 API·SQL·테이블이 있으면 재사용
        }
        String id = Ulid.next();
        String file = blankToNull(n.file());
        String lang = "FN".equals(type) && file != null
                ? FileStorageService.extensionOf(file).toLowerCase(Locale.ROOT).replace(".", "")
                : ("SP".equals(type) ? "plsql" : ("SQL".equals(type) ? "sql" : null));
        String meta = null;
        if ("API".equals(type)) {
            String[] p = name.split("\\s+", 2);
            meta = p.length == 2 ? "{\"method\":\"" + jsonEsc(p[0]) + "\",\"path\":\"" + jsonEsc(p[1]) + "\"}" : null;
        }
        jdbc.update("""
                INSERT INTO AS_NODE (NODE_ID, SYSTEM_ID, NODE_KEY, NODE_TYPE, NAME, LANG, FILE_PATH, COMMON, META_JSON,
                    MANUAL_YN) VALUES (?, ?, ?, ?, ?, ?, ?, FALSE, ?, 'Y')""",
                id, systemId, key, type, name, lang == null || lang.isEmpty() ? null : lang, file, meta);
        if ("TABLE".equals(type)) {
            jdbc.update("INSERT INTO AS_TABLE (TABLE_ID, SYSTEM_ID, SCHEMA_NM, TABLE_NM, KIND) VALUES (?, ?, NULL, ?,"
                    + " 'table')", id, systemId, name);
        }
        return new Endpoint(key, id, null);
    }

    @Transactional
    public EdgeInfo patchEdge(AuthUser user, String edgeId, EdgePatch req) {
        var row = edgeRow(edgeId);
        String screenId = screenOfKey(row.systemId, row.fromKey);
        scope.requireEdit(user, row.systemId, screenId, ProjectAccess.ASIS_EDIT);
        if (req == null) {
            throw AsScope.validation("body", "변경할 값을 보내주세요.");
        }
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (req.status() != null) {
            if (!Set.of("active", "rejected").contains(req.status())) {
                throw AsScope.validation("status", "active 또는 rejected 여야 합니다.");
            }
            sets.add("STATUS = ?");
            args.add(req.status());
        }
        if (req.confirmed() != null) {
            sets.add("CONFIRMED = ?");
            args.add(req.confirmed());
        }
        boolean edited = false;
        if (req.condition() != null) {
            sets.add("COND = ?");
            args.add(blankToNull(req.condition()));
            edited = true;
        }
        if (req.crud() != null) {
            sets.add("CRUD = ?");
            args.add(normalizeCrud(row.kind, req.crud()));
            edited = true;
        }
        if (sets.isEmpty()) {
            throw AsScope.validation("body", "변경할 값을 보내주세요.");
        }
        if (edited) {
            sets.add("EDITED = TRUE");
        }
        sets.add("UPDATED_AT = ?, UPDATED_BY = ?");
        args.add(Times.now());
        args.add(user.userId());
        args.add(edgeId);
        jdbc.update("UPDATE AS_EDGE SET " + String.join(", ", sets) + " WHERE EDGE_ID = ?", args.toArray());
        changed(row.systemId, Action.UPDATE, "edge:" + row.fromKey + " → " + row.toKey, sets.toString());
        return edge(edgeId);
    }

    @Transactional
    public void deleteEdge(AuthUser user, String edgeId) {
        var row = edgeRow(edgeId);
        scope.requireEdit(user, row.systemId, screenOfKey(row.systemId, row.fromKey), ProjectAccess.ASIS_EDIT);
        if (!"manual".equals(row.source)) {
            throw AsScope.validation("edgeId", "수집된 연결은 삭제할 수 없습니다. 기각(status=rejected)으로 처리해주세요.");
        }
        jdbc.update("DELETE FROM AS_EDGE WHERE EDGE_ID = ?", edgeId);
        changed(row.systemId, Action.DELETE, "edge:" + row.fromKey + " → " + row.toKey, null);
    }

    private record EdgeRowLite(String systemId, String fromKey, String toKey, String kind, String source) {
    }

    private EdgeRowLite edgeRow(String edgeId) {
        return jdbc.query("SELECT SYSTEM_ID, FROM_KEY, TO_KEY, KIND, SOURCE FROM AS_EDGE WHERE EDGE_ID = ?",
                (rs, i) -> new EdgeRowLite(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)), edgeId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("연결"));
    }

    private String screenOfKey(String systemId, String key) {
        if (key.startsWith("el:")) {
            return jdbc.query("SELECT SCREEN_ID FROM AS_ELEMENT WHERE SYSTEM_ID = ? AND ELEMENT_KEY = ?",
                    (rs, i) -> rs.getString(1), systemId, key).stream().findFirst().orElse(null);
        }
        if (key.startsWith("scr:")) {
            return scope.idOfKey(systemId, key);
        }
        return null;
    }

    public EdgeInfo edge(String edgeId) {
        return jdbc.query("""
                SELECT EDGE_ID, SYSTEM_ID, FROM_KEY, TO_KEY, KIND, EVENT, SOURCE, CONFIDENCE, STATUS, CONFIRMED, COND,
                       CRUD FROM AS_EDGE WHERE EDGE_ID = ?""",
                (rs, i) -> new EdgeInfo(rs.getString(1), scope.idOfKey(rs.getString(2), rs.getString(3)),
                        scope.idOfKey(rs.getString(2), rs.getString(4)), rs.getString(5),
                        rs.getString(6) == null || rs.getString(6).isEmpty() ? null : rs.getString(6),
                        rs.getString(7), rs.getDouble(8), rs.getString(9), rs.getBoolean(10), rs.getString(11),
                        rs.getString(12)), edgeId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("연결"));
    }

    // ───────────────────────── 공통 ─────────────────────────

    private void changed(String systemId, Action action, String target, String detail) {
        events.publishEvent(new GraphChangedEvent(systemId));
        audit.log(action, target.length() > 480 ? target.substring(0, 480) : target, detail,
                scope.projectId(systemId));
    }

    private static void validateType(String type) {
        if (type == null || !ELEMENT_TYPES.contains(type)) {
            throw AsScope.validation("type", "요소 유형이 올바르지 않습니다.");
        }
    }

    private static void validateBox(Box b, boolean required) {
        if (b == null) {
            if (required) {
                throw AsScope.validation("box", "캡처 위 영역(box)을 지정해주세요.");
            }
            return;
        }
        if (b.x() < 0 || b.y() < 0 || b.w() <= 0 || b.h() <= 0 || b.w() > 20000 || b.h() > 20000) {
            throw AsScope.validation("box", "영역 좌표가 올바르지 않습니다.");
        }
    }

    private static String normalizeCrud(String kind, String crud) {
        if (crud == null || crud.isBlank()) {
            return null;
        }
        if (!"accesses".equals(kind)) {
            throw AsScope.validation("crud", "CRUD 는 테이블 접근(accesses) 연결에만 지정할 수 있습니다.");
        }
        String c = crud.trim().toUpperCase(Locale.ROOT);
        if (!c.matches("[CRUD]{1,4}")) {
            throw AsScope.validation("crud", "C, R, U, D 조합이어야 합니다.");
        }
        StringBuilder sb = new StringBuilder();
        for (char x : new char[] {'C', 'R', 'U', 'D'}) {
            if (c.indexOf(x) >= 0) {
                sb.append(x);
            }
        }
        return sb.toString();
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String jsonEsc(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
