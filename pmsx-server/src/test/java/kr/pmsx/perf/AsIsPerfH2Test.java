package kr.pmsx.perf;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** H2 (MODE=PostgreSQL, 메모리) 성능 측정 */
class AsIsPerfH2Test extends AsIsPerfBase {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:pmsx-perf;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("pmsx.storage.dir", () -> "./build/test-files-perf");
    }

    @Override
    String dbName() {
        return "h2";
    }
}
