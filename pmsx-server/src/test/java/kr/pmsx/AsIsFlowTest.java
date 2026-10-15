package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AsIsFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;

    private ResultActions upload(String auth, String systemId, byte[] zip) throws Exception {
        return mvc.perform(multipart("/api/v1/systems/" + systemId + "/runs")
                .file(new MockMultipartFile("file", "pmsx-HR-1.zip", "application/zip", zip))
                .header("Authorization", auth));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 업로드 → 메뉴 트리 → 화면 → 캡처 → 워크플로우 → 노드 → CRUD → 검색 → 분석 상태 → 재업로드 */
    @Test
    void 끝에서_끝까지_시나리오() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String systemId = TestSupport.hrSystemId(mvc, om, admin);

        // 1. 업로드
        JsonNode run = body(upload(admin, systemId, TestSupport.fixtureZip(om, null))
                .andExpect(status().isCreated()));
        assertThat(run.get("seq").asInt()).isEqualTo(1);
        assertThat(run.at("/diff/screensAdded").asInt()).isEqualTo(2);
        assertThat(run.at("/diff/elementsAdded").asInt()).isEqualTo(5);
        assertThat(run.at("/diff/edgesAdded").asInt()).isEqualTo(22);
        assertThat(run.at("/stats/screens").asInt()).isEqualTo(2);
        String runId = run.get("runId").asText();

        JsonNode runs = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId + "/runs");
        assertThat(runs).hasSize(1);
        mvc.perform(get("/api/v1/runs/" + runId + "/report").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));

        JsonNode sys = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId);
        assertThat(sys.get("screenCount").asInt()).isEqualTo(2);
        assertThat(sys.at("/lastRun/seq").asInt()).isEqualTo(1);

        // 2. 메뉴 트리: 인사관리 > 사원관리 > (팝업) 사원검색
        JsonNode tree = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId + "/menu-tree");
        assertThat(tree).hasSize(1);
        assertThat(tree.at("/0/name").asText()).isEqualTo("인사관리");
        JsonNode empMenu = tree.at("/0/children/0");
        assertThat(empMenu.get("name").asText()).isEqualTo("사원관리");
        assertThat(empMenu.at("/screen/screenCode").asText()).isEqualTo("HR0101");
        assertThat(empMenu.at("/screen/status").asText()).isEqualTo("AUTO");
        assertThat(empMenu.at("/children/0/screen/screenCode").asText()).isEqualTo("HR0101P01");
        assertThat(empMenu.at("/children/0/screen/kind").asText()).isEqualTo("popup");
        String screenId = empMenu.at("/screen/screenId").asText();

        // 3. 화면 상세
        JsonNode screen = TestSupport.json(mvc, om, admin, "/api/v1/screens/" + screenId);
        assertThat(screen.get("menuPath")).extracting(JsonNode::asText)
                .containsExactly("인사시스템", "인사관리", "사원관리");
        assertThat(screen.at("/capture/width").asInt()).isEqualTo(1);
        assertThat(screen.at("/capture/runSeq").asInt()).isEqualTo(1);
        assertThat(screen.get("elements")).hasSize(4);
        JsonNode btnSave = find(screen.get("elements"), "elementCode", "btnSave");
        assertThat(btnSave.get("seqNo").asInt()).isEqualTo(4);
        assertThat(btnSave.get("danger").asBoolean()).isTrue();
        assertThat(btnSave.at("/box/x").asDouble()).isEqualTo(756.0);
        assertThat(btnSave.at("/events/0/event").asText()).isEqualTo("click");
        assertThat(btnSave.at("/events/0/handler").asText()).isEqualTo("fn_save()");
        JsonNode txt = find(screen.get("elements"), "elementCode", "txtEmpNm");
        assertThat(txt.at("/events/0/event").asText()).isEqualTo("keydown");

        // 4. 캡처 이미지 (인증 필요)
        String captureId = screen.at("/capture/captureId").asText();
        mvc.perform(get("/api/v1/captures/" + captureId + "/image")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/captures/" + captureId + "/image").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));

        // 5. 워크플로우
        String btnSaveId = btnSave.get("elementId").asText();
        JsonNode wf = TestSupport.json(mvc, om, admin, "/api/v1/elements/" + btnSaveId + "/workflow");
        assertThat(wf.get("events")).hasSize(1);
        JsonNode steps = wf.at("/events/0/steps");
        List<String> titles = new ArrayList<>();
        steps.forEach(s -> titles.add(s.get("depth").asInt() + ":" + s.get("lane").asText() + ":" + s.get("title").asText()));
        assertThat(titles).containsExactly(
                "0:UI:[저장] click",
                "1:FRONT:fn_save()",
                "2:FRONT:gfn_ajax()",
                "2:API:POST /hr/emp/save.do",
                "3:SERVER:EmpController.saveEmp()",
                "4:SERVER:EmpServiceImpl.saveEmp()",
                "5:SQL:EmpMapper.insertEmp",
                "6:DB:TB_EMP",
                "5:SQL:EmpMapper.updateEmp",
                "6:DB:TB_EMP",
                "2:FRONT:fn_search()",
                "3:FRONT:gfn_ajax()",
                "3:API:POST /hr/emp/list.do",
                "4:SERVER:EmpController.selectEmpList()",
                "5:SQL:EmpMapper.selectEmpList",
                "6:DB:TB_EMP",
                "6:DB:TB_DEPT");
        assertThat(steps.at("/0/edge").isNull()).isTrue();
        assertThat(steps.at("/1/subtitle").asText()).isEqualTo("HR0101.js:36");
        assertThat(steps.at("/1/hasSource").asBoolean()).isTrue();
        assertThat(steps.at("/3/edge/source").asText()).isEqualTo("dynamic");
        assertThat(steps.at("/6/edge/condition").asText()).isEqualTo("rowStatus == 'I'");
        assertThat(steps.at("/6/subtitle").asText()).isEqualTo("INSERT · EmpMapper.xml");
        assertThat(steps.at("/7/crud").asText()).isEqualTo("C");
        assertThat(steps.at("/7/columns/0").asText()).isEqualTo("EMP_NO");
        assertThat(steps.at("/7/subtitle").asText()).isEqualTo("사원");
        assertThat(steps.at("/9/crud").asText()).isEqualTo("U");
        assertThat(steps.at("/9/subtitle").asText()).contains("(위와 동일 흐름)");
        assertThat(steps.at("/11/subtitle").asText()).contains("(위와 동일 흐름)");
        assertThat(steps.at("/12/subtitle").asText()).isEqualTo("param: empNm, deptCd");

        // 팝업 버튼 → 화면 이동 단계
        JsonNode btnPop = find(screen.get("elements"), "elementCode", "btnEmpPop");
        JsonNode wfPop = TestSupport.json(mvc, om, admin, "/api/v1/elements/" + btnPop.get("elementId").asText()
                + "/workflow");
        assertThat(wfPop.at("/events/0/steps/2/title").asText()).isEqualTo("팝업: 사원검색");
        assertThat(wfPop.at("/events/0/steps/2/lane").asText()).isEqualTo("UI");

        // 6. 노드 상세 (+ 역참조)
        String fnSaveNode = steps.at("/1/nodeId").asText();
        JsonNode node = TestSupport.json(mvc, om, admin, "/api/v1/nodes/" + fnSaveNode);
        assertThat(node.get("snippet").asText()).contains("function fn_save");
        assertThat(node.get("nodeType").asText()).isEqualTo("FN");
        assertThat(node.get("usedBy")).extracting(u -> u.get("elementLabel").asText()).containsExactly("저장");
        String tbEmpNode = steps.at("/7/nodeId").asText();
        JsonNode tbEmp = TestSupport.json(mvc, om, admin, "/api/v1/nodes/" + tbEmpNode);
        assertThat(tbEmp.get("usedBy")).extracting(u -> u.get("elementLabel").asText())
                .containsExactlyInAnyOrder("저장", "조회", "사원명");

        // 7. CRUD 매트릭스
        JsonNode crud = TestSupport.json(mvc, om, admin, "/api/v1/screens/" + screenId + "/crud");
        assertThat(crud.get("tables")).extracting(t -> t.get("name").asText()).containsExactly("TB_DEPT", "TB_EMP");
        JsonNode saveRow = find(crud.get("rows"), "label", "저장");
        assertThat(saveRow.at("/cells/" + tbEmpNode).asText()).isEqualTo("CRU");
        JsonNode searchRow = find(crud.get("rows"), "label", "조회");
        assertThat(searchRow.at("/cells/" + tbEmpNode).asText()).isEqualTo("R");

        // 8. 검색
        JsonNode hits = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId + "/search?q=저장");
        assertThat(hits.get(0).get("kind").asText()).isEqualTo("ELEMENT");
        assertThat(hits.get(0).get("screenId").asText()).isEqualTo(screenId);
        JsonNode hits2 = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId + "/search?q=tb_emp");
        assertThat(hits2).extracting(h -> h.get("kind").asText()).contains("NODE");
        JsonNode hits3 = TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId + "/search?q=사원");
        assertThat(hits3.get(0).get("kind").asText()).isEqualTo("SCREEN");
        assertThat(hits3.get(0).get("sub").asText()).isEqualTo("인사시스템 › 인사관리 › 사원관리");

        // 9. 분석 상태: 분석자는 미배정 화면을 WORKING 으로(본인 배정), DONE 은 불가, PM 은 확정 가능
        mvc.perform(patch("/api/v1/screens/" + screenId + "/analysis").header("Authorization", analyst)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"WORKING\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WORKING"))
                .andExpect(jsonPath("$.owner.name").value("김분석"));
        mvc.perform(patch("/api/v1/screens/" + screenId + "/analysis").header("Authorization", analyst)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(patch("/api/v1/screens/" + screenId + "/analysis").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"BAD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.status").exists());
        mvc.perform(patch("/api/v1/screens/" + screenId + "/analysis").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.owner.name").value("김분석"))
                .andExpect(jsonPath("$.reviewer.name").value("관리자"));
        assertThat(TestSupport.json(mvc, om, admin, "/api/v1/systems/" + systemId).get("doneCount").asInt())
                .isEqualTo(1);

        // 10. 재업로드: 수동 보정 엣지 보존, 배지 번호 유지, 삭제 diff
        jdbc.update("UPDATE AS_EDGE SET SOURCE = 'manual', CONFIDENCE = 1.0, EVIDENCE = '분석자 확인'"
                + " WHERE FROM_KEY = 'el:HR:HR0101#btnSave' AND KIND = 'event'");
        byte[] zip2 = TestSupport.fixtureZip(om, m -> {
            m.with("manifest").put("runId", "fixture-run-2");
            ArrayNode els = (ArrayNode) m.get("elements");
            for (int i = els.size() - 1; i >= 0; i--) {
                if (els.get(i).get("elementId").asText().equals("btnSelect")) {
                    els.remove(i);
                }
            }
            ArrayNode edges = (ArrayNode) m.get("edges");
            for (int i = edges.size() - 1; i >= 0; i--) {
                if (edges.get(i).get("from").asText().endsWith("#btnSelect")) {
                    edges.remove(i);
                }
            }
            for (JsonNode e : edges) {
                if (e.get("from").asText().endsWith("#btnSave") && e.get("kind").asText().equals("event")) {
                    ((ObjectNode) e).put("confidence", 0.5);
                }
            }
        });
        JsonNode run2 = body(upload(admin, systemId, zip2).andExpect(status().isCreated()));
        assertThat(run2.get("seq").asInt()).isEqualTo(2);
        assertThat(run2.at("/diff/screensAdded").asInt()).isZero();
        assertThat(run2.at("/diff/screensRemoved").asInt()).isZero();
        assertThat(run2.at("/diff/elementsAdded").asInt()).isZero();
        assertThat(run2.at("/diff/elementsRemoved").asInt()).isEqualTo(1);
        assertThat(run2.at("/diff/edgesRemoved").asInt()).isEqualTo(1);

        JsonNode wf2 = TestSupport.json(mvc, om, admin, "/api/v1/elements/" + btnSaveId + "/workflow");
        assertThat(wf2.at("/events/0/steps/1/edge/source").asText()).isEqualTo("manual");
        assertThat(wf2.at("/events/0/steps/1/edge/confidence").asDouble()).isEqualTo(1.0);
        JsonNode screen2 = TestSupport.json(mvc, om, admin, "/api/v1/screens/" + screenId);
        assertThat(find(screen2.get("elements"), "elementCode", "btnSave").get("elementId").asText())
                .isEqualTo(btnSaveId);
        assertThat(find(screen2.get("elements"), "elementCode", "btnSave").get("seqNo").asInt()).isEqualTo(4);
        assertThat(screen2.at("/capture/runSeq").asInt()).isEqualTo(2);
        assertThat(screen2.at("/analysis/status").asText()).isEqualTo("DONE");
    }

    @Test
    void 다른_시스템_코드_패키지는_SYSTEM_MISMATCH() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String systemId = TestSupport.hrSystemId(mvc, om, admin);
        byte[] zip = TestSupport.fixtureZip(om, m -> m.with("system").put("code", "PAY"));
        upload(admin, systemId, zip)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYSTEM_MISMATCH"));
    }

    @Test
    void 스키마_위반이나_zip이_아니면_PACKAGE_INVALID() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String systemId = TestSupport.hrSystemId(mvc, om, admin);
        upload(admin, systemId, TestSupport.fixtureZip(om, m -> m.remove("manifest")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PACKAGE_INVALID"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("manifest")));
        upload(admin, systemId, TestSupport.fixtureZip(om, m -> ((ObjectNode) m.get("elements").get(0))
                .put("type", "spaceship")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PACKAGE_INVALID"));
        upload(admin, systemId, "not a zip".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PACKAGE_INVALID"));
    }

    @Test
    void 시스템_등록_권한과_중복() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String projectId = TestSupport.seedProjectId(mvc, om, admin);
        String req = "{\"code\":\"PAY\",\"name\":\"급여시스템\",\"uiTech\":[\"nexacro\"],\"serverTech\":[\"spring\"],"
                + "\"dbType\":\"oracle\"}";
        mvc.perform(post("/api/v1/projects/" + projectId + "/systems").header("Authorization", analyst)
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/projects/" + projectId + "/systems").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PAY"))
                .andExpect(jsonPath("$.uiTech[0]").value("nexacro"))
                .andExpect(jsonPath("$.lastRun").isEmpty());
        mvc.perform(post("/api/v1/projects/" + projectId + "/systems").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(post("/api/v1/projects/" + projectId + "/systems").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"a b\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"));
    }

    @Test
    void 기술을_비워_등록하면_첫_업로드의_감지값으로_채운다() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String projectId = TestSupport.seedProjectId(mvc, om, admin);
        // 코드·이름만으로 등록 (기술·DB 비움)
        JsonNode created = body(mvc.perform(post("/api/v1/projects/" + projectId + "/systems")
                        .header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"AUTO\",\"name\":\"자동감지\"}"))
                .andExpect(status().isCreated()));
        assertThat(created.get("uiTech")).isEmpty();
        // 사용자가 입력한 값은 덮어쓰지 않는다
        JsonNode manual = body(mvc.perform(post("/api/v1/projects/" + projectId + "/systems")
                        .header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"KEEP\",\"name\":\"수동\",\"uiTech\":[\"custom-ui\"],\"dbType\":\"tibero\"}"))
                .andExpect(status().isCreated()));

        upload(admin, created.get("systemId").asText(), TestSupport.fixtureZip(om, m -> {
            m.with("system").put("code", "AUTO").put("dbType", "oracle");
            m.with("system").putArray("uiTech").add("jsp-jquery");
            m.with("system").putArray("serverTech").add("spring");
        })).andExpect(status().isCreated());
        upload(admin, manual.get("systemId").asText(), TestSupport.fixtureZip(om, m -> {
            m.with("system").put("code", "KEEP").put("dbType", "oracle");
            m.with("system").putArray("uiTech").add("jsp-jquery");
            m.with("system").putArray("serverTech").add("spring");
        })).andExpect(status().isCreated());

        JsonNode auto = body(mvc.perform(get("/api/v1/systems/" + created.get("systemId").asText())
                .header("Authorization", admin)).andExpect(status().isOk()));
        assertThat(auto.get("uiTech").get(0).asText()).isEqualTo("jsp-jquery");
        assertThat(auto.get("serverTech").get(0).asText()).isEqualTo("spring");
        assertThat(auto.get("dbType").asText()).isEqualTo("oracle");
        JsonNode kept = body(mvc.perform(get("/api/v1/systems/" + manual.get("systemId").asText())
                .header("Authorization", admin)).andExpect(status().isOk()));
        assertThat(kept.get("uiTech").get(0).asText()).isEqualTo("custom-ui");
        assertThat(kept.get("serverTech").get(0).asText()).isEqualTo("spring");
        assertThat(kept.get("dbType").asText()).isEqualTo("tibero");
    }

    @Test
    void 없는_대상은_NOT_FOUND() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        mvc.perform(get("/api/v1/screens/NOPE").header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/elements/NOPE/workflow").header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    private static JsonNode find(JsonNode arr, String field, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.path(field).asText())) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + value + " 항목이 없습니다: " + arr);
    }
}
