package kr.pmsx.qa;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.pmsx.cm.notify.NotificationService;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 품질 공통: 코멘트(@멘션 알림), CUSTOMER 본인 등록 건 제한, 요소→화면 해석. */
@Component
public class QaSupport {

    public record Comment(String commentId, String content, UserRef author, OffsetDateTime createdAt) {
    }

    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9._-]{2,50})");

    private final JdbcTemplate jdbc;
    private final PjSupport support;
    private final NotificationService notify;

    public QaSupport(JdbcTemplate jdbc, PjSupport support, NotificationService notify) {
        this.jdbc = jdbc;
        this.support = support;
        this.notify = notify;
    }

    public List<Comment> comments(String targetType, String targetId) {
        List<Object[]> rows = jdbc.query("SELECT COMMENT_ID, CONTENT, AUTHOR_ID, CREATED_AT FROM QA_COMMENT"
                        + " WHERE TARGET_TYPE = ? AND TARGET_ID = ? ORDER BY CREATED_AT, COMMENT_ID",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        Times.read(rs, "CREATED_AT")}, targetType, targetId);
        Map<String, String> names = support.names(rows.stream().map(r -> (String) r[2]).toList());
        return rows.stream().map(r -> new Comment((String) r[0], (String) r[1], PjSupport.ref(names, (String) r[2]),
                (OffsetDateTime) r[3])).toList();
    }

    /** 코멘트 저장 + @loginId 멘션 알림(같은 프로젝트 멤버만) */
    public Comment addComment(String projectId, String targetType, String targetId, String content, String link,
            String title, AuthUser user) {
        String c = PjSupport.required("content", content, 20_000);
        String id = Ulid.next();
        jdbc.update("INSERT INTO QA_COMMENT (COMMENT_ID, TARGET_TYPE, TARGET_ID, CONTENT, AUTHOR_ID, CREATED_AT)"
                + " VALUES (?, ?, ?, ?, ?, ?)", id, targetType, targetId, c, user.userId(), Times.now());
        Set<String> mentioned = new LinkedHashSet<>();
        Matcher m = MENTION.matcher(c);
        while (m.find()) {
            mentioned.add(m.group(1));
        }
        List<String> targets = new ArrayList<>();
        for (String login : mentioned) {
            jdbc.query("SELECT U.USER_ID FROM CM_USER U JOIN PJ_MEMBER M ON M.USER_ID = U.USER_ID"
                    + " WHERE U.LOGIN_ID = ? AND M.PROJECT_ID = ?", rs -> {
                if (!rs.getString(1).equals(user.userId())) {
                    targets.add(rs.getString(1));
                }
            }, login, projectId);
        }
        notify.notifyAll(targets, Event.MENTION, user.name() + "님이 언급했습니다: " + title,
                c.length() > 200 ? c.substring(0, 200) + "…" : c, link);
        return comments(targetType, targetId).stream().filter(x -> x.commentId().equals(id)).findFirst().orElseThrow();
    }

    /** CUSTOMER 는 본인이 등록한 건만 수정 (docs/06 각주 5) */
    public static void requireOwnIfCustomer(ProjectAccess.Access a, AuthUser user, String createdBy) {
        if ("CUSTOMER".equals(a.projectRole()) && !user.userId().equals(createdBy)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인이 등록한 건만 수정할 수 있습니다.");
        }
    }

    /** 요소 ID → [화면 ID, 프로젝트 ID] (핫스팟 등록) */
    public String[] element(String elementId) {
        return jdbc.query("""
                SELECT E.SCREEN_ID, Y.PROJECT_ID FROM AS_ELEMENT E JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = E.SYSTEM_ID
                 WHERE E.ELEMENT_ID = ?""", (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, elementId)
                .stream().findFirst().orElseThrow(() -> PjSupport.validation("elementId", "요소를 찾을 수 없습니다."));
    }

    public String projectOfScreen(String screenId) {
        return jdbc.query("SELECT Y.PROJECT_ID FROM AS_SCREEN S JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID"
                + " WHERE S.SCREEN_ID = ?", (rs, i) -> rs.getString(1), screenId).stream().findFirst()
                .orElseThrow(() -> PjSupport.validation("screenId", "화면을 찾을 수 없습니다."));
    }

    public void requireMember(String field, String projectId, String userId) {
        if (userId == null) {
            return;
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                Integer.class, projectId, userId);
        if (n == null || n == 0) {
            throw PjSupport.validation(field, "프로젝트 멤버만 지정할 수 있습니다.");
        }
    }
}
