package kr.pmsx.as.analysis;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.as.AsScope;
import kr.pmsx.as.AsScope.ScreenRef;
import kr.pmsx.as.viewer.SystemStructure;
import kr.pmsx.as.viewer.ViewerDtos.UserRef;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 메모·분석 이력·일괄 배정·진척·화면 그리드 (11 명세 §2). */
@Service
public class AnalysisService {

    public record Note(String noteId, String targetType, String targetId, String content, UserRef author,
                       OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record NoteRequest(String targetType, String targetId, String content) {
    }

    public record HistoryRow(OffsetDateTime at, UserRef user, String fromStatus, String toStatus, String comment) {
    }

    public record AssignRequest(List<String> screenIds, String ownerId, String reviewerId) {
    }

    public record OwnerStat(UserRef owner, int total, Map<String, Integer> byStatus) {
    }

    public record MenuStat(String menuId, String name, int total, int done) {
    }

    public record Progress(int total, Map<String, Integer> byStatus, List<OwnerStat> byOwner, List<MenuStat> byMenu,
                           int unmatchedElements, int unconfirmedEdges) {
    }

    public record AnalysisRow(String screenId, String screenCode, String name, List<String> menuPath, String status,
                              UserRef owner, UserRef reviewer, int elementCount, int unmatchedCount,
                              OffsetDateTime updatedAt) {
    }

    static final int NOTE_MAX = 20_000;
    private static final List<String> STATUSES = List.of("AUTO", "WORKING", "REVIEW", "DONE");

    /** 화면별 요소 수·미매칭 수 (최신 회차 요소 + 수동 요소, 숨김 제외). 미매칭 = 정적·동적 매칭 불완전 또는 핸들러 없는 버튼류 */
    static final String ELEMENT_STATS = """
            SELECT E.SCREEN_ID, COUNT(*) AS CNT,
                   SUM(CASE WHEN E.MATCHED <> 'full' OR (E.TYPE IN ('button', 'popup', 'link') AND H.FROM_KEY IS NULL)
                            THEN 1 ELSE 0 END) AS UNMATCHED
              FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID
              LEFT JOIN (SELECT DISTINCT FROM_KEY FROM AS_EDGE
                          WHERE SYSTEM_ID = ? AND KIND = 'event' AND STATUS = 'active') H ON H.FROM_KEY = E.ELEMENT_KEY
             WHERE E.SYSTEM_ID = ? AND E.HIDDEN = FALSE
               AND (E.MANUAL_YN = 'Y' OR E.LAST_RUN_ID = S.LAST_RUN_ID)
             GROUP BY E.SCREEN_ID""";

    private final JdbcTemplate jdbc;
    private final AsScope scope;
    private final AuditService audit;

    public AnalysisService(JdbcTemplate jdbc, AsScope scope, AuditService audit) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.audit = audit;
    }

    // ───────────────────────── 메모 ─────────────────────────

    private record Target(String systemId, String screenId, String key) {
    }

    private Target target(String type, String id) {
        if (type == null || id == null) {
            throw AsScope.validation("targetType", "targetType, targetId 가 필요합니다.");
        }
        return switch (type) {
            case "SCREEN" -> {
                ScreenRef s = scope.screen(id);
                yield new Target(s.systemId(), s.screenId(), s.screenKey());
            }
            case "ELEMENT" -> {
                var e = scope.element(id);
                yield new Target(e.systemId(), e.screenId(), e.elementKey());
            }
            case "NODE" -> {
                var n = scope.node(id);
                yield new Target(n.systemId(), null, n.nodeKey());
            }
            default -> throw AsScope.validation("targetType", "SCREEN, ELEMENT, NODE 중 하나여야 합니다.");
        };
    }

    public List<Note> notes(AuthUser user, String targetType, String targetId) {
        Target t = target(targetType, targetId);
        scope.read(user, t.systemId());
        return jdbc.query("""
                SELECT N.NOTE_ID, N.TARGET_TYPE, N.TARGET_ID, N.CONTENT, N.AUTHOR_ID, U.NAME, N.CREATED_AT, N.UPDATED_AT
                  FROM AS_NOTE N LEFT JOIN CM_USER U ON U.USER_ID = N.AUTHOR_ID
                 WHERE N.TARGET_TYPE = ? AND N.TARGET_ID = ?
                 ORDER BY N.CREATED_AT DESC, N.NOTE_ID DESC""", (rs, i) -> note(rs), targetType, targetId);
    }

