package kr.pmsx.cm.admin;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.pmsx.cm.PasswordPolicy;
import kr.pmsx.cm.UserDao;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 사용자·역할 관리 (FR-CM-002, docs/10 §4). SYS_ADMIN 전용(역할 목록 조회 제외). */
@RestController
@RequestMapping("/api/v1")
public class UserAdminController {

    public record AdminUser(String userId, String loginId, String name, String email, String orgType,
                            String authType, List<String> roles, boolean locked, OffsetDateTime lastLoginAt) {
    }

    public record UserRequest(@Size(max = 50) @Pattern(regexp = "^[A-Za-z0-9._@-]*$",
            message = "아이디는 영문·숫자·._@- 만 쓸 수 있습니다.") String loginId,
                              @NotBlank(message = "이름을 입력해주세요.") @Size(max = 100) String name,
                              @Size(max = 200) String email,
                              @NotBlank(message = "소속을 선택해주세요.") String orgType,
                              @NotBlank(message = "인증 방식을 선택해주세요.") @Pattern(regexp = "LOCAL|LDAP",
                                      message = "인증 방식은 LOCAL 또는 LDAP 입니다.") String authType,
                              List<String> roles, String password) {
    }

    public record ResetPasswordRequest(@NotBlank(message = "새 비밀번호를 입력해주세요.") String newPassword) {
    }

    public record RoleDto(String roleId, String scope, String name) {
    }

    private static final Set<String> ORG_TYPES = Set.of("INTERNAL", "PARTNER", "CUSTOMER");

    private final JdbcTemplate jdbc;
    private final UserDao users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final AuditService audit;

    public UserAdminController(JdbcTemplate jdbc, UserDao users, PasswordEncoder encoder, PasswordPolicy policy,
            AuditService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.policy = policy;
        this.audit = audit;
    }

    public record UserLookup(String userId, String loginId, String name, String orgType) {
    }

