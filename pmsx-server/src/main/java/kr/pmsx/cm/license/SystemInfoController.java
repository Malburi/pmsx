package kr.pmsx.cm.license;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import kr.pmsx.cm.license.LicenseService.LicenseInfo;
import kr.pmsx.cm.license.LicenseService.LicenseStatus;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.security.AuthUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 라이선스·시스템 정보 (docs/10 §6) */
@RestController
@RequestMapping("/api/v1/system")
public class SystemInfoController {

    public record Users(long total, long active) {
    }

    public record SystemInfo(String version, Instant buildTime, String profile, String db, String javaVersion,
                             Users users, LicenseInfo license) {
    }

    public record LicenseRequest(@NotBlank(message = "라이선스 키를 입력해주세요.") String key) {
    }

    private final LicenseService license;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;
    private final Environment env;
    private final ObjectProvider<BuildProperties> build;
    private final AuditService audit;

    public SystemInfoController(LicenseService license, JdbcTemplate jdbc, DataSource dataSource, Environment env,
            ObjectProvider<BuildProperties> build, AuditService audit) {
        this.license = license;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
        this.env = env;
        this.build = build;
        this.audit = audit;
    }

    @GetMapping("/license-status")
    public LicenseStatus licenseStatus() {
        return license.status();
    }

    @GetMapping("/info")
    public SystemInfo info(@AuthenticationPrincipal AuthUser user) {
        BuildProperties b = build.getIfAvailable();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER", Long.class);
        Long active = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER WHERE ENABLED = TRUE AND LOCKED = FALSE",
                Long.class);
        LicenseInfo info = license.current();
        if (info != null && !user.isSysAdmin()) {
            info = new LicenseInfo(null, null, null, 0, List.of(), info.valid(), null);
        }
        return new SystemInfo(b == null ? "dev" : b.getVersion(), b == null ? null : b.getTime(),
                String.join(",", env.getActiveProfiles().length == 0 ? env.getDefaultProfiles()
                        : env.getActiveProfiles()),
                dbName(), System.getProperty("java.version"),
                new Users(total == null ? 0 : total, active == null ? 0 : active), info);
    }

    @PostMapping("/license")
    public LicenseInfo apply(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody LicenseRequest req) {
        if (!user.isSysAdmin()) {
            throw ApiException.forbidden();
        }
        LicenseInfo info = license.apply(req.key(), user.userId());
        audit.log(AuditService.Action.LICENSE, "license", "고객사=" + info.customer() + ", 만료=" + info.expiresAt());
        return info;
    }

    private String dbName() {
        try (Connection c = dataSource.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            return md.getDatabaseProductName() + " " + md.getDatabaseProductVersion();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
