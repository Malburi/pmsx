package kr.pmsx.cm;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class UserDao {

    private static final String COLS = "USER_ID, LOGIN_ID, NAME, PASSWORD_HASH, ENABLED, LOCKED, FAIL_COUNT,"
            + " AUTH_TYPE, ORG_TYPE, EMAIL, LAST_LOGIN_AT";

    private static final RowMapper<UserRow> MAPPER = (rs, i) -> new UserRow(rs.getString("USER_ID"),
            rs.getString("LOGIN_ID"), rs.getString("NAME"), rs.getString("PASSWORD_HASH"), rs.getBoolean("ENABLED"),
            rs.getBoolean("LOCKED"), rs.getInt("FAIL_COUNT"), rs.getString("AUTH_TYPE"), rs.getString("ORG_TYPE"),
            rs.getString("EMAIL"), Times.read(rs, "LAST_LOGIN_AT"));

    private final JdbcTemplate jdbc;

    public UserDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record UserRow(String userId, String loginId, String name, String passwordHash, boolean enabled,
                          boolean locked, int failCount, String authType, String orgType, String email,
                          OffsetDateTime lastLoginAt) {
    }

    public Optional<UserRow> findByLoginId(String loginId) {
        return jdbc.query("SELECT " + COLS + " FROM CM_USER WHERE LOGIN_ID = ?", MAPPER, loginId).stream()
                .findFirst();
    }

    public Optional<UserRow> findById(String userId) {
        return jdbc.query("SELECT " + COLS + " FROM CM_USER WHERE USER_ID = ?", MAPPER, userId).stream()
                .findFirst();
    }

    public List<String> systemRoles(String userId) {
        return jdbc.queryForList("SELECT ROLE_ID FROM CM_USER_ROLE WHERE USER_ID = ? ORDER BY ROLE_ID",
                String.class, userId);
    }

    public List<String> projectRoles(String userId) {
        return jdbc.queryForList("SELECT DISTINCT ROLE_ID FROM PJ_MEMBER WHERE USER_ID = ? ORDER BY ROLE_ID",
                String.class, userId);
    }

    public String insert(String loginId, String passwordHash, String name, List<String> systemRoles) {
        return insert(loginId, passwordHash, name, null, "INTERNAL", "LOCAL", systemRoles, null);
    }

    public String insert(String loginId, String passwordHash, String name, String email, String orgType,
            String authType, List<String> systemRoles, String createdBy) {
        String id = Ulid.next();
        jdbc.update("INSERT INTO CM_USER (USER_ID, LOGIN_ID, PASSWORD_HASH, NAME, EMAIL, ORG_TYPE, AUTH_TYPE,"
                        + " PASSWORD_CHANGED_AT, CREATED_AT, CREATED_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, loginId, passwordHash, name, email, orgType, authType,
                passwordHash == null ? null : Times.now(), Times.now(), createdBy);
        setSystemRoles(id, systemRoles);
        if (passwordHash != null) {
            addPasswordHistory(id, passwordHash);
        }
        return id;
    }

    public void setSystemRoles(String userId, List<String> roles) {
        jdbc.update("DELETE FROM CM_USER_ROLE WHERE USER_ID = ?", userId);
        for (String role : roles) {
            jdbc.update("INSERT INTO CM_USER_ROLE (USER_ID, ROLE_ID) VALUES (?, ?)", userId, role);
        }
    }

    public void loginFailed(String userId, int maxFailures) {
        jdbc.update("UPDATE CM_USER SET FAIL_COUNT = FAIL_COUNT + 1 WHERE USER_ID = ?", userId);
        jdbc.update("UPDATE CM_USER SET LOCKED = TRUE WHERE USER_ID = ? AND FAIL_COUNT >= ?", userId, maxFailures);
    }

    public void loginSucceeded(String userId) {
        jdbc.update("UPDATE CM_USER SET FAIL_COUNT = 0, LAST_LOGIN_AT = ? WHERE USER_ID = ?", Times.now(), userId);
    }

    public void setLocked(String userId, boolean locked) {
        jdbc.update("UPDATE CM_USER SET LOCKED = ?, FAIL_COUNT = 0 WHERE USER_ID = ?", locked, userId);
    }

    public void changePassword(String userId, String hash, String by) {
        jdbc.update("UPDATE CM_USER SET PASSWORD_HASH = ?, PASSWORD_CHANGED_AT = ?, UPDATED_AT = ?, UPDATED_BY = ?"
                + " WHERE USER_ID = ?", hash, Times.now(), Times.now(), by, userId);
        addPasswordHistory(userId, hash);
    }

    /** 직전 비밀번호 해시 (현재 해시 제외 가장 최근 1건) */
    public List<String> recentPasswordHashes(String userId, int n) {
        return jdbc.queryForList("SELECT PASSWORD_HASH FROM CM_PASSWORD_HISTORY WHERE USER_ID = ?"
                + " ORDER BY CREATED_AT DESC LIMIT ?", String.class, userId, n);
    }

    private void addPasswordHistory(String userId, String hash) {
        jdbc.update("INSERT INTO CM_PASSWORD_HISTORY (USER_ID, PASSWORD_HASH, CREATED_AT) VALUES (?, ?, ?)",
                userId, hash, Times.now());
    }
}
