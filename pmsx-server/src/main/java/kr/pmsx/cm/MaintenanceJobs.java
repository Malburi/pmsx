package kr.pmsx.cm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 정기 정리 작업. 만료·폐기된 refresh 토큰을 매일 새벽 정리한다. */
@Component
public class MaintenanceJobs {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceJobs.class);

    private final AuthService auth;

    public MaintenanceJobs(AuthService auth) {
        this.auth = auth;
    }

    @Scheduled(cron = "${pmsx.jobs.token-cleanup-cron:0 30 3 * * *}", zone = "Asia/Seoul")
    public void purgeRefreshTokens() {
        int n = auth.purgeRefreshTokens();
        if (n > 0) {
            log.info("만료·폐기된 refresh 토큰 {}건 정리", n);
        }
    }
}
