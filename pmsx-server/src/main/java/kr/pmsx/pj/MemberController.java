package kr.pmsx.pj;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 프로젝트 멤버 (FR-CM-002·003, docs/10 §4). 변경은 PM·SYS_ADMIN. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/members")
public class MemberController {

    public record Member(String userId, String loginId, String name, String orgType, String roleId,
                         LocalDate inDate, LocalDate outDate) {
    }

    public record MemberRequest(String userId, @NotBlank(message = "역할을 선택해주세요.") String roleId,
                                LocalDate inDate, LocalDate outDate) {
    }

    private static final Set<String> MANAGE = Set.of("SYS_ADMIN", "PM");

    private static final RowMapper<Member> MAPPER = (rs, i) -> new Member(rs.getString("USER_ID"),
            rs.getString("LOGIN_ID"), rs.getString("NAME"), rs.getString("ORG_TYPE"), rs.getString("ROLE_ID"),
            rs.getObject("IN_DT", LocalDate.class), rs.getObject("OUT_DT", LocalDate.class));

    private static final String SELECT = "SELECT M.USER_ID, U.LOGIN_ID, U.NAME, U.ORG_TYPE, M.ROLE_ID, M.IN_DT,"
            + " M.OUT_DT FROM PJ_MEMBER M JOIN CM_USER U ON U.USER_ID = M.USER_ID WHERE M.PROJECT_ID = ?";

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final AuditService audit;

    public MemberController(JdbcTemplate jdbc, ProjectAccess access, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.audit = audit;
    }

    @GetMapping
    public List<Member> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query(SELECT + " ORDER BY M.ROLE_ID, U.NAME", MAPPER, projectId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Member add(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @Valid @RequestBody MemberRequest req) {
        access.forProject(user, projectId).require(MANAGE);
        if (req.userId() == null || req.userId().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                    java.util.Map.of("userId", "사용자를 선택해주세요."));
        }
        checkRole(req.roleId());
        checkDates(req);
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER WHERE USER_ID = ?", Integer.class,
                req.userId());
        if (exists == null || exists == 0) {
            throw ApiException.notFound("사용자");
        }
        try {
            jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT, OUT_DT) VALUES (?, ?, ?, ?, ?)",
                    projectId, req.userId(), req.roleId(), req.inDate(), req.outDate());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 프로젝트 멤버입니다.");
        }
        audit.log(Action.CREATE, "member:" + req.userId(), "멤버 추가 (" + req.roleId() + ")", projectId);
        return one(projectId, req.userId());
    }

    @PutMapping("/{userId}")
    public Member update(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @PathVariable String userId, @Valid @RequestBody MemberRequest req) {
        access.forProject(user, projectId).require(MANAGE);
        checkRole(req.roleId());
        checkDates(req);
        int n = jdbc.update("UPDATE PJ_MEMBER SET ROLE_ID = ?, IN_DT = ?, OUT_DT = ? WHERE PROJECT_ID = ? AND USER_ID = ?",
                req.roleId(), req.inDate(), req.outDate(), projectId, userId);
        if (n == 0) {
            throw ApiException.notFound("프로젝트 멤버");
        }
        audit.log(Action.UPDATE, "member:" + userId, "멤버 변경 (" + req.roleId() + ")", projectId);
        return one(projectId, userId);
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @PathVariable String userId) {
        access.forProject(user, projectId).require(MANAGE);
        if (userId.equals(user.userId()) && !user.isSysAdmin()) {
            throw ApiException.badRequest("VALIDATION", "본인을 프로젝트에서 제외할 수 없습니다.");
        }
        int n = jdbc.update("DELETE FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?", projectId, userId);
        if (n == 0) {
            throw ApiException.notFound("프로젝트 멤버");
        }
        audit.log(Action.DELETE, "member:" + userId, "멤버 제외", projectId);
    }

    private Member one(String projectId, String userId) {
        return jdbc.query(SELECT + " AND M.USER_ID = ?", MAPPER, projectId, userId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("프로젝트 멤버"));
    }

    private void checkRole(String roleId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_ROLE WHERE ROLE_ID = ? AND SCOPE = 'PROJECT'",
                Integer.class, roleId);
        if (n == null || n == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                    java.util.Map.of("roleId", "프로젝트 역할이 아닙니다: " + roleId));
        }
    }

    private static void checkDates(MemberRequest req) {
        if (req.inDate() != null && req.outDate() != null && req.outDate().isBefore(req.inDate())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                    java.util.Map.of("outDate", "철수일은 투입일 이후여야 합니다."));
        }
    }
}