    @Transactional
    public Note createNote(AuthUser user, NoteRequest req) {
        if (req == null) {
            throw AsScope.validation("content", "메모 내용을 입력해주세요.");
        }
        Target t = target(req.targetType(), req.targetId());
        scope.requireEdit(user, t.systemId(), null, ProjectAccess.NOTE_WRITE);
        String content = content(req.content());
        String id = Ulid.next();
        OffsetDateTime now = Times.now();
        jdbc.update("INSERT INTO AS_NOTE (NOTE_ID, SYSTEM_ID, TARGET_KEY, TARGET_TYPE, TARGET_ID, CONTENT, AUTHOR_ID,"
                + " CREATED_AT, UPDATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, t.systemId(), t.key(),
                req.targetType(), req.targetId(), content, user.userId(), now, now);
        audit.log(Action.CREATE, "note:" + t.key(), null, scope.projectId(t.systemId()));
        return noteById(id);
    }

    @Transactional
    public Note updateNote(AuthUser user, String noteId, NoteRequest req) {
        String[] n = noteOwner(noteId);
        requireNoteChange(user, n);
        String content = content(req == null ? null : req.content());
        jdbc.update("UPDATE AS_NOTE SET CONTENT = ?, UPDATED_AT = ? WHERE NOTE_ID = ?", content, Times.now(), noteId);
        audit.log(Action.UPDATE, "note:" + n[2], null, scope.projectId(n[0]));
        return noteById(noteId);
    }

    @Transactional
    public void deleteNote(AuthUser user, String noteId) {
        String[] n = noteOwner(noteId);
        requireNoteChange(user, n);
        jdbc.update("DELETE FROM AS_NOTE WHERE NOTE_ID = ?", noteId);
        audit.log(Action.DELETE, "note:" + n[2], null, scope.projectId(n[0]));
    }

    /** 작성자 본인 또는 PM·PL */
    private void requireNoteChange(AuthUser user, String[] n) {
        ProjectAccess.Access a = scope.read(user, n[0]);
        if (!user.userId().equals(n[1]) && !a.has(ProjectAccess.ANALYSIS_FULL)) {
            throw ApiException.forbidden();
        }
    }

