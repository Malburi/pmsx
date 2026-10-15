package kr.pmsx.common.audit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.DateParams;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 감사 로그 조회 + 웹 화면 접근 기록 (docs/10 §5). */
@RestController
public class AuditController {

    public record AuditLogDto(String logId, OffsetDateTime at, String userId, String loginId, String action,
                              String target, String detail, String ip) {
    }

    public record AccessRequest(@NotBlank @Size(max = 500) String path) {
    }

    static final RowMapper<AuditLogDto> MAPPER = (rs, i) -> new AuditLogDto(rs.getString("LOG_ID"),
            Times.read(rs, "LOG_AT"), rs.getString("USER_ID"), rs.getString("LOGIN_ID"), rs.getString("ACTION"),
            rs.getString("TARGET"), rs.getString("DETAIL"), rs.getString("IP"));

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public AuditController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping("/api/v1/admin/audit-logs")
    public PageDto<AuditLogDto> list(@AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) String userId, @RequestParam(required = false) String action,
            @RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Query query = query(user, from, to, userId, action, q);
        int[] ps = PageDto.normalize(page, size);
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM CM_AUDIT_LOG" + query.where, Long.class,
                query.args.toArray());
        List<Object> args = new ArrayList<>(query.args);
        args.add(ps[1]);
        args.add((long) ps[0] * ps[1]);
        List<AuditLogDto> content = jdbc.query("SELECT * FROM CM_AUDIT_LOG" + query.where
                + " ORDER BY LOG_AT DESC, LOG_ID DESC LIMIT ? OFFSET ?", MAPPER, args.toArray());
        return new PageDto<>(content, ps[0], ps[1], total == null ? 0 : total);
    }

    /** 엑셀 내보내기에서도 같은 조건을 쓴다 (최대 50,000건). */
    public List<AuditLogDto> listAll(AuthUser user, String from, String to, String userId, String action,
            String q) {
        Query query = query(user, from, to, userId, action, q);
        return jdbc.query("SELECT * FROM CM_AUDIT_LOG" + query.where + " ORDER BY LOG_AT DESC LIMIT 50000", MAPPER,
                query.args.toArray());
    }

    @PostMapping("/api/v1/audit/access")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void access(@Valid @RequestBody AccessRequest req) {
        audit.log(AuditService.Action.ACCESS, req.path(), null);
    }

    private record Query(String where, List<Object> args) {
    }

    private Query query(AuthUser user, String fromParam, String toParam, String userId, String action, String q) {
        OffsetDateTime from = DateParams.from("from", fromParam);
        OffsetDateTime to = DateParams.toExclusive("to", toParam);
        StringBuilder w = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (!user.isSysAdmin() && !user.isPmo()) {
            List<String> pmProjects = jdbc.queryForList(
                    "SELECT PROJECT_ID FROM PJ_MEMBER WHERE USER_ID = ? AND ROLE_ID = 'PM'", String.class,
                    user.userId());
            if (pmProjects.isEmpty()) {
                throw ApiException.forbidden();
            }
            w.append(" AND PROJECT_ID IN (").append(String.join(",", pmProjects.stream().map(p -> "?").toList()))
                    .append(")");
            args.addAll(pmProjects);
        }
        if (from != null) {
            w.append(" AND LOG_AT >= ?");
            args.add(from);
        }
        if (to != null) {
            w.append(" AND LOG_AT < ?");
            args.add(to);
        }
        if (userId != null && !userId.isBlank()) {
            w.append(" AND USER_ID = ?");
            args.add(userId);
        }
        if (action != null && !action.isBlank()) {
            w.append(" AND ACTION = ?");
            args.add(action);
        }
        if (q != null && !q.isBlank()) {
            w.append(" AND (LOWER(TARGET) LIKE ? OR LOWER(DETAIL) LIKE ? OR LOWER(LOGIN_ID) LIKE ?)");
            String like = "%" + q.toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        return new Query(w.toString(), args);
    }
}
