package kr.pmsx.cm.notify;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 기한 임박(D-2 이내)·지연 알림 (docs/12 §7, 매일 08:00). 대상: 이슈 담당, WBS 말단 작업 담당, 액션아이템 담당, 산출물 담당.
 * 같은 사용자·이벤트·링크로 그날 이미 보냈으면 건너뛴다.
 */
@Component
public class DueScheduler {

    private static final Logger log = LoggerFactory.getLogger(DueScheduler.class);
    static final int DUE_SOON_DAYS = 2;

    private record Due(String userId, LocalDate due, String title, String link) {
    }

    private final JdbcTemplate jdbc;
    private final NotificationService notify;

    public DueScheduler(JdbcTemplate jdbc, NotificationService notify) {
        this.jdbc = jdbc;
        this.notify = notify;
    }

    @Scheduled(cron = "${pmsx.jobs.due-cron:0 0 8 * * *}", zone = "Asia/Seoul")
    public void daily() {
        int n = run(LocalDate.now(Times.ZONE));
        log.info("기한 알림 {}건 발송", n);
    }

    /** 기준일로 실행 (테스트용 공개). 보낸 알림 수 */
    public int run(LocalDate today) {
        List<Due> items = new ArrayList<>();
        jdbc.query("SELECT OWNER_ID, DUE_DT, ISSUE_NO || ' ' || TITLE, ISSUE_ID FROM QA_ISSUE"
                + " WHERE OWNER_ID IS NOT NULL AND DUE_DT IS NOT NULL AND STATUS IN ('OPEN', 'IN_PROGRESS')", rs -> {
            items.add(new Due(rs.getString(1), rs.getObject(2, LocalDate.class), "이슈 " + rs.getString(3),
                    "/quality/issues/" + rs.getString(4)));
        });
        jdbc.query("""
                SELECT A.USER_ID, W.PLAN_END, W.WBS_NO || ' ' || W.NAME, W.PROJECT_ID, W.WBS_ID FROM PJ_WBS W
                  JOIN RS_ASSIGN A ON A.TARGET_TYPE = 'WBS' AND A.TARGET_ID = W.WBS_ID
                 WHERE W.PLAN_END IS NOT NULL AND W.STATUS <> 'DONE'
                   AND NOT EXISTS (SELECT 1 FROM PJ_WBS C WHERE C.PARENT_ID = W.WBS_ID)""", rs -> {
            items.add(new Due(rs.getString(1), rs.getObject(2, LocalDate.class), "작업 " + rs.getString(3),
                    "/projects/" + rs.getString(4) + "/wbs?focus=" + rs.getString(5)));
        });
        jdbc.query("SELECT OWNER_ID, DUE_DT, CONTENT, MEETING_ID FROM QA_ACTION_ITEM"
                + " WHERE OWNER_ID IS NOT NULL AND DUE_DT IS NOT NULL AND DONE = FALSE", rs -> {
            items.add(new Due(rs.getString(1), rs.getObject(2, LocalDate.class), "액션아이템 " + rs.getString(3),
                    "/quality/meetings/" + rs.getString(4)));
        });
        jdbc.query("SELECT OWNER_ID, DUE_DT, NAME, DELIVERABLE_ID FROM DL_DELIVERABLE"
                + " WHERE OWNER_ID IS NOT NULL AND DUE_DT IS NOT NULL AND STATUS IN ('DRAFT', 'REJECTED')", rs -> {
            items.add(new Due(rs.getString(1), rs.getObject(2, LocalDate.class), "산출물 " + rs.getString(3),
                    "/deliverables?focus=" + rs.getString(4)));
        });
        int sent = 0;
        for (Due d : items) {
            Event ev;
            String title;
            if (d.due().isBefore(today)) {
                ev = Event.OVERDUE;
                title = "기한 지연: " + d.title() + " (" + d.due() + ")";
            } else if (!d.due().isAfter(today.plusDays(DUE_SOON_DAYS))) {
                ev = Event.DUE_SOON;
                title = "기한 임박: " + d.title() + " (" + d.due() + ")";
            } else {
                continue;
            }
            if (notify.sentToday(d.userId(), ev, d.link())) {
                continue;
            }
            notify.notify(d.userId(), ev, title, null, d.link());
            sent++;
        }
        return sent;
    }
}
