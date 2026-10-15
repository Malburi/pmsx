package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** 스키마 v0.2 수용, 적재 실패 시 고아 파일 정리, 웹 SPA fallback */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IngestV2Test {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;

    private ResultActions upload(String auth, String systemId, byte[] zip) throws Exception {
        return mvc.perform(multipart("/api/v1/systems/" + systemId + "/runs")
                .file(new MockMultipartFile("file", "pmsx-v2.zip", "application/zip", zip))
                .header("Authorization", auth));
    }

    private static String code() {
        return "V" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    @Test
    void 스키마_v02_패키지_namespace_scale_반영() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String code = code();
        String systemId = TestSupport.newSystem(mvc, om, admin, code);
        byte[] zip = TestSupport.fixtureZip(om, m -> {
            ((ObjectNode) m.get("manifest")).put("modelVersion", "0.2");
            ((ObjectNode) m.get("system")).put("code", code);
            ((ObjectNode) m.at("/screens/0/captures/0")).put("scale", 2.0);
            ((ObjectNode) m.at("/sqlStatements/0")).put("namespace", "kr.go.hr.emp.service.impl.EmpMapper");
        });
        upload(admin, systemId, zip).andExpect(status().isCreated());
        String screenId = jdbc.queryForObject("SELECT SCREEN_ID FROM AS_SCREEN WHERE SYSTEM_ID = ? AND SCREEN_CODE = ?",
                String.class, systemId, "HR0101");
        JsonNode screen = TestSupport.json(mvc, om, admin, "/api/v1/screens/" + screenId);
        assertThat(screen.at("/capture/scale").asDouble()).isEqualTo(2.0);
        String meta = jdbc.queryForObject("SELECT META_JSON FROM AS_NODE WHERE SYSTEM_ID = ? AND NODE_KEY = ?",
                String.class, systemId, "sql:EmpMapper.insertEmp");
        assertThat(meta).contains("kr.go.hr.emp.service.impl.EmpMapper");

        // v0.2 스키마: 요소·엣지에 정의되지 않은 속성은 허용하지 않는다
        byte[] extra = TestSupport.fixtureZip(om, m -> {
            ((ObjectNode) m.get("system")).put("code", code);
            ((ObjectNode) m.at("/elements/0")).put("_internal", 1);
        });
        upload(admin, systemId, extra).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PACKAGE_INVALID"));
    }

    @Test
    void 적재_실패하면_저장한_파일을_지운다() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String code = code();
        String systemId = TestSupport.newSystem(mvc, om, admin, code);
        Integer filesBefore = jdbc.queryForObject("SELECT COUNT(*) FROM CM_FILE", Integer.class);
        long diskBefore = countFiles();

        byte[] zip = TestSupport.fixtureZip(om, m -> {
            ((ObjectNode) m.get("system")).put("code", code);
            ArrayNode menus = (ArrayNode) m.get("menus");
            menus.add(menus.get(0).deepCopy());     // 같은 메뉴 키 중복 → DB 유니크 위반
        });
        upload(admin, systemId, zip).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PACKAGE_INVALID"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CM_FILE", Integer.class)).isEqualTo(filesBefore);
        assertThat(countFiles()).isEqualTo(diskBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM AS_COLLECT_RUN WHERE SYSTEM_ID = ?", Integer.class,
                systemId)).isZero();
    }

    @Test
    void 웹_정적리소스와_SPA_fallback() throws Exception {
        // "/" 는 Spring Boot 환영 페이지가 index.html 로 forward (MockMvc 는 forward 를 따라가지 않음)
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/asis/viewer/abc/def")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("pmsx-test-index")));
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/nope")).andExpect(status().isUnauthorized());
    }

    private static long countFiles() throws IOException {
        Path dir = Path.of("./build/test-files");
        if (!Files.exists(dir)) {
            return 0;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).count();
        }
    }
}