    private String[] noteOwner(String noteId) {
        return jdbc.query("SELECT SYSTEM_ID, AUTHOR_ID, TARGET_KEY FROM AS_NOTE WHERE NOTE_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, noteId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("메모"));
    }

    private static String content(String c) {
        if (c == null || c.isBlank()) {
            throw AsScope.validation("content", "메모 내용을 입력해주세요.");
        }
        if (c.length() > NOTE_MAX) {
            throw AsScope.validation("content", "메모는 20,000자 이내로 입력해주세요.");
        }
        return c;
    }

    private Note noteById(String id) {
        return jdbc.query("""
                SELECT N.NOTE_ID, N.TARGET_TYPE, N.TARGET_ID, N.CONTENT, N.AUTHOR_ID, U.NAME, N.CREATED_AT, N.UPDATED_AT
                  FROM AS_NOTE N LEFT JOIN CM_USER U ON U.USER_ID = N.AUTHOR_ID WHERE N.NOTE_ID = ?""",
                (rs, i) -> note(rs), id).get(0);
    }

    private static Note note(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Note(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                new UserRef(rs.getString(5), rs.getString(6)), Times.read(rs, "CREATED_AT"),
                Times.read(rs, "UPDATED_AT"));
    }

    // ───────────────────────── 이력·배정 ─────────────────────────

    public List<HistoryRow> history(AuthUser user, String screenId) {
        ScreenRef s = scope.screen(screenId);
        scope.read(user, s.systemId());
        return jdbc.query("""
                SELECT H.CHANGED_AT, H.USER_ID, U.NAME, H.FROM_STATUS, H.TO_STATUS, H.COMMENT_TXT
                  FROM AS_ANALYSIS_HIST H LEFT JOIN CM_USER U ON U.USER_ID = H.USER_ID
                 WHERE H.SCREEN_ID = ? ORDER BY H.CHANGED_AT DESC, H.HIST_ID DESC""",
                (rs, i) -> new HistoryRow(Times.read(rs, "CHANGED_AT"),
                        rs.getString(2) == null ? null : new UserRef(rs.getString(2), rs.getString(3)),
                        rs.getString(4), rs.getString(5), rs.getString(6)), screenId);
    }

    @Transactional
    public Map<String, Integer> assign(AuthUser user, String systemId, AssignRequest req) {
        ProjectAccess.Access a = scope.read(user, systemId);
        a.require(ProjectAccess.ANALYSIS_FULL);
        if (req == null || req.screenIds() == null || req.screenIds().isEmpty()) {
            throw AsScope.validation("screenIds", "배정할 화면을 선택해주세요.");
        }
        if (req.screenIds().size() > 5000) {
            throw AsScope.validation("screenIds", "한 번에 5,000개까지 배정할 수 있습니다.");
        }
        requireMember(a.projectId(), "ownerId", req.ownerId());
        requireMember(a.projectId(), "reviewerId", req.reviewerId());
        int updated = 0;
        OffsetDateTime now = Times.now();
        for (String screenId : new LinkedHashSet<>(req.screenIds())) {
            var cur = jdbc.query("SELECT A.STATUS, A.OWNER_ID, A.REVIEWER_ID FROM AS_ANALYSIS A JOIN AS_SCREEN S"
                            + " ON S.SCREEN_ID = A.SCREEN_ID WHERE A.SCREEN_ID = ? AND S.SYSTEM_ID = ?",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, screenId, systemId);
            if (cur.isEmpty()) {
                throw AsScope.validation("screenIds", "이 시스템의 화면이 아닙니다: " + screenId);
            }
            String reviewer = req.reviewerId() != null ? req.reviewerId() : cur.get(0)[2];
            jdbc.update("UPDATE AS_ANALYSIS SET OWNER_ID = ?, REVIEWER_ID = ?, UPDATED_AT = ?, UPDATED_BY = ?"
                    + " WHERE SCREEN_ID = ?", req.ownerId(), reviewer, now, user.userId(), screenId);
            jdbc.update("INSERT INTO AS_ANALYSIS_HIST (HIST_ID, SCREEN_ID, CHANGED_AT, USER_ID, FROM_STATUS, TO_STATUS,"
                    + " OWNER_ID, REVIEWER_ID, COMMENT_TXT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)", Ulid.next(), screenId,
                    now, user.userId(), cur.get(0)[0], cur.get(0)[0], req.ownerId(), reviewer, "일괄 배정");
            updated++;
        }
        audit.log(Action.UPDATE, "analysis-assign:" + systemId, updated + "개 화면 → " + req.ownerId(),
                a.projectId());
        return Map.of("updated", updated);
    }

    private void requireMember(String projectId, String field, String userId) {
        if (userId == null) {
            return;
        }
        Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                Integer.class, projectId, userId);
        if (member == null || member == 0) {
            throw AsScope.validation(field, "프로젝트 멤버가 아닙니다.");
        }
    }

    // ───────────────────────── 진척 ─────────────────────────

    private record Row(SystemStructure.ScreenRow screen, String status, UserRef owner, UserRef reviewer,
                       OffsetDateTime updatedAt) {
    }

