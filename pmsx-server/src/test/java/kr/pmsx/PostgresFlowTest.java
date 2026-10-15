package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 PostgreSQL(내장 바이너리, 시스템 설치 없음)에서 Flyway 마이그레이션 + 주요 흐름 검증.
 * H2 PostgreSQL 모드에서만 돌던 SQL 이 실DB에서도 동작하는지 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PostgresFlowTest {

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
        r.add("pmsx.storage.dir", () -> "./build/test-files-pg");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    @Test
    void PostgreSQL_에서_적재_뷰어_공통기능_흐름() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        JsonNode info = TestSupport.json(mvc, om, admin, "/api/v1/system/info");
        assertThat(info.get("db").asText()).startsWith("PostgreSQL");

        String systemId = TestSupport.hrSystemId(mvc, om, admin);
        JsonNode run = om.readTree(mvc.perform(multipart("/api/v1/systems/" + systemId + "/runs")
                        .file(new MockMultipartFile("file", "pmsx-HR-1.zip", "application/zip",
                                TestSupport.fixtureZip(om, null)))
                        .header("Authorization", admin))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(run.at("/diff/edgesAdded").asInt()).isEqualTo(22);

        JsonNode tree = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId + "/menu-tree");
        String screenId = tree.at("/0/children/0/screen/screenId").asText();
        JsonNode screen = TestSupport.json(mvc, om, admin, "/api/v1/screens/" + screenId);
        String btnSave = null;
        for (JsonNode e : screen.get("elements")) {
            if ("btnSave".equals(e.get("elementCode").asText())) {
                btnSave = e.get("elementId").asText();
            }
        }
        JsonNode wf = TestSupport.json(mvc, om, admin, "/api/v1/elements/" + btnSave + "/workflow");
        List<String> lanes = new ArrayList<>();
        wf.at("/events/0/steps").forEach(s -> lanes.add(s.get("lane").asText()));
        assertThat(lanes).startsWith("UI", "FRONT").contains("API", "SERVER", "SQL", "DB");
        JsonNode crud = TestSupport.json(mvc, om, admin, "/api/v1/screens/" + screenId + "/crud");
        assertThat(crud.get("tables")).hasSize(2);
        JsonNode hits = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId + "/search?q=tb_emp");
        assertThat(hits).isNotEmpty();
        mvc.perform(patch("/api/v1/screens/" + screenId + "/analysis").header("Authorization", analyst)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WORKING\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("WORKING"));

        // 공통 기능
        JsonNode combo = TestSupport.json(mvc, om, analyst, "/api/v1/codes?groups=ELEMENT_TYPE");
        assertThat(combo.get("ELEMENT_TYPE")).hasSizeGreaterThan(5);
        mvc.perform(post("/api/v1/terms").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"term\":\"부서코드\",\"definition\":\"부서 식별\"}")).andExpect(status().isCreated());
        assertThat(TestSupport.json(mvc, om, admin, "/api/v1/terms?q=부서").get("totalElements").asLong())
                .isEqualTo(1);
        mvc.perform(get("/api/v1/excel/export/as-elements").param("systemId", systemId)
                .header("Authorization", analyst)).andExpect(status().isOk());
        JsonNode logs = TestSupport.json(mvc, om, admin, "/api/v1/admin/audit-logs?action=UPLOAD&size=5");
        assertThat(logs.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        JsonNode users = TestSupport.json(mvc, om, admin, "/api/v1/admin/users?q=ana");
        assertThat(users.get("totalElements").asLong()).isEqualTo(1);
    }
}
