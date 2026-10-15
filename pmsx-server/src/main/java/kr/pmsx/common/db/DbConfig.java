package kr.pmsx.common.db;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;

/**
 * 저장소 DB 벤더 판별·검사, 벤더별 Flyway 위치, 방언 번역 JdbcTemplate.
 *
 * <ul>
 *   <li>pmsx.db.vendor: auto(기본, JDBC 제품명으로 판별) | postgresql | h2 | oracle | tibero | mssql</li>
 *   <li>pmsx.db.legacy-paging: Oracle 11g·Tibero 용 ROWNUM 페이징 (Tibero 기본 true)</li>
 *   <li>Flyway: H2·PostgreSQL = common + 벤더 폴더, Oracle = oracle, SQL Server = sqlserver.
 *       Tibero 는 Flyway 미지원이라 Flyway 를 건너뛰고 {@link SimpleSqlMigrator} 가 oracle 폴더를 실행한다.</li>
 * </ul>
 */
@Configuration
public class DbConfig {

    private static final Logger log = LoggerFactory.getLogger(DbConfig.class);

    @Bean
    SqlDialect sqlDialect(DataSource dataSource, @Value("${pmsx.db.vendor:auto}") String vendorSetting,
            @Value("${pmsx.db.legacy-paging:}") String legacySetting,
            @Value("${pmsx.db.allow-case-insensitive:false}") boolean allowCaseInsensitive) {
        String product;
        try (Connection c = dataSource.getConnection()) {
            product = c.getMetaData().getDatabaseProductName();
            DbVendor vendor = DbVendor.of(vendorSetting, product);
            boolean legacy = legacySetting == null || legacySetting.isBlank() ? vendor == DbVendor.TIBERO
                    : Boolean.parseBoolean(legacySetting.trim());
            boolean emulated = product != null && product.toLowerCase(Locale.ROOT).contains("h2")
                    && vendor != DbVendor.H2;
            if (!emulated) {
                check(c, vendor, allowCaseInsensitive);
            }
            log.info("저장소 DB: {} (JDBC 제품명 {}){}{}", vendor, product, legacy ? ", ROWNUM 페이징" : "",
                    emulated ? ", H2 호환 모드(테스트)" : "");
            return new SqlDialect(vendor, legacy, emulated);
        } catch (SQLException e) {
            throw new IllegalStateException("저장소 DB 에 접속할 수 없습니다: " + e.getMessage(), e);
        }
    }

    /** 벤더별 필수 설정 검사 */
    private static void check(Connection c, DbVendor vendor, boolean allowCaseInsensitive) {
        try (Statement st = c.createStatement()) {
            if (vendor == DbVendor.MSSQL) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT CONVERT(NVARCHAR(128), DATABASEPROPERTYEX(DB_NAME(), 'Collation'))")) {
                    String collation = rs.next() ? rs.getString(1) : "";
                    if (collation != null && collation.toUpperCase(Locale.ROOT).contains("_CI_")
                            && !allowCaseInsensitive) {
                        throw new IllegalStateException("SQL Server 데이터베이스 데이터 정렬(" + collation
                                + ")이 대소문자를 구분하지 않습니다. 수집 키(함수명·경로)는 대소문자를 구분하므로 "
                                + "Korean_100_CS_AS 같은 _CS_ 데이터 정렬로 DB 를 만들어야 합니다 "
                                + "(설치가이드 '저장소 DB 별 설정' 참고, 강제 기동: pmsx.db.allow-case-insensitive=true).");
                    }
                }
            } else if (vendor.oracleLike()) {
                try (ResultSet rs = st.executeQuery(
                        "SELECT VALUE FROM NLS_DATABASE_PARAMETERS WHERE PARAMETER = 'NLS_CHARACTERSET'")) {
                    String cs = rs.next() ? rs.getString(1) : "";
                    if (cs != null && !cs.toUpperCase(Locale.ROOT).contains("UTF8")) {
                        log.warn("DB 문자셋이 {} 입니다. 한글 외 문자(이모지·특수문자) 저장을 위해 AL32UTF8 을 권장합니다.", cs);
                    }
                } catch (SQLException e) {
                    log.debug("문자셋 확인 생략: {}", e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.warn("DB 설정 검사 실패(무시): {}", e.getMessage());
        }
    }

    /** 벤더별 마이그레이션 위치·placeholder. */
    @Bean
    FlywayConfigurationCustomizer pmsxFlywayLocations(SqlDialect dialect, DataSource dataSource) {
        return conf -> {
            DbVendor v = dialect.vendor();
            List<String> loc = new ArrayList<>();
            if (v == DbVendor.H2 || v == DbVendor.POSTGRESQL) {
                loc.add("classpath:db/migration/common");
            }
            loc.add("classpath:db/migration/" + v.migrationFolder());
            conf.locations(loc.toArray(String[]::new));
            boolean h2 = isH2(dataSource);
            // sqlserver 스크립트의 ${tstz}·${bit}: 실 SQL Server = DATETIMEOFFSET·BIT, H2 MSSQLServer 모드(테스트) = H2 대응 타입
            conf.placeholders(Map.of("tstz", h2 ? "TIMESTAMP WITH TIME ZONE" : "DATETIMEOFFSET",
                    "bit", h2 ? "TINYINT" : "BIT"));
        };
    }

    /** Flyway 가 모르는 DB(Tibero)·내장 실행기 사용 시에는 Flyway 를 건너뛴다(jdbcTemplate 빈에서 SimpleSqlMigrator 실행). */
    @Bean
    FlywayMigrationStrategy pmsxFlywayStrategy(SqlDialect dialect, @Value("${pmsx.db.migrator:auto}") String migrator) {
        boolean simple = useSimpleMigrator(dialect, migrator);
        return flyway -> {
            if (simple) {
                log.info("Flyway 생략 — 내장 마이그레이션 실행기 사용 ({})", dialect.vendor());
            } else {
                flyway.migrate();
            }
        };
    }

    static boolean useSimpleMigrator(SqlDialect dialect, String migrator) {
        return "simple".equalsIgnoreCase(migrator)
                || ("auto".equalsIgnoreCase(migrator) && dialect.vendor() == DbVendor.TIBERO);
    }

    private static boolean isH2(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            return c.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2");
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 서버 전체가 쓰는 JdbcTemplate. Oracle·Tibero·SQL Server 는 SQL 을 번역하는 DataSource 를 거친다
     * (트랜잭션 연결을 그대로 쓰도록 TransactionAwareDataSourceProxy 위에 얹는다).
     */
    @Bean
    @Primary
    JdbcTemplate jdbcTemplate(DataSource dataSource, SqlDialect dialect,
            @Value("${pmsx.db.migrator:auto}") String migrator) {
        if (useSimpleMigrator(dialect, migrator)) {
            int n = new SimpleSqlMigrator(dataSource, dialect.vendor().migrationFolder()).migrate();
            log.info("DB 마이그레이션(내장 실행기): {}건 적용", n);
        }
        if (!dialect.vendor().needsTranslation()) {
            return new JdbcTemplate(dataSource);
        }
        return new JdbcTemplate(new DialectDataSource(new TransactionAwareDataSourceProxy(dataSource),
                dialect.translator()));
    }
}
