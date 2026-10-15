package kr.pmsx.cm;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import kr.pmsx.cm.AuthDtos.UserDto;
import kr.pmsx.cm.license.LicenseService;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.security.JwtService;
import kr.pmsx.common.storage.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserDao users;
    private final JwtService jwt;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final PmsxProperties props;
    private final LdapAuthenticator ldap;
    private final LicenseService license;
    private final PasswordPolicy policy;
    private final AuditService audit;

    public AuthService(UserDao users, JwtService jwt, PasswordEncoder encoder, JdbcTemplate jdbc,
            PmsxProperties props, LdapAuthenticator ldap, LicenseService license, PasswordPolicy policy,
            AuditService audit) {
        this.users = users;
        this.jwt = jwt;
        this.encoder = encoder;
        this.jdbc = jdbc;
        this.props = props;
        this.ldap = ldap;
        this.license = license;
        this.policy = policy;
        this.audit = audit;
    }

    /** 로그인/갱신 결과: 응답 본문용 access token 과 쿠키에 실을 refresh token. */
    public record Tokens(String accessToken, String refreshToken, UserDto user) {
    }

    /**
     * 로그인. 실패 횟수·잠금은 롤백되면 안 되므로 메서드 전체를 트랜잭션으로 묶지 않는다(문장 단위 커밋).
     */
    public Tokens login(String loginId, String password) {
        Optional<UserDao.UserRow> found = users.findByLoginId(loginId).filter(UserDao.UserRow::enabled);
        if (found.isEmpty()) {
            audit.logAs(Action.LOGIN_FAIL, null, loginId, "auth", "존재하지 않거나 사용 중지된 계정");
            throw invalidCredentials();
        }
        UserDao.UserRow row = found.get();
        if (row.locked()) {
            audit.logAs(Action.LOGIN_FAIL, row.userId(), loginId, "auth", "잠긴 계정");
            throw locked();
        }
        boolean ok = "LDAP".equals(row.authType())
                ? ldap.authenticate(loginId, password)
                : row.passwordHash() != null && encoder.matches(password, row.passwordHash());
        if (!ok) {
            users.loginFailed(row.userId(), props.maxLoginFailures());
            boolean nowLocked = users.findById(row.userId()).map(UserDao.UserRow::locked).orElse(false);
            audit.logAs(Action.LOGIN_FAIL, row.userId(), loginId, "auth",
                    nowLocked ? "비밀번호 " + props.maxLoginFailures() + "회 실패로 잠금" : "비밀번호 불일치");
            throw nowLocked ? locked() : invalidCredentials();
        }
        license.checkLogin(users.systemRoles(row.userId()).contains("SYS_ADMIN"));
        users.loginSucceeded(row.userId());
        Tokens t = issue(row);
        audit.logAs(Action.LOGIN, row.userId(), loginId, "auth", row.authType());
        return t;
    }

    @Transactional
    public Tokens refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw expired();
        }
        String hash = FileStorageService.sha256(refreshToken.getBytes());
        Optional<String> userId = jdbc.query(
                "SELECT USER_ID FROM CM_REFRESH_TOKEN WHERE TOKEN_HASH = ? AND REVOKED = FALSE AND EXPIRES_AT > ?",
                (rs, i) -> rs.getString(1), hash, Times.now()).stream().findFirst();
        if (userId.isEmpty()) {
            throw expired();
        }
        // 회전(rotation): 쓴 토큰은 폐기하고 새로 발급
        jdbc.update("UPDATE CM_REFRESH_TOKEN SET REVOKED = TRUE WHERE TOKEN_HASH = ?", hash);
        UserDao.UserRow row = users.findById(userId.get())
                .filter(u -> u.enabled() && !u.locked())
                .orElseThrow(this::expired);
        return issue(row);
    }

    @Transactional
    public void logout(String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            String hash = FileStorageService.sha256(refreshToken.getBytes());
            jdbc.query("SELECT T.USER_ID, U.LOGIN_ID FROM CM_REFRESH_TOKEN T JOIN CM_USER U ON U.USER_ID = T.USER_ID"
                    + " WHERE T.TOKEN_HASH = ? AND T.REVOKED = FALSE", rs -> {
                        audit.logAs(Action.LOGOUT, rs.getString(1), rs.getString(2), "auth", null);
                    }, hash);
            jdbc.update("UPDATE CM_REFRESH_TOKEN SET REVOKED = TRUE WHERE TOKEN_HASH = ?", hash);
        }
    }

    public UserDto me(AuthUser user) {
        UserDao.UserRow row = users.findById(user.userId()).orElseThrow(this::expired);
        return toDto(row);
    }

    @Transactional
    public void changePassword(AuthUser user, String current, String next) {
        UserDao.UserRow row = users.findById(user.userId()).orElseThrow(this::expired);
        if (!"LOCAL".equals(row.authType())) {
            throw ApiException.badRequest("VALIDATION", "LDAP 계정은 이 화면에서 비밀번호를 바꿀 수 없습니다.");
        }
        if (row.passwordHash() == null || !encoder.matches(current, row.passwordHash())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "현재 비밀번호가 올바르지 않습니다.",
                    java.util.Map.of("currentPassword", "현재 비밀번호가 올바르지 않습니다."));
        }
        List<String> previous = new ArrayList<>(users.recentPasswordHashes(row.userId(), 2));
        previous.add(row.passwordHash());
        policy.checkNew(next, previous);
        users.changePassword(row.userId(), encoder.encode(next), row.userId());
        audit.log(Action.UPDATE, "user:" + row.loginId(), "비밀번호 변경");
    }

    /** 만료·폐기된 refresh 토큰 정리 (스케줄러에서 호출). */
    @Transactional
    public int purgeRefreshTokens() {
        return jdbc.update("DELETE FROM CM_REFRESH_TOKEN WHERE EXPIRES_AT < ? OR (REVOKED = TRUE AND CREATED_AT < ?)",
                Times.now(), Times.now().minusDays(1));
    }

    private Tokens issue(UserDao.UserRow row) {
        List<String> systemRoles = users.systemRoles(row.userId());
        String access = jwt.issueAccessToken(new AuthUser(row.userId(), row.loginId(), row.name(), systemRoles));
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String refresh = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        jdbc.update("INSERT INTO CM_REFRESH_TOKEN (TOKEN_HASH, USER_ID, EXPIRES_AT, CREATED_AT) VALUES (?, ?, ?, ?)",
                FileStorageService.sha256(refresh.getBytes()), row.userId(),
                Times.now().plus(props.jwt().refreshTtl()), Times.now());
        return new Tokens(access, refresh, toDto(row));
    }

    private UserDto toDto(UserDao.UserRow row) {
        LinkedHashSet<String> roles = new LinkedHashSet<>(users.systemRoles(row.userId()));
        roles.addAll(users.projectRoles(row.userId()));
        return new UserDto(row.userId(), row.loginId(), row.name(), new ArrayList<>(roles));
    }

    private static ApiException invalidCredentials() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "아이디 또는 비밀번호가 올바르지 않습니다.");
    }

    private ApiException locked() {
        return new ApiException(HttpStatus.LOCKED, "AUTH_LOCKED",
                "비밀번호를 " + props.maxLoginFailures() + "회 이상 틀려 계정이 잠겼습니다. 관리자에게 잠금 해제를 요청하세요.");
    }

    private ApiException expired() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_EXPIRED", "다시 로그인해주세요.");
    }
}