    private List<Row> rows(String systemId, SystemStructure st) {
        Map<String, Row> byId = new LinkedHashMap<>();
        Map<String, SystemStructure.ScreenRow> screens = new HashMap<>();
        st.screens().forEach(s -> screens.put(s.id(), s));
        jdbc.query("""
                SELECT A.SCREEN_ID, A.STATUS, A.OWNER_ID, O.NAME, A.REVIEWER_ID, R.NAME, A.UPDATED_AT
                  FROM AS_ANALYSIS A JOIN AS_SCREEN S ON S.SCREEN_ID = A.SCREEN_ID
                  LEFT JOIN CM_USER O ON O.USER_ID = A.OWNER_ID
                  LEFT JOIN CM_USER R ON R.USER_ID = A.REVIEWER_ID
                 WHERE S.SYSTEM_ID = ?""", rs -> {
                    SystemStructure.ScreenRow s = screens.get(rs.getString(1));
                    if (s != null) {
                        byId.put(s.id(), new Row(s, rs.getString(2),
                                rs.getString(3) == null ? null : new UserRef(rs.getString(3), rs.getString(4)),
                                rs.getString(5) == null ? null : new UserRef(rs.getString(5), rs.getString(6)),
                                Times.read(rs, "UPDATED_AT")));
                    }
                }, systemId);
        List<Row> out = new ArrayList<>();
        for (SystemStructure.ScreenRow s : st.screens()) {
            Row r = byId.get(s.id());
            out.add(r != null ? r : new Row(s, "AUTO", null, null, null));
        }
        return out;
    }

    /** 요소 통계·미확정 연결 수 캐시 (그래프가 바뀌면 GraphChangedEvent 로 버림). 연결선 100만 건 집계를 매번 하지 않는다 */
    private record Stats(Map<String, int[]> elements, int unconfirmed, String runId) {
    }

    private final Map<String, Stats> statsCache = new java.util.concurrent.ConcurrentHashMap<>();

    @org.springframework.context.event.EventListener
    public void onGraphChanged(kr.pmsx.as.GraphChangedEvent e) {
        statsCache.remove(e.systemId());
    }

    /** 커밋 후에도 한 번 더 비운다 (커밋 전 다른 요청이 옛 값으로 다시 채운 경우 대비) */
    @org.springframework.transaction.event.TransactionalEventListener(fallbackExecution = true)
    public void afterGraphCommit(kr.pmsx.as.GraphChangedEvent e) {
        statsCache.remove(e.systemId());
    }

    private Stats stats(String systemId, String latestRunId) {
        Stats cached = statsCache.get(systemId);
        if (cached != null && java.util.Objects.equals(cached.runId(), latestRunId)) {
            return cached;
        }
        Map<String, int[]> m = new HashMap<>();
        jdbc.query(ELEMENT_STATS, rs -> {
            m.put(rs.getString(1), new int[] {rs.getInt(2), rs.getInt(3)});
        }, systemId, systemId);
        List<Object> args = new ArrayList<>(List.of(systemId));
        String run = "";
        if (latestRunId != null) {
            run = " AND (LAST_RUN_ID = ? OR EDITED = TRUE OR CONFIRMED = TRUE OR LAST_RUN_ID IS NULL)";
            args.add(latestRunId);
        }
        Integer unconfirmed = jdbc.queryForObject("SELECT COUNT(*) FROM AS_EDGE WHERE SYSTEM_ID = ? AND STATUS = 'active'"
                + " AND CONFIDENCE < 0.9 AND CONFIRMED = FALSE AND SOURCE <> 'manual'" + run, Integer.class,
                args.toArray());
        Stats s = new Stats(m, unconfirmed == null ? 0 : unconfirmed, latestRunId);
        statsCache.put(systemId, s);
        return s;
    }

    private Map<String, int[]> elementStats(String systemId) {
        return stats(systemId, SystemStructure.latestRunId(jdbc, systemId)).elements();
    }

