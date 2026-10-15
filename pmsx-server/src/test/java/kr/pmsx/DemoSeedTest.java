package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 시연용 데모 시드(demo·local 프로필): 데모 As-Is 패키지를 SI 프로젝트에 먼저 적재한 뒤 인수인계로 SM 프로젝트를 만든다.
 * → SI·SM 인사시스템 화면 수가 같고 0보다 크며, 시연용 분석 상태·메모·핫스팟 결함이 들어간다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pmsx-demo-seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "pmsx.storage.dir=./build/test-files-demo",
        "pmsx.seed.enabled=true",
        "pmsx.seed.demo=true",
        "pmsx.demo.asis-packages[0]=../samples/hr-legacy/out",
        "pmsx.demo.asis-packages[1]=../samples/hr-nexacro/out"})
@ActiveProfiles("test")
class DemoSeedTest {

    @Autowired
    JdbcTemplate jdbc;

    String project(String type) {
        return jdbc.queryForObject("SELECT PROJECT_ID FROM PJ_PROJECT WHERE TYPE = ? ORDER BY CREATED_AT LIMIT 1",
                String.class, type);
    }

    int screens(String projectId, String code) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM AS_SCREEN S JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID
                 WHERE Y.PROJECT_ID = ? AND Y.CODE = ?""", Integer.class, projectId, code);
        return n == null ? 0 : n;
    }

    @Test
    void 데모_As_Is_는_SI_에_먼저_적재되고_인수인계로_SM_에_복사된다() {
        boolean samples = Files.isDirectory(Path.of("../samples/hr-legacy/out"));
        org.junit.jupiter.api.Assumptions.assumeTrue(samples, "샘플 수집 결과가 없는 환경");

        String si = project("SI");
        String sm = project("SM");
        assertThat(sm).isNotNull();
        int siHr = screens(si, "HR");
        assertThat(siHr).isGreaterThan(0);
        assertThat(screens(sm, "HR")).isEqualTo(siHr);
        // HR 외 데모 시스템(급여 PY) 도 SI 에 만들어져 적재된다 (SM 인수인계 대상은 HR 만)
        assertThat(screens(si, "PY")).isGreaterThan(0);
        assertThat(screens(sm, "PY")).isZero();

        // 시연용 분석 상태: 확정 2·검토 1·작업 1
        List<String> statuses = jdbc.queryForList("""
                SELECT A.STATUS FROM AS_ANALYSIS A JOIN AS_SCREEN S ON S.SCREEN_ID = A.SCREEN_ID
                  JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID
                 WHERE Y.PROJECT_ID = ? AND Y.CODE = 'HR'""", String.class, si);
        assertThat(statuses.stream().filter("DONE"::equals).count()).isEqualTo(2);
        assertThat(statuses).contains("REVIEW", "WORKING");
        // 메모 2건(화면·저장 버튼) — SM 에도 복사
        Integer notes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM AS_NOTE N JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = N.SYSTEM_ID WHERE Y.PROJECT_ID = ?""",
                Integer.class, si);
        assertThat(notes).isEqualTo(2);
        Integer smNotes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM AS_NOTE N JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = N.SYSTEM_ID WHERE Y.PROJECT_ID = ?""",
                Integer.class, sm);
        assertThat(smNotes).isEqualTo(2);
        // 핫스팟 결함: 저장 버튼에 연결
        Integer hotspot = jdbc.queryForObject("""
                SELECT COUNT(*) FROM QA_DEFECT D JOIN AS_ELEMENT E ON E.ELEMENT_ID = D.ELEMENT_ID
                 WHERE D.PROJECT_ID = ? AND E.ELEMENT_CODE = 'btnSave'""", Integer.class, si);
        assertThat(hotspot).isEqualTo(1);
        // SM 데모 SR 생성
        Integer srs = jdbc.queryForObject("SELECT COUNT(*) FROM SM_SR WHERE PROJECT_ID = ?", Integer.class, sm);
        assertThat(srs).isGreaterThanOrEqualTo(30);
    }
}
