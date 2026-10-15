package kr.pmsx.as.viewer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 시스템의 메뉴·화면 구조 (최신 수집 회차 기준). 메뉴 트리 + 팝업(호출 화면 아래) + 메뉴 미연결 화면.
 * 메뉴 경로·1단계 메뉴·메뉴 하위 화면 계산에 쓴다.
 */
public final class SystemStructure {

    public record MenuRow(String id, String key, String parentKey, String name, int order, String screenKey) {
    }

    public record ScreenRow(String id, String key, String code, String name, String kind, String status) {
    }

    final String systemName;
    final String latestRunId;
    final Map<String, MenuRow> menus = new LinkedHashMap<>();
    final Map<String, ScreenRow> screens = new LinkedHashMap<>();
    final Map<String, String> menuKeyByScreenKey = new HashMap<>();
    final Map<String, String> callerByPopupKey = new HashMap<>();   // 팝업 화면 키 → 호출 화면 키
    private final JdbcTemplate jdbc;

    public SystemStructure(JdbcTemplate jdbc, String systemId) {
        this.jdbc = jdbc;
        systemName = jdbc.queryForObject("SELECT NAME FROM AS_SYSTEM WHERE SYSTEM_ID = ?", String.class, systemId);
        latestRunId = latestRunId(jdbc, systemId);
        if (latestRunId == null) {
            return;
        }
        jdbc.query("SELECT MENU_ID, MENU_KEY, PARENT_KEY, NAME, SORT_ORD, SCREEN_KEY FROM AS_MENU"
                + " WHERE SYSTEM_ID = ? AND LAST_RUN_ID = ? ORDER BY SORT_ORD, NAME", rs -> {
                    MenuRow m = new MenuRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6));
                    menus.put(m.key(), m);
                    if (m.screenKey() != null) {
                        menuKeyByScreenKey.putIfAbsent(m.screenKey(), m.key());
                    }
                }, systemId, latestRunId);
        jdbc.query("SELECT S.SCREEN_ID, S.SCREEN_KEY, S.SCREEN_CODE, S.NAME, S.KIND, A.STATUS FROM AS_SCREEN S"
                + " LEFT JOIN AS_ANALYSIS A ON A.SCREEN_ID = S.SCREEN_ID"
                + " WHERE S.SYSTEM_ID = ? AND S.LAST_RUN_ID = ? ORDER BY S.SCREEN_CODE", rs -> {
                    ScreenRow s = new ScreenRow(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6) == null ? "AUTO" : rs.getString(6));
                    screens.put(s.key(), s);
                }, systemId, latestRunId);
        findCallers(systemId);
    }

    public static String latestRunId(JdbcTemplate jdbc, String systemId) {
        return jdbc.query("SELECT RUN_ID FROM AS_COLLECT_RUN WHERE SYSTEM_ID = ? ORDER BY SEQ DESC"
                + " FETCH FIRST 1 ROWS ONLY", (rs, i) -> rs.getString(1), systemId).stream().findFirst().orElse(null);
    }

    public String systemName() {
        return systemName;
    }

    public String latestRunId() {
        return latestRunId;
    }

    public Collection<ScreenRow> screens() {
        return screens.values();
    }

    public ScreenRow screen(String key) {
        return screens.get(key);
    }

    public boolean contains(String screenKey) {
        return screens.containsKey(screenKey);
    }

    public Map<String, String> callerByPopupKey() {
        return callerByPopupKey;
    }

    /** 메뉴에 없는 화면 중 navigates 엣지의 대상인 것 → 호출 화면 찾기. */
    private void findCallers(String systemId) {
        for (ScreenRow s : screens.values()) {
            if (menuKeyByScreenKey.containsKey(s.key())) {
                continue;
            }
            List<String> froms = jdbc.queryForList("SELECT FROM_KEY FROM AS_EDGE WHERE SYSTEM_ID = ?"
                    + " AND TO_KEY = ? AND KIND = 'navigates' AND STATUS = 'active' ORDER BY FROM_KEY",
                    String.class, systemId, s.key());
            for (String from : froms) {
                String caller = callerScreenKey(jdbc, systemId, from);
                if (caller != null && !caller.equals(s.key())) {
                    callerByPopupKey.put(s.key(), caller);
                    break;
                }
            }
        }
    }

    /** 엣지 출발 키(요소·함수·화면)가 속한 화면 키. 요소 → 그 화면, 함수 → 그 함수를 이벤트로 부르는 요소의 화면. */
    public static String callerScreenKey(JdbcTemplate jdbc, String systemId, String fromKey) {
        if (fromKey.startsWith("scr:")) {
            return fromKey;
        }
        List<String> direct = jdbc.queryForList("SELECT S.SCREEN_KEY FROM AS_ELEMENT E JOIN AS_SCREEN S"
                + " ON S.SCREEN_ID = E.SCREEN_ID WHERE E.SYSTEM_ID = ? AND E.ELEMENT_KEY = ?", String.class,
                systemId, fromKey);
        if (!direct.isEmpty()) {
            return direct.get(0);
        }
        List<String> viaFn = jdbc.queryForList("SELECT S.SCREEN_KEY FROM AS_EDGE D JOIN AS_ELEMENT E"
                + " ON E.SYSTEM_ID = D.SYSTEM_ID AND E.ELEMENT_KEY = D.FROM_KEY JOIN AS_SCREEN S"
                + " ON S.SCREEN_ID = E.SCREEN_ID WHERE D.SYSTEM_ID = ? AND D.TO_KEY = ? AND D.STATUS = 'active'"
                + " ORDER BY S.SCREEN_KEY", String.class, systemId, fromKey);
        if (!viaFn.isEmpty()) {
            return viaFn.get(0);
        }
        // 화면 로드(scr: → event) 로 불리는 함수
        List<String> viaLoad = jdbc.queryForList("SELECT D.FROM_KEY FROM AS_EDGE D WHERE D.SYSTEM_ID = ?"
                + " AND D.TO_KEY = ? AND D.STATUS = 'active' AND D.FROM_KEY LIKE 'scr:%' ORDER BY D.FROM_KEY",
                String.class, systemId, fromKey);
        return viaLoad.isEmpty() ? null : viaLoad.get(0);
    }

    public boolean isUnlinked(ScreenRow s) {
        return !menuKeyByScreenKey.containsKey(s.key()) && !callerByPopupKey.containsKey(s.key());
    }

    /** [시스템명, 상위 메뉴..., 메뉴명] (팝업은 호출 화면 경로 + 팝업명) */
    public List<String> path(String screenKey) {
        List<String> out = new ArrayList<>();
        ScreenRow s = screens.get(screenKey);
        List<MenuRow> chain = menuChain(screenKey);
        if (!chain.isEmpty() && menuKeyByScreenKey.containsKey(screenKey)) {
            if (!chain.get(0).name().equals(systemName)) {
                out.add(systemName);
            }
            chain.forEach(m -> out.add(m.name()));
            return out;
        }
        String caller = callerByPopupKey.get(screenKey);
        if (caller != null && !caller.equals(screenKey) && menuKeyByScreenKey.containsKey(caller)) {
            out.addAll(path(caller));
        } else {
            out.add(systemName);
        }
        if (s != null) {
            out.add(s.name());
        }
        return out;
    }

    /** 화면이 속한 메뉴 체인(최상위 → 화면 메뉴). 팝업은 호출 화면의 체인. 없으면 빈 목록. */
    public List<MenuRow> menuChain(String screenKey) {
        String menuKey = menuKeyByScreenKey.get(screenKey);
        if (menuKey == null) {
            String caller = callerByPopupKey.get(screenKey);
            Set<String> guard = new HashSet<>();
            while (caller != null && guard.add(caller) && menuKey == null) {
                menuKey = menuKeyByScreenKey.get(caller);
                caller = callerByPopupKey.get(caller);
            }
        }
        List<MenuRow> chain = new ArrayList<>();
        Set<String> guard = new HashSet<>();
        for (MenuRow m = menuKey == null ? null : menus.get(menuKey); m != null && guard.add(m.key());
                m = m.parentKey() == null ? null : menus.get(m.parentKey())) {
            chain.add(0, m);
        }
        return chain;
    }

    /** 진척 집계용 1단계 메뉴. 시스템명과 같은 최상위 메뉴는 건너뛴다. 메뉴가 없으면 null. */
    public MenuRow topMenu(String screenKey) {
        List<MenuRow> chain = menuChain(screenKey);
        if (chain.isEmpty()) {
            return null;
        }
        if (chain.size() > 1 && chain.get(0).name().equals(systemName)) {
            return chain.get(1);
        }
        return chain.get(0);
    }

    /** menuId 아래(자신 포함)에 있는 화면 키들 (팝업 포함). */
    public Set<String> screensUnderMenu(String menuId) {
        Set<String> out = new LinkedHashSet<>();
        for (ScreenRow s : screens.values()) {
            for (MenuRow m : menuChain(s.key())) {
                if (m.id().equals(menuId)) {
                    out.add(s.key());
                    break;
                }
            }
        }
        return out;
    }
}
