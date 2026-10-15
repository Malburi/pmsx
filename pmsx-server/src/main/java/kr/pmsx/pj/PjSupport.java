package kr.pmsx.pj;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** R2 공통 도우미: 사용자 참조, 상태 이력, 목록 문자열, 검증 오류. */
@Component
public class PjSupport {

    public record Hist(OffsetDateTime at, UserRef user, String action, String fromStatus, String toStatus,
                       String comment) {
    }

    private final JdbcTemplate jdbc;

    public PjSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public static LocalDate today() {
        return LocalDate.now(Times.ZONE);
    }

    public UserRef user(String userId) {
        if (userId == null) {
            return null;
        }
        return jdbc.query("SELECT USER_ID, NAME FROM CM_USER WHERE USER_ID = ?",
                (rs, i) -> new UserRef(rs.getString(1), rs.getString(2)), userId).stream().findFirst()
                .orElse(new UserRef(userId, "(알 수 없음)"));
    }

    /** userId → 이름 (일괄) */
    public Map<String, String> names(Collection<String> ids) {
        Map<String, String> out = new HashMap<>();
        List<String> list = ids.stream().filter(x -> x != null && !x.isBlank()).distinct().toList();
        for (int i = 0; i < list.size(); i += 500) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + 500));
            String in = String.join(",", chunk.stream().map(x -> "?").toList());
            jdbc.query("SELECT USER_ID, NAME FROM CM_USER WHERE USER_ID IN (" + in + ")",
                    rs -> {
                        out.put(rs.getString(1), rs.getString(2));
                    }, chunk.toArray());
        }
        return out;
    }

    public static UserRef ref(Map<String, String> names, String id) {
        return id == null ? null : new UserRef(id, names.getOrDefault(id, "(알 수 없음)"));
    }

    /** 사용자 존재 확인 (담당자 지정 등) */
    public void requireUser(String field, String userId) {
        if (userId == null) {
            return;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER WHERE USER_ID = ?", Integer.class, userId);
        if (n == null || n == 0) {
            throw validation(field, "사용자를 찾을 수 없습니다.");
        }
    }

    public void hist(String targetType, String targetId, String action, String from, String to, String comment) {
        AuthUser u = AuditService.currentUser();
        jdbc.update("""
                INSERT INTO CM_STATUS_HIST (HIST_ID, TARGET_TYPE, TARGET_ID, ACTION, FROM_STATUS, TO_STATUS, COMMENT_TXT,
                                            HIST_AT, USER_ID)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", Ulid.next(), targetType, targetId, action, from, to,
                comment, Times.now(), u == null ? null : u.userId());
    }

    public List<Hist> hist(String targetType, String targetId) {
        List<Object[]> rows = new ArrayList<>();
        jdbc.query("SELECT HIST_AT, USER_ID, ACTION, FROM_STATUS, TO_STATUS, COMMENT_TXT FROM CM_STATUS_HIST"
                        + " WHERE TARGET_TYPE = ? AND TARGET_ID = ? ORDER BY HIST_AT, HIST_ID",
                rs -> {
                    rows.add(new Object[] {Times.read(rs, "HIST_AT"), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6)});
                }, targetType, targetId);
        Map<String, String> names = names(rows.stream().map(r -> (String) r[1]).toList());
        return rows.stream().map(r -> new Hist((OffsetDateTime) r[0], ref(names, (String) r[1]), (String) r[2],
                (String) r[3], (String) r[4], (String) r[5])).toList();
    }

    // ── 목록 문자열 (콤마 구분 ID) ──

    public static List<String> csv(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(Arrays.stream(v.split(",")).map(String::trim)
                .filter(x -> !x.isEmpty()).toList()));
    }

    public static String csv(Collection<String> v) {
        if (v == null || v.isEmpty()) {
            return null;
        }
        return String.join(",", new LinkedHashSet<>(v.stream().filter(x -> x != null && !x.isBlank())
                .map(String::trim).toList()));
    }

    public static ApiException validation(String field, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.", Map.of(field, message));
    }

    public static String required(String field, String v, int max) {
        if (v == null || v.isBlank()) {
            throw validation(field, "필수 입력입니다.");
        }
        if (v.length() > max) {
            throw validation(field, max + "자 이하로 입력해주세요.");
        }
        return v.trim();
    }

    public static String optional(String field, String v, int max) {
        if (v == null || v.isBlank()) {
            return null;
        }
        if (v.length() > max) {
            throw validation(field, max + "자 이하로 입력해주세요.");
        }
        return v.trim();
    }

    public static String oneOf(String field, String v, String def, String... allowed) {
        if (v == null || v.isBlank()) {
            if (def == null) {
                throw validation(field, "필수 입력입니다.");
            }
            return def;
        }
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        throw validation(field, "허용 값: " + String.join(", ", allowed));
    }
}
