package kr.pmsx.cm.notify;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.pmsx.cm.notify.NotificationService.Settings;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 알림 목록·읽음·설정 (docs/12 §7). 본인 알림만. */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    public record Notification(String notificationId, String event, String title, String body, String link,
                               boolean read, OffsetDateTime createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final NotificationService service;

    public NotificationController(JdbcTemplate jdbc, NotificationService service) {
        this.jdbc = jdbc;
        this.service = service;
    }

    @GetMapping
    public PageDto<Notification> list(@AuthenticationPrincipal AuthUser user,
            @RequestParam(required = false) Boolean unread, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        int[] ps = PageDto.normalize(page, size);
        String where = " WHERE USER_ID = ?" + (Boolean.TRUE.equals(unread) ? " AND READ_YN = FALSE" : "");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM CM_NOTIFICATION" + where, Long.class, user.userId());
        List<Notification> content = new ArrayList<>(jdbc.query("""
                SELECT NOTIFICATION_ID, EVENT, TITLE, BODY, LINK, READ_YN, CREATED_AT FROM CM_NOTIFICATION""" + where
                        + " ORDER BY CREATED_AT DESC, NOTIFICATION_ID DESC LIMIT ? OFFSET ?",
                (rs, i) -> new Notification(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getBoolean(6), Times.read(rs, "CREATED_AT")),
                user.userId(), ps[1], (long) ps[0] * ps[1]));
        return new PageDto<>(content, ps[0], ps[1], total == null ? 0 : total);
    }

    @PostMapping("/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal AuthUser user, @PathVariable String notificationId) {
        int n = jdbc.update("UPDATE CM_NOTIFICATION SET READ_YN = TRUE WHERE NOTIFICATION_ID = ? AND USER_ID = ?",
                notificationId, user.userId());
        if (n == 0) {
            throw ApiException.notFound("알림");
        }
    }

    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll(@AuthenticationPrincipal AuthUser user) {
        jdbc.update("UPDATE CM_NOTIFICATION SET READ_YN = TRUE WHERE USER_ID = ? AND READ_YN = FALSE", user.userId());
    }

    @GetMapping("/settings")
    public Settings settings(@AuthenticationPrincipal AuthUser user) {
        return service.settings(user.userId());
    }

    @PutMapping("/settings")
    public Settings save(@AuthenticationPrincipal AuthUser user, @RequestBody Settings req) {
        return service.saveSettings(user.userId(), req);
    }
}
