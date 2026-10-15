package kr.pmsx.cm;

import java.time.LocalDate;
import java.util.List;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 개발용 시드 데이터 (08 명세 §1, §2). admin 계정이 이미 있으면 아무것도 하지 않는다.
 * - admin / pmsx1234! : SYS_ADMIN + 샘플 프로젝트 PM (분석 상태 변경 등 시연용)
 * - analyst / pmsx1234! : 샘플 프로젝트 ANALYST
 */
@Component
@org.springframework.core.annotation.Order(1)
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    public static final String SEED_PASSWORD = "pmsx1234!";

    private final JdbcTemplate jdbc;
    private final UserDao users;
    private final PasswordEncoder encoder;
    private final PmsxProperties props;

    public DataSeeder(JdbcTemplate jdbc, UserDao users, PasswordEncoder encoder, PmsxProperties props) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (props.seed() == null || !props.seed().enabled() || users.findByLoginId("admin").isPresent()) {
            return;
        }
        String hash = encoder.encode(SEED_PASSWORD);
        String adminId = users.insert("admin", hash, "관리자", List.of("SYS_ADMIN"));
        String analystId = users.insert("analyst", hash, "김분석", List.of());

        String projectId = Ulid.next();
        jdbc.update("""
                INSERT INTO PJ_PROJECT (PROJECT_ID, TYPE, NAME, CUSTOMER, START_DT, END_DT, CONTRACT_MM, CREATED_AT,
                                        CREATED_BY)
                VALUES (?, 'SI', '○○공사 차세대 SI', '○○공사', ?, ?, 120, ?, ?)""",
                projectId, LocalDate.of(2026, 11, 2), LocalDate.of(2027, 10, 29), Times.now(), adminId);
        jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, 'PM', ?)",
                projectId, adminId, LocalDate.of(2026, 11, 2));
        jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, 'ANALYST', ?)",
                projectId, analystId, LocalDate.of(2026, 11, 2));

        jdbc.update("""
                INSERT INTO AS_SYSTEM (SYSTEM_ID, PROJECT_ID, CODE, NAME, UI_TECH, SERVER_TECH, DB_TYPE, TEST_URL,
                                       CREATED_AT, CREATED_BY)
                VALUES (?, ?, 'HR', '인사시스템', 'jsp-jquery', 'spring-mybatis', 'oracle', 'http://localhost:8091', ?, ?)""",
                Ulid.next(), projectId, Times.now(), adminId);
        log.info("개발용 시드 데이터 생성: admin / analyst (비밀번호 {}), 프로젝트 '○○공사 차세대 SI', 시스템 HR", SEED_PASSWORD);
    }
}
