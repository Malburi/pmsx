package kr.pmsx;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 저장소 DB 멀티벤더 검증: 기존 통합 시나리오를 H2 호환 모드 + 벤더 DDL(db/migration/{oracle,sqlserver})
 * + SQL 번역 계층으로 다시 실행한다. 실제 Oracle·Tibero·SQL Server 와의 차이는 docs/설치가이드에 명시.
 * <ul>
 *   <li>Oracle 모드: 빈 문자열=NULL, NUMBER(1) 불리언 비교 엄격 → Oracle 고유 문제를 잡는다</li>
 *   <li>Tibero 모드: Oracle 모드 + 내장 마이그레이션 실행기 + ROWNUM 페이징</li>
 *   <li>SQL Server 모드: 번역(+, N'', OFFSET/FETCH, LEN…)·sqlserver DDL 경로 검증. 실 SQL Server 재생은 MssqlReplay</li>
 * </ul>
 */
final class VendorModes {

    private VendorModes() {
    }

    static void oracle(DynamicPropertyRegistry r, String name) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:pmsx-ora-" + name + ";MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("pmsx.db.vendor", () -> "oracle");
        r.add("pmsx.storage.dir", () -> "./build/test-files-ora-" + name);
        kr.pmsx.common.db.SqlCaptureFile.startIfRequested("oracle", name);
    }

    static void tibero(DynamicPropertyRegistry r, String name) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:pmsx-tib-" + name + ";MODE=Oracle;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("pmsx.db.vendor", () -> "tibero");
        r.add("pmsx.storage.dir", () -> "./build/test-files-tib-" + name);
        kr.pmsx.common.db.SqlCaptureFile.startIfRequested("tibero", name);
    }

    static void mssql(DynamicPropertyRegistry r, String name) {
        r.add("spring.datasource.url",
                () -> "jdbc:h2:mem:pmsx-ms-" + name + ";MODE=MSSQLServer;DEFAULT_NULL_ORDERING=LOW;DB_CLOSE_DELAY=-1");
        r.add("pmsx.db.vendor", () -> "mssql");
        r.add("pmsx.storage.dir", () -> "./build/test-files-ms-" + name);
        kr.pmsx.common.db.SqlCaptureFile.startIfRequested("mssql", name);     // ./gradlew sqlCapture 일 때만
    }
}

// ───────── Oracle (H2 MODE=Oracle) ─────────
class OracleAsIsFlowTest extends AsIsFlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "asis");
    }
}

class OracleAsIsR1Test extends AsIsR1Test {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "r1");
    }
}

class OracleR2FlowTest extends R2FlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "r2");
    }
}

class OracleSmFlowTest extends SmFlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "sm");
    }
}

class OracleCommonApiTest extends CommonApiTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "common");
    }
}

class OracleExcelApiTest extends ExcelApiTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "excel");
    }
}

class OracleAuthApiTest extends AuthApiTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "auth");
    }
}

class OracleIngestV2Test extends IngestV2Test {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "ingest");
    }
}

class OracleParamValidationTest extends ParamValidationTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "param");
    }
}

// ───────── Tibero (H2 MODE=Oracle + 내장 실행기 + ROWNUM) ─────────
class TiberoR2FlowTest extends R2FlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.tibero(r, "r2");
    }
}

class TiberoSmFlowTest extends SmFlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.tibero(r, "sm");
    }
}

class TiberoAsIsR1Test extends AsIsR1Test {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.tibero(r, "r1");
    }
}

// ───────── SQL Server (H2 MODE=MSSQLServer) ─────────
class MssqlAsIsFlowTest extends AsIsFlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.mssql(r, "asis");
    }
}

class MssqlAsIsR1Test extends AsIsR1Test {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.mssql(r, "r1");
    }
}

class MssqlR2FlowTest extends R2FlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.mssql(r, "r2");
    }
}

class MssqlSmFlowTest extends SmFlowTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.mssql(r, "sm");
    }
}

class MssqlCommonApiTest extends CommonApiTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.mssql(r, "common");
    }
}

class MssqlExcelApiTest extends ExcelApiTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.mssql(r, "excel");
    }
}

// ───────── 데모 시드(DemoSeeder·SmDemoSeeder·인수인계) — 벤더별 ─────────
class OracleDemoSeedTest extends DemoSeedTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.oracle(r, "demo");
    }
}

class TiberoDemoSeedTest extends DemoSeedTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.tibero(r, "demo");
    }
}

class MssqlDemoSeedTest extends DemoSeedTest {
    @DynamicPropertySource
    static void vendor(DynamicPropertyRegistry r) {
        VendorModes.mssql(r, "demo");
    }
}
