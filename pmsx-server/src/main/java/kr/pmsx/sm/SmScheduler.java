package kr.pmsx.sm;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.cm.notify.NotificationService;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.Times;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.sm.SlaEngine.SrSla;
import kr.pmsx.sm.SrService.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SM 알림 스케줄러 (docs/13 §1·§5).
 * <ul>
 *   <li>매분: 진행 중 SR 의 응답·해결 임박(DUE_SOON 이벤트)·위반(OVERDUE 이벤트)을 단계별 1회씩 담당자(없으면 PM·PL)에게</li>
 *   <li>매일 08:05: 운영 자산 만료 30·7·1일 전(DUE_SOON), 만료일 경과(OVERDUE) — 자산 담당(없으면 PM·PL)</li>
 * </ul>
 */
@Component
public class SmScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmScheduler.class);

    private final JdbcTemplate jdbc;
    private final SrService srs;
    private final NotificationService notify;

    public SmScheduler(JdbcTemplate jdbc, SrService srs, NotificationService notify) {
        this.jdbc = jdbc;
        this.srs = srs;
        this.notify = notify;
    }

    @Scheduled(cron = "${pmsx.jobs.sla-cron:0 * * * * *}", zone = "Asia/Seoul")
    public void everyMinute() {
        try {
            int n = slaAlerts(Times.now());
            if (n > 0) {
                log.info("SLA 알림 {}건", n);
            }
        } catch (RuntimeException e) {
            log.warn("SLA 알림 실패: {}", e.getMessage());
        }
    }

    /** 기준 시각으로 실행 (테스트용 공개). 보낸 알림 묶음 수 */
    public int slaAlerts(OffsetDateTime now) {
        List<Row> open = srs.rows("R.STATUS NOT IN ('RESOLVED', 'CONFIRMED', 'CLOSED', 'REJECTED')"
                + " AND R.POLICY_ID IS NOT NULL ORDER BY R.CREATED_AT");
        if (open.isEmpty()) {
            return 0;
        }
        Map<String, Set<String>> flags = new java.util.HashMap<>();
        jdbc.query("SELECT SR_ID, NOTIFY_FLAGS FROM SM_SR WHERE STATUS NOT IN ('RESOLVED', 'CONFIRMED', 'CLOSED',"
                + " 'REJECTED') AND NOTIFY_FLAGS IS NOT NULL", rs -> {
                    flags.put(rs.getString(1), new LinkedHashSet<>(PjSupport.csv(rs.getString(2))));
                });
        Map<String, SrSla> s = srs.sla().evaluator(now).evaluate(open.stream().map(Row::clock).toList());
        int sent = 0;
        for (Row r : open) {
            SrSla x = s.get(r.srId());
            if (x == null || "PAUSED".equals(x.state())) {
                continue;
            }
            String phase = r.respondedAt() == null ? "RESP" : "RES";
            String key;
            Event event;
            String title;
            if ("BREACHED".equals(x.state())) {
                key = (x.responseBreached() && r.respondedAt() == null ? "RESP" : "RES") + "_BREACH";
                event = Event.OVERDUE;
                title = "SLA 위반: " + r.srNo() + " " + r.title();
            } else if ("DUE_SOON".equals(x.state())) {
                key = phase + "_SOON";
                event = Event.DUE_SOON;
                title = "SLA 임박(" + x.remainingMinutes() + "분 남음): " + r.srNo() + " " + r.title();
            } else {
                continue;
            }
            Set<String> f = flags.computeIfAbsent(r.srId(), k -> new LinkedHashSet<>());
            if (f.contains(key)) {
                continue;
            }
            f.add(key);
            jdbc.update("UPDATE SM_SR SET NOTIFY_FLAGS = ? WHERE SR_ID = ?", PjSupport.csv(f), r.srId());
            List<String> to = new ArrayList<>();
            if (r.assignee() != null) {
                to.add(r.assignee().userId());
            }
            if (r.assignee() == null || event == Event.OVERDUE) {
                to.addAll(srs.managers(r.projectId()));
            }
            notify.notifyAll(to, event, title, "응답 기한 " + x.responseDue() + " / 해결 기한 " + x.resolveDue(),
                    SrService.link(r.srId()));
            sent++;
        }
        return sent;
    }

    @Scheduled(cron = "${pmsx.jobs.asset-cron:0 5 8 * * *}", zone = "Asia/Seoul")
    public void daily() {
        int n = assetAlerts(LocalDate.now(Times.ZONE));
        log.info("운영 자산 만료 알림 {}건", n);
    }

    /** 만료 단계: 1 = 30일 이내, 2 = 7일 이내, 3 = 1일 이내, 4 = 만료일 경과. 단계가 올라갈 때만 1회 */
    public int assetAlerts(LocalDate today) {
        record A(String id, String projectId, String name, String type, String ownerId, LocalDate exp, int stage) {
        }
        List<A> list = jdbc.query("SELECT ASSET_ID, PROJECT_ID, NAME, TYPE, OWNER_ID, EXPIRES_AT, NOTIFIED_STAGE"
                        + " FROM SM_OPS_ASSET WHERE EXPIRES_AT IS NOT NULL AND EXPIRES_AT <= ?",
                (rs, i) -> new A(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getInt(7)), today.plusDays(30));
        int sent = 0;
        for (A a : list) {
            long left = ChronoUnit.DAYS.between(today, a.exp());
            int stage = left < 0 ? 4 : left <= 1 ? 3 : left <= 7 ? 2 : 1;
            if (stage <= a.stage()) {
                continue;
            }
            jdbc.update("UPDATE SM_OPS_ASSET SET NOTIFIED_STAGE = ? WHERE ASSET_ID = ?", stage, a.id());
            List<String> to = a.ownerId() != null ? List.of(a.ownerId()) : srs.managers(a.projectId());
            String title = left < 0 ? "운영 자산 만료됨: " + a.name() + " (" + a.exp() + ")"
                    : "운영 자산 만료 D-" + left + ": " + a.name() + " (" + a.exp() + ")";
            notify.notifyAll(to, left < 0 ? Event.OVERDUE : Event.DUE_SOON, title, a.type(), "/sm/ops-assets?focus="
                    + a.id());
            sent++;
        }
        return sent;
    }
}
