package kr.pmsx.cm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.List;
import kr.pmsx.common.PmsxProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 운영 설치 최초 기동 시 관리자 계정 생성 (시드를 쓰지 않는 prod/postgres 프로필).
 * 사용자가 한 명도 없을 때만 admin 을 만들고, 비밀번호는 PMSX_ADMIN_PASSWORD 환경 변수 또는
 * 무작위 생성 후 ${PMSX_HOME}/run/initial-admin-password.txt 에 남긴다(로그인 후 변경·파일 삭제 권장).
 */
@Component
@Order(10)
public class InitialAdminCreator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InitialAdminCreator.class);
    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final JdbcTemplate jdbc;
    private final UserDao users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy policy;
    private final PmsxProperties props;

    public InitialAdminCreator(JdbcTemplate jdbc, UserDao users, PasswordEncoder encoder, PasswordPolicy policy,
            PmsxProperties props) {
        this.jdbc = jdbc;
        this.users = users;
        this.encoder = encoder;
        this.policy = policy;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (props.seed() != null && props.seed().enabled()) {
            return;   // 개발 시드(DataSeeder)가 계정을 만든다
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER", Integer.class);
        if (count != null && count > 0) {
            return;
        }
        String password = System.getenv("PMSX_ADMIN_PASSWORD");
        boolean generated = password == null || password.isBlank();
        if (generated) {
            password = randomPassword();
        }
        policy.check(password);
        users.insert("admin", encoder.encode(password), "시스템 관리자", List.of("SYS_ADMIN"));
        if (generated) {
            String home = System.getenv().getOrDefault("PMSX_HOME", ".");
            Path file = Path.of(home, "run", "initial-admin-password.txt");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "admin\n" + password + "\n");
            log.warn("최초 관리자 계정 admin 을 만들었습니다. 비밀번호 파일: {} (로그인 후 비밀번호 변경·파일 삭제)",
                    file.toAbsolutePath());
        } else {
            log.warn("최초 관리자 계정 admin 을 PMSX_ADMIN_PASSWORD 값으로 만들었습니다.");
        }
    }

    private static String randomPassword() {
        SecureRandom r = new SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            b.append(CHARS.charAt(r.nextInt(CHARS.length())));
        }
        return b.append("7!").toString();   // 숫자·특수문자 보장
    }
}
