package kr.pmsx.cm.notify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 알림 (FR-CM-007, docs/12 §7). 인앱은 항상 기록, 메일(spring.mail 설정 시, 없으면 로그만)·웹훅(pmsx.notification.webhook-url)은
 * 사용자 설정에 따라 커밋 후 비동기로 보낸다. 사용자가 끈 이벤트는 어떤 채널로도 보내지 않는다.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public enum Event {
        ASSIGNED, REVIEW_REQUESTED, APPROVED, REJECTED, DUE_SOON, OVERDUE, DEFECT_ASSIGNED, ISSUE_ESCALATED,
        TIMESHEET_REJECTED, MENTION
    }

    public record Settings(boolean email, boolean webhook, Map<String, Boolean> events) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final PmsxProperties props;
    private final ObjectProvider<JavaMailSender> mail;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "pmsx-notify");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public NotificationService(JdbcTemplate jdbc, ObjectMapper om, PmsxProperties props,
            ObjectProvider<JavaMailSender> mail) {
        this.jdbc = jdbc;
        this.om = om;
        this.props = props;
        this.mail = mail;
    }

    public void notify(String userId, Event event, String title, String body, String link) {
        if (userId == null) {
            return;
        }
        notifyAll(java.util.List.of(userId), event, title, body, link);
    }

    /** 같은 내용을 여러 사용자에게 (중복 사용자 1회) */
    public void notifyAll(Collection<String> userIds, Event event, String title, String body, String link) {
        for (String userId : new LinkedHashSet<>(userIds)) {
            if (userId == null) {
                continue;
            }
            Settings s = settings(userId);
            if (!s.events().getOrDefault(event.name(), true)) {
                continue;
            }
            jdbc.update("""
                    INSERT INTO CM_NOTIFICATION (NOTIFICATION_ID, USER_ID, EVENT, TITLE, BODY, LINK, READ_YN, CREATED_AT)
                    VALUES (?, ?, ?, ?, ?, ?, FALSE, ?)""", Ulid.next(), userId, event.name(), cut(title, 300),
                    cut(body, 2000), cut(link, 500), Times.now());
            String email = null;
            if (s.email()) {
                java.util.List<String> emails = jdbc.queryForList("SELECT EMAIL FROM CM_USER WHERE USER_ID = ?",
                        String.class, userId);
                email = emails.isEmpty() || emails.get(0) == null || emails.get(0).isBlank() ? null : emails.get(0);
            }
            boolean webhook = s.webhook() && props.webhookUrl() != null;
            if (email != null || webhook) {
                String to = email;
                afterCommit(() -> executor.submit(() -> deliver(to, webhook, title, body, link)));
            }
        }
    }

    /** 오늘 같은 사용자·이벤트·링크 알림이 이미 있으면 true (스케줄러 중복 방지) */
    public boolean sentToday(String userId, Event event, String link) {
        LocalDate today = LocalDate.now(Times.ZONE);
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM CM_NOTIFICATION WHERE USER_ID = ? AND EVENT = ? AND LINK = ? AND CREATED_AT >= ?""",
                Integer.class, userId, event.name(), link, today.atStartOfDay(Times.ZONE).toOffsetDateTime());
        return n != null && n > 0;
    }

    public Settings settings(String userId) {
        return jdbc.query("SELECT EMAIL_YN, WEBHOOK_YN, EVENTS_JSON FROM CM_NOTIFY_SETTING WHERE USER_ID = ?",
                (rs, i) -> new Settings(rs.getBoolean(1), rs.getBoolean(2), events(rs.getString(3))), userId)
                .stream().findFirst().orElse(new Settings(true, true, events(null)));
    }

    public Settings saveSettings(String userId, Settings s) {
        Map<String, Boolean> ev = events(null);
        if (s.events() != null) {
            s.events().forEach((k, v) -> {
                if (ev.containsKey(k) && v != null) {
                    ev.put(k, v);
                }
            });
        }
        String json;
        try {
            json = om.writeValueAsString(ev);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        int n = jdbc.update("UPDATE CM_NOTIFY_SETTING SET EMAIL_YN = ?, WEBHOOK_YN = ?, EVENTS_JSON = ? WHERE USER_ID = ?",
                s.email(), s.webhook(), json, userId);
        if (n == 0) {
            jdbc.update("INSERT INTO CM_NOTIFY_SETTING (USER_ID, EMAIL_YN, WEBHOOK_YN, EVENTS_JSON) VALUES (?, ?, ?, ?)",
                    userId, s.email(), s.webhook(), json);
        }
        return settings(userId);
    }

    private Map<String, Boolean> events(String json) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Event e : Event.values()) {
            out.put(e.name(), true);
        }
        if (json != null && !json.isBlank()) {
            try {
                Map<String, Boolean> m = om.readValue(json, new TypeReference<Map<String, Boolean>>() { });
                m.forEach((k, v) -> {
                    if (out.containsKey(k) && v != null) {
                        out.put(k, v);
                    }
                });
            } catch (Exception ignore) {
                // 손상된 설정은 기본값
            }
        }
        return out;
    }

    private void deliver(String email, boolean webhook, String title, String body, String link) {
        if (email != null) {
            JavaMailSender sender = mail.getIfAvailable();
            if (sender == null) {
                log.info("[알림 메일 미설정] to={} title={}", email, title);
            } else {
                try {
                    SimpleMailMessage m = new SimpleMailMessage();
                    m.setFrom(props.mailFrom());
                    m.setTo(email);
                    m.setSubject("[PMS-X] " + title);
                    m.setText((body == null ? "" : body) + (link == null ? "" : "\n\n" + link));
                    sender.send(m);
                } catch (RuntimeException e) {
                    log.warn("알림 메일 발송 실패: {}", e.getMessage());
                }
            }
        }
        if (webhook && props.webhookUrl() != null) {
            try {
                Map<String, String> payload = new LinkedHashMap<>();
                payload.put("title", title);
                payload.put("body", body);
                payload.put("link", link);
                HttpRequest req = HttpRequest.newBuilder(URI.create(props.webhookUrl()))
                        .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json; charset=UTF-8")
                        .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(payload))).build();
                HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() >= 300) {
                    log.warn("알림 웹훅 응답 {}", res.statusCode());
                }
            } catch (Exception e) {
                log.warn("알림 웹훅 발송 실패: {}", e.getMessage());
            }
        }
    }

    private static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    r.run();
                }
            });
        } else {
            r.run();
        }
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
