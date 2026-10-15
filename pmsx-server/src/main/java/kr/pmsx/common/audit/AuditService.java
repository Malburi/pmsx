package kr.pmsx.common.audit;

import jakarta.servlet.http.HttpServletRequest;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 감사 로그 기록 (FR-CM-010, docs/10 §5).
 * 업무 트랜잭션이 롤백돼도 남도록 별도 트랜잭션(REQUIRES_NEW)으로 쓴다. 기록 실패는 업무를 막지 않는다.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    public enum Action { LOGIN, LOGIN_FAIL, LOGOUT, ACCESS, VIEW_SOURCE, DOWNLOAD, UPLOAD, CREATE, UPDATE, DELETE, LICENSE }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public AuditService(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 현재 인증 사용자로 기록 */
    public void log(Action action, String target, String detail) {
        log(action, target, detail, null);
    }

    public void log(Action action, String target, String detail, String projectId) {
        AuthUser u = currentUser();
        write(action, u == null ? null : u.userId(), u == null ? null : u.loginId(), target, detail, projectId);
    }

    /** 인증 전(로그인 실패 등) 사용자 정보를 직접 지정 */
    public void logAs(Action action, String userId, String loginId, String target, String detail) {
        write(action, userId, loginId, target, detail, null);
    }

    private void write(Action action, String userId, String loginId, String target, String detail,
            String projectId) {
        try {
            String ip = clientIp();
            tx.executeWithoutResult(s -> jdbc.update("""
                    INSERT INTO CM_AUDIT_LOG (LOG_ID, LOG_AT, USER_ID, LOGIN_ID, ACTION, TARGET, DETAIL, IP, PROJECT_ID)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Ulid.next(), Times.now(), userId, cut(loginId, 50), action.name(), cut(target, 500),
                    cut(detail, 2000), ip, projectId));
        } catch (RuntimeException e) {
            log.warn("감사 로그 기록 실패: {} {}", action, target, e);
        }
    }

    public static AuthUser currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a != null && a.getPrincipal() instanceof AuthUser u ? u : null;
    }

    public static String clientIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        String fwd = req.getHeader("X-Forwarded-For");
        String ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
        return cut(ip, 64);
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
