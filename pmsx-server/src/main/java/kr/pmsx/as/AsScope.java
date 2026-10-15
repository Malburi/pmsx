package kr.pmsx.as;

import java.util.Map;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** As-Is 대상(화면·요소·노드) 조회 + 권한 확인 공통. */
@Component
public class AsScope {

    public record ScreenRef(String screenId, String systemId, String screenKey, String code, String name,
                            String lastRunId) {
    }

    public record ElementRef(String elementId, String systemId, String screenId, String elementKey, String code,
                             String label, boolean manual) {
    }

    public record NodeRef(String nodeId, String systemId, String nodeKey, String nodeType, String name, String file,
                          String lang) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;

    public AsScope(JdbcTemplate jdbc, ProjectAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    public ScreenRef screen(String screenId) {
        return jdbc.query("SELECT SCREEN_ID, SYSTEM_ID, SCREEN_KEY, SCREEN_CODE, NAME, LAST_RUN_ID FROM AS_SCREEN"
                        + " WHERE SCREEN_ID = ?",
                (rs, i) -> new ScreenRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6)), screenId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("화면"));
    }

    public ElementRef element(String elementId) {
        return jdbc.query("SELECT ELEMENT_ID, SYSTEM_ID, SCREEN_ID, ELEMENT_KEY, ELEMENT_CODE, LABEL, MANUAL_YN"
                        + " FROM AS_ELEMENT WHERE ELEMENT_ID = ?",
                (rs, i) -> new ElementRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), "Y".equals(rs.getString(7))), elementId).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("요소"));
    }

    public NodeRef node(String nodeId) {
        return jdbc.query("SELECT NODE_ID, SYSTEM_ID, NODE_KEY, NODE_TYPE, NAME, FILE_PATH, LANG FROM AS_NODE"
                        + " WHERE NODE_ID = ?",
                (rs, i) -> new NodeRef(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7)), nodeId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("노드"));
    }

    public ProjectAccess.Access read(AuthUser user, String systemId) {
        return access.forSystem(user, systemId);
    }

    /**
     * 화면 단위 변경 권한. allowed 역할이어야 하고, ANALYST 는 본인 담당(또는 미배정) 화면만 (06 "O" 표기).
     * screenId 가 null 이면(노드 기점 등) 역할만 본다.
     */
    public ProjectAccess.Access requireEdit(AuthUser user, String systemId, String screenId, Set<String> allowed) {
        ProjectAccess.Access a = access.forSystem(user, systemId);
        a.require(allowed);
        if (screenId != null && "ANALYST".equals(a.projectRole()) && !a.has(ProjectAccess.ANALYSIS_FULL)) {
            String owner = jdbc.query("SELECT OWNER_ID FROM AS_ANALYSIS WHERE SCREEN_ID = ?",
                    (rs, i) -> rs.getString(1), screenId).stream().findFirst().orElse(null);
            if (owner != null && !owner.equals(user.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인이 담당한 화면만 수정할 수 있습니다.");
            }
        }
        return a;
    }

    public String projectId(String systemId) {
        return jdbc.queryForObject("SELECT PROJECT_ID FROM AS_SYSTEM WHERE SYSTEM_ID = ?", String.class, systemId);
    }

    /** 수집 키 → 대리 ID (요소·노드·화면). 없으면 null. */
    public String idOfKey(String systemId, String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith("el:")) {
            return first("SELECT ELEMENT_ID FROM AS_ELEMENT WHERE SYSTEM_ID = ? AND ELEMENT_KEY = ?", systemId, key);
        }
        if (key.startsWith("scr:")) {
            return first("SELECT SCREEN_ID FROM AS_SCREEN WHERE SYSTEM_ID = ? AND SCREEN_KEY = ?", systemId, key);
        }
        return first("SELECT NODE_ID FROM AS_NODE WHERE SYSTEM_ID = ? AND NODE_KEY = ?", systemId, key);
    }

    private String first(String sql, Object... args) {
        return jdbc.query(sql, (rs, i) -> rs.getString(1), args).stream().findFirst().orElse(null);
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.", Map.of(field, message));
    }
}
