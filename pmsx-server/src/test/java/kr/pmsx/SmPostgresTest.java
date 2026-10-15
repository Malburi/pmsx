package kr.pmsx;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** R3 SM 시나리오 전체를 실제 PostgreSQL(내장 바이너리)에서 다시 실행. */
class SmPostgresTest extends SmFlowTest {

    static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new IllegalStateException("내장 PostgreSQL 기동 실패", e);
        }
    }

    @AfterAll
    static void stop() throws IOException {
        PG.close();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("pmsx.storage.dir", () -> "./build/test-files-pg-r3");
    }
}
