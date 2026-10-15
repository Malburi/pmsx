package kr.pmsx.as.viewer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.as.viewer.ViewerDtos.Analysis;
import kr.pmsx.as.viewer.ViewerDtos.Box;
import kr.pmsx.as.viewer.ViewerDtos.Capture;
import kr.pmsx.as.viewer.ViewerDtos.ElementSummary;
import kr.pmsx.as.viewer.ViewerDtos.EventRef;
import kr.pmsx.as.viewer.ViewerDtos.MenuNode;
import kr.pmsx.as.viewer.ViewerDtos.MenuScreen;
import kr.pmsx.as.viewer.ViewerDtos.ScreenDetail;
import kr.pmsx.as.viewer.ViewerDtos.SearchHit;
import kr.pmsx.as.viewer.SystemStructure.MenuRow;
import kr.pmsx.as.viewer.ViewerDtos.UserRef;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 메뉴 트리·화면 상세·캡처 이미지·검색·분석 상태 (08 명세 §4). */
@Service
public class ViewerService {

    static final Set<String> STATUSES = Set.of("AUTO", "WORKING", "REVIEW", "DONE");
    private static final String UNLINKED_ID = "unlinked";

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final FileStorageService files;

    public ViewerService(JdbcTemplate jdbc, ProjectAccess access, FileStorageService files) {
        this.jdbc = jdbc;
        this.access = access;
        this.files = files;
    }

    // ───────────────────────── 메뉴 트리 ─────────────────────────

    private record ScreenRow(String id, String key, String code, String name, String kind, String status) {
        static ScreenRow of(SystemStructure.ScreenRow s) {
            return s == null ? null : new ScreenRow(s.id(), s.key(), s.code(), s.name(), s.kind(), s.status());
        }
    }

    public List<MenuNode> menuTree(AuthUser user, String systemId) {
        ProjectAccess.Access acc = access.forSystem(user, systemId);
        List<MenuNode> tree = fullMenuTree(systemId);
        List<String> menuScope = partnerMenus(acc, user);
        return menuScope.isEmpty() ? tree : prune(tree, new HashSet<>(menuScope));
    }

    /** PARTNER 의 메뉴 열람 범위 (비어 있으면 제한 없음) */
    private List<String> partnerMenus(ProjectAccess.Access acc, AuthUser user) {
        return "PARTNER".equals(acc.projectRole())
                ? access.partnerScope(acc.projectId(), user.userId(), "MENU_IDS") : List.of();
    }

    /** 허용 메뉴(하위 전체 포함)와 그 상위만 남긴다 */
    private static List<MenuNode> prune(List<MenuNode> nodes, Set<String> allowed) {
        List<MenuNode> out = new ArrayList<>();
        for (MenuNode n : nodes) {
            if (allowed.contains(n.menuId())) {
                out.add(n);
                continue;
            }
            List<MenuNode> kids = prune(n.children(), allowed);
            if (!kids.isEmpty()) {
                out.add(new MenuNode(n.menuId(), n.name(), n.order(), n.screen(), kids));
            }
        }
        return out;
    }

    private static void collectScreens(List<MenuNode> nodes, Set<String> out) {
        for (MenuNode n : nodes) {
            if (n.screen() != null) {
                out.add(n.screen().screenId());
            }
            collectScreens(n.children(), out);
        }
    }

    private List<MenuNode> fullMenuTree(String systemId) {
        SystemStructure st = new SystemStructure(jdbc, systemId);

        // 화면별 팝업 자식
        Map<String, List<ScreenRow>> popupsByCaller = new LinkedHashMap<>();
        st.callerByPopupKey.forEach((popup, caller) -> popupsByCaller.computeIfAbsent(caller,
                k -> new ArrayList<>()).add(ScreenRow.of(st.screens.get(popup))));

        Map<String, List<MenuRow>> childrenByParent = new LinkedHashMap<>();
        List<MenuRow> roots = new ArrayList<>();
        for (MenuRow m : st.menus.values()) {
            if (m.parentKey() == null || !st.menus.containsKey(m.parentKey())) {
                roots.add(m);
            } else {
                childrenByParent.computeIfAbsent(m.parentKey(), k -> new ArrayList<>()).add(m);
            }
        }
        List<MenuNode> out = new ArrayList<>();
        for (MenuRow r : roots) {
            out.add(menuNode(st, r, childrenByParent, popupsByCaller, new HashSet<>()));
        }
        List<MenuNode> unlinked = new ArrayList<>();
        int i = 0;
        for (SystemStructure.ScreenRow s : st.screens.values()) {
            if (st.isUnlinked(s)) {
                unlinked.add(screenNode(ScreenRow.of(s), i++, popupsByCaller, new HashSet<>()));
            }
        }
        if (!unlinked.isEmpty()) {
            out.add(new MenuNode(UNLINKED_ID, "메뉴 미연결 화면", 9999, null, unlinked));
        }
        return out;
    }