    public Progress progress(AuthUser user, String systemId) {
        scope.read(user, systemId);
        SystemStructure st = new SystemStructure(jdbc, systemId);
        List<Row> rows = rows(systemId, st);
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        STATUSES.forEach(s -> byStatus.put(s, 0));
        Map<String, OwnerAcc> owners = new LinkedHashMap<>();
        Map<String, int[]> menus = new LinkedHashMap<>();
        Map<String, String> menuNames = new HashMap<>();
        for (Row r : rows) {
            byStatus.merge(r.status(), 1, Integer::sum);
            String ok = r.owner() == null ? "" : r.owner().userId();
            owners.computeIfAbsent(ok, k -> new OwnerAcc(r.owner())).add(r.status());
            SystemStructure.MenuRow top = st.topMenu(r.screen().key());
            String mid = top == null ? "unlinked" : top.id();
            menuNames.putIfAbsent(mid, top == null ? "메뉴 미연결 화면" : top.name());
            int[] acc = menus.computeIfAbsent(mid, k -> new int[2]);
            acc[0]++;
            if ("DONE".equals(r.status())) {
                acc[1]++;
            }
        }
        int unmatched = 0;
        Set<String> latestIds = new java.util.HashSet<>();
        st.screens().forEach(s -> latestIds.add(s.id()));
        for (var en : stats(systemId, st.latestRunId()).elements().entrySet()) {
            if (latestIds.contains(en.getKey())) {
                unmatched += en.getValue()[1];
            }
        }
        Integer unconfirmed = stats(systemId, st.latestRunId()).unconfirmed();
        List<OwnerStat> byOwner = new ArrayList<>();
        owners.values().forEach(o -> byOwner.add(new OwnerStat(o.owner, o.total, o.byStatus)));
        byOwner.sort((a, b) -> a.owner() == null ? 1 : b.owner() == null ? -1
                : a.owner().name().compareTo(b.owner().name()));
        List<MenuStat> byMenu = new ArrayList<>();
        menus.forEach((id, v) -> byMenu.add(new MenuStat(id, menuNames.get(id), v[0], v[1])));
        return new Progress(rows.size(), byStatus, byOwner, byMenu, unmatched, unconfirmed == null ? 0 : unconfirmed);
    }

    private static final class OwnerAcc {
        final UserRef owner;
        int total;
        final Map<String, Integer> byStatus = new LinkedHashMap<>();

        OwnerAcc(UserRef owner) {
            this.owner = owner;
            STATUSES.forEach(s -> byStatus.put(s, 0));
        }

        void add(String status) {
            total++;
            byStatus.merge(status, 1, Integer::sum);
        }
    }

    public PageDto<AnalysisRow> screens(AuthUser user, String systemId, String status, String ownerId, String menuId,
            String q, Integer page, Integer size) {
        scope.read(user, systemId);
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw AsScope.validation("status", "AUTO, WORKING, REVIEW, DONE 중 하나여야 합니다.");
        }
        SystemStructure st = new SystemStructure(jdbc, systemId);
        Set<String> underMenu = menuId == null || menuId.isBlank() ? null
                : ("unlinked".equals(menuId) ? unlinkedKeys(st) : st.screensUnderMenu(menuId));
        String term = q == null ? "" : q.trim().toLowerCase();
        Map<String, int[]> stats = elementStats(systemId);
        List<AnalysisRow> all = new ArrayList<>();
        for (Row r : rows(systemId, st)) {
            if (status != null && !status.isBlank() && !status.equals(r.status())) {
                continue;
            }
            if (ownerId != null && !ownerId.isBlank()) {
                boolean none = "none".equals(ownerId);
                if (none ? r.owner() != null : (r.owner() == null || !ownerId.equals(r.owner().userId()))) {
                    continue;
                }
            }
            if (underMenu != null && !underMenu.contains(r.screen().key())) {
                continue;
            }
            if (!term.isEmpty() && !r.screen().name().toLowerCase().contains(term)
                    && !r.screen().code().toLowerCase().contains(term)) {
                continue;
            }
            int[] s = stats.getOrDefault(r.screen().id(), new int[2]);
            all.add(new AnalysisRow(r.screen().id(), r.screen().code(), r.screen().name(), st.path(r.screen().key()),
                    r.status(), r.owner(), r.reviewer(), s[0], s[1], r.updatedAt()));
        }
        int[] ps = PageDto.normalize(page, size);
        int from = Math.min(all.size(), ps[0] * ps[1]);
        int to = Math.min(all.size(), from + ps[1]);
        return new PageDto<>(new ArrayList<>(all.subList(from, to)), ps[0], ps[1], all.size());
    }

    private static Set<String> unlinkedKeys(SystemStructure st) {
        Set<String> out = new LinkedHashSet<>();
        for (SystemStructure.ScreenRow s : st.screens()) {
            if (st.topMenu(s.key()) == null) {
                out.add(s.key());
            }
        }
        return out;
    }

    static ApiException forbidden(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", message);
    }
}
