package kr.pmsx;

import java.nio.file.Path;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.boot.context.ApplicationPidFileWriter;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// LDAP 은 LdapAuthenticator 가 설정(pmsx.auth.ldap)에 따라 직접 연결하므로 Boot 자동 구성은 끈다.
@SpringBootApplication(exclude = LdapAutoConfiguration.class)
@ConfigurationPropertiesScan
@EnableScheduling
public class PmsxServerApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(PmsxServerApplication.class);
        // 설치 패키지(bin/start)는 PMSX_HOME 을 지정한다 → stop 스크립트용 PID 파일
        String home = System.getenv("PMSX_HOME");
        if (home != null && !home.isBlank()) {
            app.addListeners(new ApplicationPidFileWriter(Path.of(home, "run", "pmsx.pid").toFile()));
        }
        app.run(args);
    }
}