    /** 멤버 추가·담당자 지정용 사용자 검색 (docs/10 v0.2). 로그인 사용자 누구나, 잠금 제외, 최대 20건. */
    @GetMapping("/users/lookup")
    public List<UserLookup> lookup(@RequestParam String q) {
        String term = q.trim().toLowerCase();
        if (term.isEmpty()) {
            return List.of();
        }
        String like = "%" + term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.query("SELECT USER_ID, LOGIN_ID, NAME, ORG_TYPE FROM CM_USER WHERE LOCKED = FALSE AND ENABLED = TRUE"
                        + " AND (LOWER(LOGIN_ID) LIKE ? ESCAPE '\\' OR LOWER(NAME) LIKE ? ESCAPE '\\')"
                        + " ORDER BY NAME, LOGIN_ID FETCH FIRST 20 ROWS ONLY",
                (rs, i) -> new UserLookup(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)),
                like, like);
    }

    @GetMapping("/admin/roles")
    public List<RoleDto> roles() {
        return jdbc.query("SELECT ROLE_ID, SCOPE, NAME FROM CM_ROLE ORDER BY SCOPE DESC, ROLE_ID",
                (rs, i) -> new RoleDto(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    @GetMapping("/admin/users")
    public PageDto<AdminUser> list(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        requireAdmin(user);
        int[] ps = PageDto.normalize(page, size);
        String where = "";
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where = " WHERE LOWER(LOGIN_ID) LIKE ? OR LOWER(NAME) LIKE ? OR LOWER(COALESCE(EMAIL, '')) LIKE ?";
            String like = "%" + q.toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER" + where, Long.class, args.toArray());
        args.add(ps[1]);
        args.add((long) ps[0] * ps[1]);
        List<String> ids = jdbc.queryForList("SELECT USER_ID FROM CM_USER" + where
                + " ORDER BY LOGIN_ID LIMIT ? OFFSET ?", String.class, args.toArray());
        return new PageDto<>(ids.stream().map(this::get).toList(), ps[0], ps[1], total == null ? 0 : total);
    }

    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public AdminUser create(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody UserRequest req) {
        requireAdmin(user);
        if (req.loginId() == null || req.loginId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                    java.util.Map.of("loginId", "아이디를 입력해주세요."));
        }
        if (users.findByLoginId(req.loginId()).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 사용 중인 아이디입니다: " + req.loginId());
        }
        validate(req);
        String hash = null;
        if ("LOCAL".equals(req.authType())) {
            if (req.password() == null || req.password().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                        java.util.Map.of("password", "LOCAL 계정은 비밀번호가 필요합니다."));
            }
            policy.check(req.password());
            hash = encoder.encode(req.password());
        }
        String id = users.insert(req.loginId(), hash, req.name(), req.email(), req.orgType(), req.authType(),
                roles(req), user.userId());
        audit.log(Action.CREATE, "user:" + req.loginId(), "사용자 등록 (" + req.authType() + ")");
        return get(id);
    }

    @PutMapping("/admin/users/{userId}")
    @Transactional
    public AdminUser update(@AuthenticationPrincipal AuthUser user, @PathVariable String userId,
            @Valid @RequestBody UserRequest req) {
        requireAdmin(user);
        UserDao.UserRow row = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        validate(req);
        List<String> roles = roles(req);
        if (userId.equals(user.userId()) && !roles.contains("SYS_ADMIN")) {
            throw ApiException.badRequest("VALIDATION", "본인 계정의 시스템 관리자 권한은 해제할 수 없습니다.");
        }
        jdbc.update("UPDATE CM_USER SET NAME = ?, EMAIL = ?, ORG_TYPE = ?, AUTH_TYPE = ?, UPDATED_AT = ?, UPDATED_BY = ?"
                + " WHERE USER_ID = ?", req.name(), req.email(), req.orgType(), req.authType(), Times.now(),
                user.userId(), userId);
        users.setSystemRoles(userId, roles);
        audit.log(Action.UPDATE, "user:" + row.loginId(), "사용자 정보 변경");
        return get(userId);
    }

    @PostMapping("/admin/users/{userId}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void resetPassword(@AuthenticationPrincipal AuthUser user, @PathVariable String userId,
            @Valid @RequestBody ResetPasswordRequest req) {
        requireAdmin(user);
        UserDao.UserRow row = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        List<String> previous = new ArrayList<>(users.recentPasswordHashes(userId, 2));
        if (row.passwordHash() != null) {
            previous.add(row.passwordHash());
        }
        policy.checkNew(req.newPassword(), previous);
        users.changePassword(userId, encoder.encode(req.newPassword()), user.userId());
        users.setLocked(userId, false);
        audit.log(Action.UPDATE, "user:" + row.loginId(), "비밀번호 초기화");
    }

    @PostMapping("/admin/users/{userId}/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void lock(@AuthenticationPrincipal AuthUser user, @PathVariable String userId) {
        requireAdmin(user);
        if (userId.equals(user.userId())) {
            throw ApiException.badRequest("VALIDATION", "본인 계정은 잠글 수 없습니다.");
        }
        UserDao.UserRow row = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        users.setLocked(userId, true);
        jdbc.update("UPDATE CM_REFRESH_TOKEN SET REVOKED = TRUE WHERE USER_ID = ?", userId);
        audit.log(Action.UPDATE, "user:" + row.loginId(), "계정 잠금");
    }

    @PostMapping("/admin/users/{userId}/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlock(@AuthenticationPrincipal AuthUser user, @PathVariable String userId) {
        requireAdmin(user);
        UserDao.UserRow row = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        users.setLocked(userId, false);
        audit.log(Action.UPDATE, "user:" + row.loginId(), "잠금 해제");
    }

    public AdminUser get(String userId) {
        UserDao.UserRow r = users.findById(userId).orElseThrow(() -> ApiException.notFound("사용자"));
        return new AdminUser(r.userId(), r.loginId(), r.name(), r.email(), r.orgType(), r.authType(),
                users.systemRoles(userId), r.locked(), r.lastLoginAt());
    }

    private void validate(UserRequest req) {
        if (!ORG_TYPES.contains(req.orgType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                    java.util.Map.of("orgType", "소속은 INTERNAL·PARTNER·CUSTOMER 중 하나입니다."));
        }
    }

    private List<String> roles(UserRequest req) {
        List<String> roles = req.roles() == null ? List.of() : req.roles().stream().distinct().toList();
        List<String> systemRoles = jdbc.queryForList("SELECT ROLE_ID FROM CM_ROLE WHERE SCOPE = 'SYSTEM'",
                String.class);
        for (String r : roles) {
            if (!systemRoles.contains(r)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                        java.util.Map.of("roles", "시스템 역할이 아닙니다: " + r));
            }
        }
        return roles;
    }

    private static void requireAdmin(AuthUser user) {
        if (!user.isSysAdmin()) {
            throw ApiException.forbidden();
        }
    }
}