    private MenuNode menuNode(SystemStructure st, MenuRow m, Map<String, List<MenuRow>> childrenByParent,
            Map<String, List<ScreenRow>> popupsByCaller, Set<String> guard) {
        List<MenuNode> children = new ArrayList<>();
        if (guard.add(m.key())) {
            for (MenuRow c : childrenByParent.getOrDefault(m.key(), List.of())) {
                children.add(menuNode(st, c, childrenByParent, popupsByCaller, guard));
            }
        }
        MenuScreen screen = null;
        ScreenRow s = m.screenKey() == null ? null : ScreenRow.of(st.screens.get(m.screenKey()));
        if (s != null) {
            screen = new MenuScreen(s.id(), s.code(), s.kind(), s.status());
            children.addAll(popupChildren(s, popupsByCaller, new HashSet<>()));
        }
        return new MenuNode(m.id(), m.name(), m.order(), screen, children);
    }

    private MenuNode screenNode(ScreenRow s, int order, Map<String, List<ScreenRow>> popupsByCaller,
            Set<String> guard) {
        return new MenuNode("screen-" + s.id(), s.name(), order, new MenuScreen(s.id(), s.code(), s.kind(), s.status()),
                popupChildren(s, popupsByCaller, guard));
    }

    private List<MenuNode> popupChildren(ScreenRow s, Map<String, List<ScreenRow>> popupsByCaller,
            Set<String> guard) {
        List<MenuNode> out = new ArrayList<>();
        if (!guard.add(s.key())) {
            return out;
        }
        int i = 0;
        List<ScreenRow> popups = new ArrayList<>(popupsByCaller.getOrDefault(s.key(), List.of()));
        popups.sort(Comparator.comparing(ScreenRow::code));
        for (ScreenRow p : popups) {
            out.add(screenNode(p, i++, popupsByCaller, guard));
        }
        return out;
    }

    // ───────────────────────── 화면 상세 ─────────────────────────

    public ScreenDetail screen(AuthUser user, String screenId) {
        var s = jdbc.query("SELECT SYSTEM_ID, SCREEN_KEY, SCREEN_CODE, NAME, KIND, UI_TECH, SOURCE_FILE"
                        + " FROM AS_SCREEN WHERE SCREEN_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)}, screenId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("화면"));
        String systemId = s[0];
        ProjectAccess.Access acc = access.forSystem(user, systemId);
        List<String> menuScope = partnerMenus(acc, user);
        if (!menuScope.isEmpty()) {
            Set<String> visible = new HashSet<>();
            collectScreens(prune(fullMenuTree(systemId), new HashSet<>(menuScope)), visible);
            if (!visible.contains(screenId)) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "열람 범위 밖의 화면입니다.");
            }
        }
        SystemStructure st = new SystemStructure(jdbc, systemId);
        List<String> menuPath = st.screens.containsKey(s[1]) ? st.path(s[1]) : List.of(st.systemName, s[3]);
        Capture capture = latestCapture(screenId);

        // 요소별 이벤트 → 핸들러
        Map<String, List<EventRef>> events = new HashMap<>();
        jdbc.query("""
                SELECT E.ELEMENT_ID, D.EVENT, D.TO_KEY, N.NAME, N.NODE_TYPE
                  FROM AS_ELEMENT E
                  JOIN AS_EDGE D ON D.SYSTEM_ID = E.SYSTEM_ID AND D.FROM_KEY = E.ELEMENT_KEY
                  LEFT JOIN AS_NODE N ON N.SYSTEM_ID = D.SYSTEM_ID AND N.NODE_KEY = D.TO_KEY
                 WHERE E.SCREEN_ID = ? AND D.KIND = 'event' AND D.STATUS = 'active'
                   AND (D.LAST_RUN_ID = (SELECT S.LAST_RUN_ID FROM AS_SCREEN S WHERE S.SCREEN_ID = E.SCREEN_ID)
                        OR D.SOURCE = 'manual' OR D.EDITED = TRUE OR D.CONFIRMED = TRUE OR D.LAST_RUN_ID IS NULL)
                 ORDER BY E.SEQ_NO, CASE WHEN D.ORD IS NULL THEN 1 ELSE 0 END, D.ORD, D.MODEL_SEQ, D.TO_KEY""", rs -> {
                    String name = rs.getString(4);
                    String handler = name == null ? rs.getString(3)
                            : ("FN".equals(rs.getString(5)) && !name.contains("(") ? name + "()" : name);
                    String ev = rs.getString(2);
                    events.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                            .add(new EventRef(ev == null || ev.isBlank() ? "click" : ev, handler));
                }, screenId);

        List<ElementSummary> elements = jdbc.query("""
                SELECT E.ELEMENT_ID, E.SEQ_NO, E.ELEMENT_CODE, E.LABEL, E.TYPE, E.BOX_X, E.BOX_Y, E.BOX_W, E.BOX_H,
                       E.BINDING, E.MATCHED, E.DANGER, E.MANUAL_YN, E.HIDDEN
                  FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID
                 WHERE E.SCREEN_ID = ?
                   AND (E.MANUAL_YN = 'Y' OR E.LAST_RUN_ID = S.LAST_RUN_ID OR S.LAST_RUN_ID IS NULL)
                 ORDER BY E.SEQ_NO""",
                (rs, i) -> element(rs, events.getOrDefault(rs.getString(1), List.of())), screenId);

        return new ScreenDetail(screenId, s[2], s[3], s[4], s[5], s[6], menuPath, analysis(screenId), capture,
                elements);
    }

    private Analysis analysis(String screenId) {
        return jdbc.query("""
                SELECT A.STATUS, A.OWNER_ID, O.NAME AS OWNER_NAME, A.REVIEWER_ID, R.NAME AS REVIEWER_NAME, A.UPDATED_AT
                  FROM AS_ANALYSIS A
                  LEFT JOIN CM_USER O ON O.USER_ID = A.OWNER_ID
                  LEFT JOIN CM_USER R ON R.USER_ID = A.REVIEWER_ID
                 WHERE A.SCREEN_ID = ?""",
                (rs, i) -> new Analysis(rs.getString(1),
                        rs.getString(2) == null ? null : new UserRef(rs.getString(2), rs.getString(3)),
                        rs.getString(4) == null ? null : new UserRef(rs.getString(4), rs.getString(5)),
                        Times.read(rs, "UPDATED_AT")), screenId).stream().findFirst()
                .orElseGet(() -> new Analysis("AUTO", null, null, null));
    }

    /** 화면의 최신 캡처 (수동 업로드 포함, 수집 일시 최신 → 같은 회차면 IDX 순). runSeq: 수동 업로드는 0 */
    public Capture latestCapture(String screenId) {
        return jdbc.query("""
                SELECT C.CAPTURE_ID, C.WIDTH, C.HEIGHT, C.COLLECTED_AT, R.SEQ, C.SCALE, C.STATE
                  FROM AS_CAPTURE C LEFT JOIN AS_COLLECT_RUN R ON R.RUN_ID = C.RUN_ID
                 WHERE C.SCREEN_ID = ?
                 ORDER BY C.COLLECTED_AT DESC, COALESCE(R.SEQ, 0) DESC, C.IDX
                 FETCH FIRST 1 ROWS ONLY""",
                (rs, i) -> new Capture(rs.getString(1), rs.getInt(2), rs.getInt(3), Times.read(rs, "COLLECTED_AT"),
                        rs.getObject(5) == null ? 0 : rs.getInt(5), rs.getDouble(6), rs.getString(7)), screenId)
                .stream().findFirst().orElse(null);
    }

    /** SELECT ELEMENT_ID, SEQ_NO, ELEMENT_CODE, LABEL, TYPE, BOX_X..H, BINDING, MATCHED, DANGER, MANUAL_YN, HIDDEN */
    public static ElementSummary element(java.sql.ResultSet rs, List<EventRef> events) throws java.sql.SQLException {
        Double x = rs.getObject(6, Double.class);
        Box box = x == null ? null : new Box(x, rs.getDouble(7), rs.getDouble(8), rs.getDouble(9));
        return new ElementSummary(rs.getString(1), rs.getInt(2), rs.getString(3),
                rs.getString(4) == null ? rs.getString(3) : rs.getString(4), rs.getString(5), box,
                rs.getString(10), rs.getString(11), rs.getBoolean(12), events, "Y".equals(rs.getString(13)),
                rs.getBoolean(14));
    }

    // ───────────────────────── 캡처 이미지 ─────────────────────────

    public FileStorageService.StoredFile captureImage(AuthUser user, String captureId) {
        var row = jdbc.query("SELECT S.SYSTEM_ID, C.FILE_ID FROM AS_CAPTURE C JOIN AS_SCREEN S"
                        + " ON S.SCREEN_ID = C.SCREEN_ID WHERE C.CAPTURE_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, captureId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("캡처"));
        access.forSystem(user, row[0]);
        return files.load(row[1]);
    }

    // ───────────────────────── 검색 ─────────────────────────

    public List<SearchHit> search(AuthUser user, String systemId, String q) {
        access.forSystem(user, systemId);
        String term = q == null ? "" : q.trim();
        if (term.isEmpty()) {
            return List.of();
        }
        String like = "%" + term.toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        int limit = 50;
        SystemStructure st = new SystemStructure(jdbc, systemId);
        List<SearchHit> hits = new ArrayList<>();

        jdbc.query("SELECT SCREEN_ID, SCREEN_KEY, SCREEN_CODE, NAME FROM AS_SCREEN WHERE SYSTEM_ID = ?"
                + " AND (LOWER(NAME) LIKE ? ESCAPE '\\' OR LOWER(SCREEN_CODE) LIKE ? ESCAPE '\\')"
                + " ORDER BY SCREEN_CODE FETCH FIRST 50 ROWS ONLY", rs -> {
                    String key = rs.getString(2);
                    List<String> path = st.screens.containsKey(key) ? st.path(key) : List.of(st.systemName);
                    hits.add(new SearchHit("SCREEN", rs.getString(1), rs.getString(4) + " (" + rs.getString(3) + ")",
                            String.join(" › ", path), rs.getString(1), null));
                }, systemId, like, like);

        if (hits.size() < limit) {
            jdbc.query("SELECT E.ELEMENT_ID, E.ELEMENT_CODE, E.LABEL, S.SCREEN_ID, S.NAME, S.SCREEN_CODE"
                    + " FROM AS_ELEMENT E JOIN AS_SCREEN S ON S.SCREEN_ID = E.SCREEN_ID WHERE E.SYSTEM_ID = ?"
                    + " AND (LOWER(E.LABEL) LIKE ? ESCAPE '\\' OR LOWER(E.ELEMENT_CODE) LIKE ? ESCAPE '\\')"
                    + " ORDER BY S.SCREEN_CODE, E.SEQ_NO FETCH FIRST 50 ROWS ONLY", rs -> {
                        String label = rs.getString(3) == null ? rs.getString(2) : rs.getString(3) + " (" + rs.getString(2) + ")";
                        hits.add(new SearchHit("ELEMENT", rs.getString(1), label,
                                rs.getString(5) + " (" + rs.getString(6) + ")", rs.getString(4), rs.getString(1)));
                    }, systemId, like, like);
        }
        if (hits.size() < limit) {
            jdbc.query("SELECT NODE_ID, NODE_TYPE, NAME, FILE_PATH, NODE_KEY FROM AS_NODE WHERE SYSTEM_ID = ?"
                    + " AND (LOWER(NAME) LIKE ? ESCAPE '\\' OR LOWER(NODE_KEY) LIKE ? ESCAPE '\\')"
                    + " ORDER BY NODE_TYPE, NAME FETCH FIRST 50 ROWS ONLY", rs -> {
                        String sub = rs.getString(4) != null ? rs.getString(4) : rs.getString(2);
                        hits.add(new SearchHit("NODE", rs.getString(1), rs.getString(3), sub, null, null));
                    }, systemId, like, like);
        }
        return hits.size() > limit ? new ArrayList<>(hits.subList(0, limit)) : hits;
    }

    // ───────────────────────── 분석 상태 ─────────────────────────

    /**
     * PM·PL: 상태·담당자·검토자 모두 변경. ANALYST: 본인 담당(또는 미배정) 화면을 WORKING/REVIEW 까지만,
     * 미배정 화면을 바꾸면 본인이 담당자가 된다. (docs/06 각주 3) 변경은 AS_ANALYSIS_HIST 에 기록 (11 §2).
     */
    @Transactional
    public Analysis patchAnalysis(AuthUser user, String screenId, Map<String, Object> body) {
        String systemId = jdbc.query("SELECT SYSTEM_ID FROM AS_SCREEN WHERE SCREEN_ID = ?", (rs, i) -> rs.getString(1),
                screenId).stream().findFirst().orElseThrow(() -> ApiException.notFound("화면"));
        ProjectAccess.Access a = access.forSystem(user, systemId);
        Analysis current = analysis(screenId);

        String status = body.containsKey("status") && body.get("status") != null
                ? String.valueOf(body.get("status")) : null;
        if (status != null && !STATUSES.contains(status)) {
            throw validation("status", "AUTO, WORKING, REVIEW, DONE 중 하나여야 합니다.");
        }
        String comment = body.get("comment") == null ? null : String.valueOf(body.get("comment")).trim();
        if (comment != null && comment.length() > 1000) {
            throw validation("comment", "1000자 이내로 입력해주세요.");
        }
        boolean ownerGiven = body.containsKey("ownerId");
        String ownerId = ownerGiven && body.get("ownerId") != null ? String.valueOf(body.get("ownerId")) : null;
        boolean reviewerGiven = body.containsKey("reviewerId");
        String reviewerId = reviewerGiven && body.get("reviewerId") != null ? String.valueOf(body.get("reviewerId"))
                : null;
        String currentOwner = current.owner() == null ? null : current.owner().userId();
        String currentReviewer = current.reviewer() == null ? null : current.reviewer().userId();

        if (a.has(ProjectAccess.ANALYSIS_FULL)) {
            requireMember(a.projectId(), "ownerId", ownerId);
            requireMember(a.projectId(), "reviewerId", reviewerId);
        } else if ("ANALYST".equals(a.projectRole())) {
            if (ownerGiven || reviewerGiven || (status != null && !Set.of("WORKING", "REVIEW").contains(status))
                    || (currentOwner != null && !currentOwner.equals(user.userId()))) {
                throw ApiException.forbidden();
            }
            if (currentOwner == null) {
                ownerGiven = true;
                ownerId = user.userId();
            }
        } else {
            throw ApiException.forbidden();
        }

        String newStatus = status != null ? status : current.status();
        String newOwner = ownerGiven ? ownerId : currentOwner;
        boolean confirming = "DONE".equals(newStatus) && !"DONE".equals(current.status());
        // 확정 시 검토자가 지정되지 않았으면 확정한 사람을 검토자로 기록
        String newReviewer = reviewerGiven ? reviewerId : (confirming && currentReviewer == null ? user.userId()
                : currentReviewer);
        jdbc.update("""
                UPDATE AS_ANALYSIS SET STATUS = ?, OWNER_ID = ?, REVIEWER_ID = ?,
                       CONFIRMED_AT = CASE WHEN ? = TRUE THEN ? ELSE CONFIRMED_AT END,
                       UPDATED_AT = ?, UPDATED_BY = ?
                 WHERE SCREEN_ID = ?""",
                newStatus, newOwner, newReviewer, confirming, Times.now(), Times.now(), user.userId(), screenId);
        if (!newStatus.equals(current.status()) || !java.util.Objects.equals(newOwner, currentOwner)
                || !java.util.Objects.equals(newReviewer, currentReviewer) || (comment != null && !comment.isEmpty())) {
            history(screenId, user.userId(), current.status(), newStatus, newOwner, newReviewer, comment);
        }
        return analysis(screenId);
    }

    void history(String screenId, String userId, String from, String to, String owner, String reviewer,
            String comment) {
        jdbc.update("INSERT INTO AS_ANALYSIS_HIST (HIST_ID, SCREEN_ID, CHANGED_AT, USER_ID, FROM_STATUS, TO_STATUS,"
                        + " OWNER_ID, REVIEWER_ID, COMMENT_TXT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                kr.pmsx.common.Ulid.next(), screenId, Times.now(), userId, from, to, owner, reviewer,
                comment == null || comment.isEmpty() ? null : comment);
    }

    private void requireMember(String projectId, String field, String userId) {
        if (userId == null) {
            return;
        }
        Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                Integer.class, projectId, userId);
        if (member == null || member == 0) {
            throw validation(field, "프로젝트 멤버가 아닙니다.");
        }
    }

    private static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.", Map.of(field, message));
    }

    Analysis analysisOf(String screenId) {
        return analysis(screenId);
    }
}
