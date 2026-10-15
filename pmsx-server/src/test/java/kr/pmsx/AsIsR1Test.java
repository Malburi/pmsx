package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import kr.pmsx.as.viewer.WorkflowService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockPart;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** R1 (docs/11): 핫스팟 편집·연결 보정 보존, 메모·이력·배정·진척, 테이블·ERD·관계, 시스템 뷰, 산출물, 첨부 권한. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AsIsR1Test {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    private JsonNode body(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode json(String auth, String url) throws Exception {
        return TestSupport.json(mvc, om, auth, url);
    }

    private ResultActions send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b,
            String auth, Object payload) throws Exception {
        return mvc.perform(b.header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(payload instanceof String s ? s : om.writeValueAsString(payload)));
    }

    /** 새 시스템을 만들고 픽스처(시스템 코드만 바꿈)를 올린다. [systemId, screenId(HR0101)] */
    private String[] setup(String admin, String code, Consumer<ObjectNode> modify) throws Exception {
        String systemId = TestSupport.newSystem(mvc, om, admin, code);
        upload(admin, systemId, code, modify, "run-1");
        String screenId = null;
        for (JsonNode h : json(admin, "/api/v1/systems/" + systemId + "/search?q=HR0101")) {
            if ("SCREEN".equals(h.get("kind").asText()) && h.get("label").asText().endsWith("(HR0101)")) {
                screenId = h.get("screenId").asText();
            }
        }
        assertThat(screenId).isNotNull();
        return new String[] {systemId, screenId};
    }

    private JsonNode upload(String admin, String systemId, String code, Consumer<ObjectNode> modify, String runId)
            throws Exception {
        byte[] zip = TestSupport.fixtureZip(om, m -> {
            m.with("system").put("code", code).put("key", "sys:" + code);
            m.with("manifest").put("runId", runId);
            if (modify != null) {
                modify.accept(m);
            }
        });
        return body(mvc.perform(multipart("/api/v1/systems/" + systemId + "/runs")
                        .file(new MockMultipartFile("file", "pkg.zip", "application/zip", zip))
                        .header("Authorization", admin))
                .andExpect(status().isCreated()));
    }

    private static JsonNode find(JsonNode arr, String field, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.path(field).asText())) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + value + " 없음: " + arr);
    }

    private String nodeId(String auth, String systemId, String q, String type) throws Exception {
        return json(auth, "/api/v1/systems/" + systemId + "/nodes?q=" + q + "&type=" + type).at("/content/0/nodeId")
                .asText();
    }

    @Test
    void 핫스팟_편집과_연결_보정은_재수집해도_보존() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String[] ids = setup(admin, "R1E", null);
        String systemId = ids[0];
        String screenId = ids[1];
        JsonNode screen = json(admin, "/api/v1/screens/" + screenId);
        String btnSearch = find(screen.get("elements"), "elementCode", "btnSearch").get("elementId").asText();
        String btnEmpPop = find(screen.get("elements"), "elementCode", "btnEmpPop").get("elementId").asText();
        String btnSave = find(screen.get("elements"), "elementCode", "btnSave").get("elementId").asText();
        assertThat(find(screen.get("elements"), "elementCode", "btnSave").get("manual").asBoolean()).isFalse();

        // 수동 요소 추가 (중복 ID 409, 잘못된 유형 400)
        String req = "{\"elementCode\":\"btnPrint\",\"label\":\"인쇄\",\"type\":\"button\","
                + "\"box\":{\"x\":10,\"y\":20,\"w\":60,\"h\":24}}";
        JsonNode created = body(send(post("/api/v1/screens/" + screenId + "/elements"), admin, req)
                .andExpect(status().isCreated()));
        String btnPrint = created.get("elementId").asText();
        assertThat(created.get("manual").asBoolean()).isTrue();
        assertThat(created.get("seqNo").asInt()).isEqualTo(5);
        send(post("/api/v1/screens/" + screenId + "/elements"), admin, req).andExpect(status().isConflict());
        send(post("/api/v1/screens/" + screenId + "/elements"), admin,
                req.replace("button", "nope").replace("btnPrint", "x1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.type").exists());

        // 수집 요소 수정 → manual, 수집 요소 삭제 → 숨김
        send(put("/api/v1/elements/" + btnSearch), admin, "{\"label\":\"검색(수정)\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.manual").value(true))
                .andExpect(jsonPath("$.label").value("검색(수정)"));
        mvc.perform(delete("/api/v1/elements/" + btnEmpPop).header("Authorization", admin))
                .andExpect(status().isNoContent());
        assertThat(find(json(admin, "/api/v1/screens/" + screenId).get("elements"), "elementCode", "btnEmpPop")
                .get("hidden").asBoolean()).isTrue();

        // 분석자: 다른 사람 담당 화면은 수정 불가
        send(patch("/api/v1/screens/" + screenId + "/analysis"), admin,
                "{\"ownerId\":\"" + json(admin, "/api/v1/auth/me").get("userId").asText() + "\"}")
                .andExpect(status().isOk());
        send(put("/api/v1/elements/" + btnSearch), analyst, "{\"label\":\"x\"}").andExpect(status().isForbidden());

        // 연결: 새 API 노드로 수동 엣지, 수집 엣지 확정·조건, 수집 엣지 기각
        String fnSave = nodeId(admin, systemId, "fn_save", "FN");
        JsonNode edge = body(send(post("/api/v1/edges"), admin, "{\"from\":{\"nodeId\":\"" + fnSave + "\"},"
                + "\"to\":{\"newNode\":{\"nodeType\":\"API\",\"name\":\"POST /hr/emp/audit.do\"}},\"kind\":\"request\"}")
                .andExpect(status().isCreated()));
        String manualEdge = edge.get("edgeId").asText();
        assertThat(edge.get("source").asText()).isEqualTo("manual");
        assertThat(edge.get("confirmed").asBoolean()).isTrue();
        send(post("/api/v1/edges"), admin, "{\"from\":{\"nodeId\":\"" + fnSave + "\"},"
                + "\"to\":{\"newNode\":{\"nodeType\":\"API\",\"name\":\"POST /hr/emp/audit.do\"}},\"kind\":\"request\"}")
                .andExpect(status().isConflict());

        JsonNode wf = json(admin, "/api/v1/elements/" + btnSave + "/workflow");
        JsonNode saveEvent = wf.at("/events/0/steps/1/edge");
        String eventEdge = saveEvent.get("edgeId").asText();
        assertThat(eventEdge).isNotBlank();
        send(patch("/api/v1/edges/" + eventEdge), admin, "{\"confirmed\":true,\"condition\":\"권한 있을 때\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.confirmed").value(true));
        String ajaxEdge = null;
        for (JsonNode st : wf.at("/events/0/steps")) {
            if (st.get("title").asText().startsWith("gfn_ajax")) {
                ajaxEdge = st.at("/edge/edgeId").asText();
                // fn_save → gfn_ajax, fn_search → gfn_ajax 두 연결 모두 기각
                send(patch("/api/v1/edges/" + ajaxEdge), admin, "{\"status\":\"rejected\"}")
                        .andExpect(status().isOk());
            }
        }
        assertThat(ajaxEdge).isNotNull();
        mvc.perform(delete("/api/v1/edges/" + ajaxEdge).header("Authorization", admin))
                .andExpect(status().isBadRequest());

        // 재수집 (같은 패키지) → 수동 요소·수정·숨김·수동 엣지·기각·확정 모두 유지
        JsonNode run2 = upload(admin, systemId, "R1E", null, "run-2");
        assertThat(run2.at("/diff/elementsRemoved").asInt()).isZero();
        assertThat(run2.at("/diff/edgesRemoved").asInt()).isZero();
        JsonNode s2 = json(admin, "/api/v1/screens/" + screenId);
        assertThat(find(s2.get("elements"), "elementCode", "btnPrint").get("manual").asBoolean()).isTrue();
        assertThat(find(s2.get("elements"), "elementCode", "btnSearch").get("label").asText()).isEqualTo("검색(수정)");
        assertThat(find(s2.get("elements"), "elementCode", "btnEmpPop").get("hidden").asBoolean()).isTrue();
        JsonNode wf2 = json(admin, "/api/v1/elements/" + btnSave + "/workflow");
        List<String> titles = new ArrayList<>();
        wf2.at("/events/0/steps").forEach(s -> titles.add(s.get("title").asText()));
        assertThat(titles).contains("POST /hr/emp/audit.do");
        // 기각 엣지는 status=rejected 로 표시만 되고 (11 v0.2) 그 아래는 펼치지 않는다
        for (JsonNode st : wf2.at("/events/0/steps")) {
            if (st.get("title").asText().startsWith("gfn_ajax")) {
                assertThat(st.at("/edge/status").asText()).isEqualTo("rejected");
            } else if (st.hasNonNull("edge")) {
                assertThat(st.at("/edge/status").asText()).isEqualTo("active");
            }
        }
        assertThat(wf2.at("/events/0/steps/1/edge/confirmed").asBoolean()).isTrue();
        assertThat(wf2.at("/events/0/steps/1/edge/condition").asText()).isEqualTo("권한 있을 때");

        // 숨김 복원, 수동 요소 삭제(실제 삭제), 수동 엣지 삭제
        mvc.perform(post("/api/v1/elements/" + btnEmpPop + "/restore").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(false));
        mvc.perform(delete("/api/v1/elements/" + btnPrint).header("Authorization", admin))
                .andExpect(status().isNoContent());
        JsonNode s3 = json(admin, "/api/v1/screens/" + screenId);
        assertThat(s3.get("elements").findValuesAsText("elementCode")).doesNotContain("btnPrint");
        mvc.perform(delete("/api/v1/edges/" + manualEdge).header("Authorization", admin))
                .andExpect(status().isNoContent());

        // 노드 검색 (유형 필터, 화면 포함)
        JsonNode nodes = json(admin, "/api/v1/systems/" + systemId + "/nodes?q=save&type=API");
        assertThat(nodes.get("totalElements").asInt()).isEqualTo(1);
        assertThat(json(admin, "/api/v1/systems/" + systemId + "/nodes?type=SCREEN").get("totalElements").asInt())
                .isEqualTo(2);
        mvc.perform(get("/api/v1/systems/" + systemId + "/nodes?type=BAD").header("Authorization", admin))
                .andExpect(status().isBadRequest());

        // 수동 캡처 업로드 → 최신 캡처 (runSeq 0)
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        mvc.perform(multipart("/api/v1/screens/" + screenId + "/captures")
                        .file(new MockMultipartFile("file", "manual.png", "image/png", png.toByteArray()))
                        .header("Authorization", admin))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.width").value(320))
                .andExpect(jsonPath("$.runSeq").value(0));
        assertThat(json(admin, "/api/v1/screens/" + screenId).at("/capture/width").asInt()).isEqualTo(320);
        mvc.perform(multipart("/api/v1/screens/" + screenId + "/captures")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain", "x".getBytes()))
                        .header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
    }

    @Test
    void 메모_분석이력_일괄배정_진척() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String[] ids = setup(admin, "R1N", null);
        String systemId = ids[0];
        String screenId = ids[1];
        String analystId = json(analyst, "/api/v1/auth/me").get("userId").asText();
        String adminId = json(admin, "/api/v1/auth/me").get("userId").asText();

        // 메모: 분석자 작성, 길이 제한, PM 삭제 가능, 다른 사람 메모 수정 불가
        JsonNode note = body(send(post("/api/v1/notes"), analyst, "{\"targetType\":\"SCREEN\",\"targetId\":\""
                + screenId + "\",\"content\":\"## 규칙\\n- 입사일은 미래 불가\"}").andExpect(status().isCreated()));
        assertThat(note.at("/author/userId").asText()).isEqualTo(analystId);
        send(post("/api/v1/notes"), analyst, "{\"targetType\":\"SCREEN\",\"targetId\":\"" + screenId
                + "\",\"content\":\"" + "가".repeat(20001) + "\"}").andExpect(status().isBadRequest());
        send(post("/api/v1/notes"), analyst, "{\"targetType\":\"BAD\",\"targetId\":\"x\",\"content\":\"a\"}")
                .andExpect(status().isBadRequest());
        JsonNode adminNote = body(send(post("/api/v1/notes"), admin, "{\"targetType\":\"SCREEN\",\"targetId\":\""
                + screenId + "\",\"content\":\"관리자 메모\"}").andExpect(status().isCreated()));
        send(put("/api/v1/notes/" + adminNote.get("noteId").asText()), analyst, "{\"content\":\"x\"}")
                .andExpect(status().isForbidden());
        send(put("/api/v1/notes/" + note.get("noteId").asText()), analyst, "{\"content\":\"수정됨\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("수정됨"));
        assertThat(json(admin, "/api/v1/notes?targetType=SCREEN&targetId=" + screenId)).hasSize(2);
        mvc.perform(delete("/api/v1/notes/" + note.get("noteId").asText()).header("Authorization", admin))
                .andExpect(status().isNoContent());

        // 일괄 배정 (PM만), 진척, 화면 그리드 필터
        String popupId = json(admin, "/api/v1/systems/" + systemId + "/search?q=HR0101P01").get(0).get("screenId")
                .asText();
        send(post("/api/v1/systems/" + systemId + "/analysis/assign"), analyst,
                "{\"screenIds\":[\"" + screenId + "\"],\"ownerId\":\"" + analystId + "\"}")
                .andExpect(status().isForbidden());
        send(post("/api/v1/systems/" + systemId + "/analysis/assign"), admin, "{\"screenIds\":[\"" + screenId + "\",\""
                + popupId + "\"],\"ownerId\":\"" + analystId + "\",\"reviewerId\":\"" + adminId + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(2));
        JsonNode progress = json(admin, "/api/v1/systems/" + systemId + "/analysis/progress");
        assertThat(progress.get("total").asInt()).isEqualTo(2);
        assertThat(progress.at("/byStatus/AUTO").asInt()).isEqualTo(2);
        assertThat(find(progress.get("byOwner"), "total", "2").at("/owner/userId").asText()).isEqualTo(analystId);
        assertThat(progress.get("byMenu").findValuesAsText("name")).isNotEmpty();
        assertThat(progress.get("unmatchedElements").asInt()).isGreaterThanOrEqualTo(0);
        assertThat(progress.get("unconfirmedEdges").asInt()).isGreaterThanOrEqualTo(1);   // 0.6·0.7·0.9 미만 엣지

        // 분석자가 상태 변경 + 코멘트 → 이력
        send(patch("/api/v1/screens/" + screenId + "/analysis"), analyst,
                "{\"status\":\"REVIEW\",\"comment\":\"검토 부탁드립니다\"}").andExpect(status().isOk());
        JsonNode grid = json(admin, "/api/v1/systems/" + systemId + "/analysis/screens?status=REVIEW&ownerId="
                + analystId);
        assertThat(grid.get("totalElements").asInt()).isEqualTo(1);
        assertThat(grid.at("/content/0/screenCode").asText()).isEqualTo("HR0101");
        assertThat(grid.at("/content/0/elementCount").asInt()).isEqualTo(4);
        assertThat(json(admin, "/api/v1/systems/" + systemId + "/analysis/screens?q=P01").get("totalElements")
                .asInt()).isEqualTo(1);
        JsonNode hist = json(admin, "/api/v1/screens/" + screenId + "/analysis/history");
        assertThat(hist.get(0).get("toStatus").asText()).isEqualTo("REVIEW");
        assertThat(hist.get(0).get("comment").asText()).isEqualTo("검토 부탁드립니다");
        assertThat(hist.findValuesAsText("comment")).contains("일괄 배정");
        // PM 이 확정 (검토자 유지)
        send(patch("/api/v1/screens/" + screenId + "/analysis"), admin, "{\"status\":\"DONE\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.reviewer.userId").value(adminId));
    }

    @Test
    void 테이블정의서_ERD_관계보정() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String[] ids = setup(admin, "R1T", null);
        String systemId = ids[0];

        JsonNode tables = json(admin, "/api/v1/systems/" + systemId + "/tables");
        assertThat(tables.get("totalElements").asInt()).isEqualTo(2);
        JsonNode emp = find(tables.get("content"), "name", "TB_EMP");
        assertThat(emp.get("columnCount").asInt()).isEqualTo(3);
        assertThat(emp.get("usedByScreens").asInt()).isEqualTo(1);
        String empId = emp.get("tableId").asText();
        String deptId = find(tables.get("content"), "name", "TB_DEPT").get("tableId").asText();

        JsonNode detail = json(admin, "/api/v1/tables/" + empId);
        assertThat(detail.get("relations")).hasSize(1);
        assertThat(detail.at("/relations/0/kind").asText()).isEqualTo("inferred");
        assertThat(find(detail.get("columns"), "name", "DEPT_CD").get("fk").asBoolean()).isFalse();
        JsonNode usedBy = detail.get("usedBy");
        assertThat(find(usedBy, "sqlName", "EmpMapper.insertEmp").get("crud").asText()).isEqualTo("C");
        assertThat(usedBy.findValuesAsText("elementLabel")).contains("저장", "조회");

        // 주제영역·코멘트 보정 → 재수집해도 유지
        send(put("/api/v1/tables/" + empId), admin, "{\"subjectArea\":\"인사\",\"comment\":\"사원 마스터\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.subjectArea").value("인사"));
        upload(admin, systemId, "R1T", null, "run-2");
        JsonNode emp2 = json(admin, "/api/v1/tables/" + empId);
        assertThat(emp2.get("subjectArea").asText()).isEqualTo("인사");
        assertThat(emp2.get("comment").asText()).isEqualTo("사원 마스터");
        assertThat(json(admin, "/api/v1/systems/" + systemId + "/subject-areas").get(0).get("tableCount").asInt())
                .isEqualTo(1);
        assertThat(json(admin, "/api/v1/systems/" + systemId + "/tables?subjectArea=-").get("totalElements")
                .asInt()).isEqualTo(1);

        // ERD: 추정 관계 제외 옵션 → 확정 후 포함, FK 표시
        assertThat(json(admin, "/api/v1/systems/" + systemId + "/erd?includeInferred=false").get("relations"))
                .isEmpty();
        JsonNode erd = json(admin, "/api/v1/systems/" + systemId + "/erd");
        assertThat(erd.get("tables")).hasSize(2);
        // 선택 테이블 + 1단계 관계 테이블
        JsonNode picked = json(admin, "/api/v1/systems/" + systemId + "/erd?tableIds=" + empId);
        List<String> pickedNames = new ArrayList<>();
        picked.get("tables").forEach(t -> pickedNames.add(t.get("name").asText()));
        assertThat(pickedNames).containsExactlyInAnyOrder("TB_EMP", "TB_DEPT");
        assertThat(picked.get("relations")).hasSize(1);
        String relId = erd.at("/relations/0/relationId").asText();
        send(patch("/api/v1/relations/" + relId), admin, "{\"decision\":\"confirmed\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("confirmed"));
        JsonNode erd2 = json(admin, "/api/v1/systems/" + systemId + "/erd?includeInferred=false");
        assertThat(erd2.get("relations")).hasSize(1);
        JsonNode empCols = find(erd2.get("tables"), "name", "TB_EMP").get("columns");
        assertThat(find(empCols, "name", "DEPT_CD").get("fk").asBoolean()).isTrue();
        send(patch("/api/v1/relations/" + relId), admin, "{\"decision\":\"maybe\"}")
                .andExpect(status().isBadRequest());

        // 수동 관계 추가 (컬럼 검증, 중복 409)
        String rel = "{\"childTableId\":\"" + deptId + "\",\"parentTableId\":\"" + empId
                + "\",\"columns\":[[\"DEPT_CD\",\"DEPT_CD\"]]}";
        send(post("/api/v1/relations"), admin, rel).andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("manual")).andExpect(jsonPath("$.decision").value("confirmed"));
        send(post("/api/v1/relations"), admin, rel).andExpect(status().isConflict());
        send(post("/api/v1/relations"), admin, rel.replace("[\"DEPT_CD\",\"DEPT_CD\"]", "[\"NOPE\",\"DEPT_CD\"]"))
                .andExpect(status().isBadRequest());
        // 재수집해도 manual 종류 유지
        upload(admin, systemId, "R1T", null, "run-3");
        assertThat(json(admin, "/api/v1/tables/" + deptId).get("relations").findValuesAsText("kind"))
                .contains("manual", "inferred");
    }

    @Test
    void 시스템_CRUD_이동맵_호출트리_사용처_회차diff() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String[] ids = setup(admin, "R1S", null);
        String systemId = ids[0];
        String screenId = ids[1];

        JsonNode crud = json(admin, "/api/v1/systems/" + systemId + "/crud");
        String empTable = find(crud.get("tables"), "name", "TB_EMP").get("nodeId").asText();
        JsonNode row = find(crud.get("rows"), "elementId", screenId);
        assertThat(row.at("/cells/" + empTable).asText()).isEqualTo("CRU");
        JsonNode byEl = json(admin, "/api/v1/systems/" + systemId + "/crud?by=element");
        assertThat(find(byEl.get("rows"), "label", "저장").at("/cells/" + empTable).asText()).isEqualTo("CRU");
        assertThat(find(byEl.get("rows"), "label", "저장").get("screenId").asText()).isEqualTo(screenId);
        assertThat(find(byEl.get("rows"), "label", "저장").get("sub").asText()).isEqualTo("사원관리");
        assertThat(row.get("screenId").asText()).isEqualTo(screenId);
        mvc.perform(get("/api/v1/systems/" + systemId + "/crud?by=x").header("Authorization", admin))
                .andExpect(status().isBadRequest());

        JsonNode nav = json(admin, "/api/v1/systems/" + systemId + "/navigation");
        assertThat(nav.get("nodes")).hasSize(2);
        assertThat(nav.get("links")).hasSize(1);
        assertThat(nav.at("/links/0/fromScreenId").asText()).isEqualTo(screenId);
        assertThat(nav.at("/links/0/kind").asText()).isEqualTo("popup");
        assertThat(nav.at("/links/0/via").asText()).isEqualTo("fn_openEmpPopup()");

        String fnSave = nodeId(admin, systemId, "fn_save", "FN");
        JsonNode down = json(admin, "/api/v1/nodes/" + fnSave + "/call-tree?direction=down&depth=2");
        assertThat(down.get("children").findValuesAsText("name")).contains("POST /hr/emp/save.do");
        String ajax = nodeId(admin, systemId, "gfn_ajax", "FN");
        JsonNode up = json(admin, "/api/v1/nodes/" + ajax + "/call-tree?direction=up&depth=3");
        assertThat(up.get("children").findValuesAsText("name")).contains("fn_save()", "fn_search()");
        assertThat(up.findValuesAsText("nodeType")).contains("ELEMENT");

        JsonNode usage = json(admin, "/api/v1/nodes/" + ajax + "/usage");
        assertThat(usage.findValuesAsText("elementLabel")).contains("저장", "조회");
        assertThat(find(usage, "elementLabel", "저장").get("path").get(0).asText()).isEqualTo("fn_save()");

        // 2회차: 팝업 요소 제거 → diff 상세
        JsonNode run2 = upload(admin, systemId, "R1S", m -> {
            var els = (com.fasterxml.jackson.databind.node.ArrayNode) m.get("elements");
            for (int i = els.size() - 1; i >= 0; i--) {
                if (els.get(i).get("elementId").asText().equals("btnSelect")) {
                    els.remove(i);
                }
            }
            var edges = (com.fasterxml.jackson.databind.node.ArrayNode) m.get("edges");
            for (int i = edges.size() - 1; i >= 0; i--) {
                if (edges.get(i).get("from").asText().endsWith("#btnSelect")) {
                    edges.remove(i);
                }
            }
        }, "run-2");
        JsonNode diff = json(admin, "/api/v1/runs/" + run2.get("runId").asText() + "/diff");
        assertThat(diff.at("/elements/removed/0/key").asText()).endsWith("#btnSelect");
        assertThat(diff.at("/elements/removed/0/label").asText()).contains("선택");
        assertThat(diff.at("/edges/removed").asInt()).isEqualTo(1);
        assertThat(diff.at("/edges/samples/0/change").asText()).isEqualTo("removed");
        assertThat(diff.get("prevRunId").asText()).isNotBlank();
    }

    @Test
    void 산출물_xlsx_pptx_생성과_내용() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String[] ids = setup(admin, "R1D", m -> {
            // 캡처를 320x200 실제 이미지로 (요소 박스가 이미지 안에 들어오게)
            ((ObjectNode) m.withArray("screens").get(0)).putArray("captures");
        });
        String systemId = ids[0];
        String screenId = ids[1];
        BufferedImage img = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        mvc.perform(multipart("/api/v1/screens/" + screenId + "/captures")
                .file(new MockMultipartFile("file", "cap.png", "image/png", png.toByteArray()))
                .header("Authorization", admin)).andExpect(status().isCreated());
        send(post("/api/v1/notes"), admin, "{\"targetType\":\"SCREEN\",\"targetId\":\"" + screenId
                + "\",\"content\":\"저장 시 이력 남김\"}").andExpect(status().isCreated());

        JsonNode types = json(admin, "/api/v1/systems/" + systemId + "/deliverables");
        assertThat(types.findValuesAsText("type")).containsExactly("screen-spec", "program-spec", "table-spec",
                "crud-matrix", "interface-list");

        String base = "/api/v1/systems/" + systemId + "/deliverables/";
        byte[] spec = mvc.perform(get(base + "screen-spec").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(spec))) {
            assertThat(wb.getSheetName(0)).isEqualTo("화면목록");
            Sheet s = wb.getSheet("HR0101");
            assertThat(s).isNotNull();
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).contains("화면정의서").contains("HR0101");
            assertThat(wb.getAllPictures()).isNotEmpty();
            assertThat(text(s)).contains("btnSave", "POST /hr/emp/save.do", "저장 시 이력 남김");
        }
        byte[] pptx = mvc.perform(get(base + "screen-spec?format=pptx").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(pptx))) {
            assertThat(ppt.getSlides().size()).isGreaterThanOrEqualTo(3);   // 표지 + 화면 2
            boolean picture = false;
            for (XSLFSlide sl : ppt.getSlides()) {
                for (XSLFShape sh : sl.getShapes()) {
                    picture |= sh instanceof XSLFPictureShape;
                }
            }
            assertThat(picture).isTrue();
        }
        byte[] prog = mvc.perform(get(base + "program-spec").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(prog))) {
            String t = text(wb.getSheetAt(0));
            assertThat(t).contains("POST /hr/emp/save.do", "EmpServiceImpl.saveEmp()", "TB_EMP (CRU)");
        }
        mvc.perform(get(base + "program-spec?format=pptx").header("Authorization", admin)).andExpect(status().isOk());
        byte[] tbl = mvc.perform(get(base + "table-spec").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tbl))) {
            assertThat(text(wb.getSheet("컬럼정의"))).contains("TB_EMP", "DEPT_CD");
            assertThat(text(wb.getSheet("관계"))).contains("inferred");
        }
        byte[] crud = mvc.perform(get(base + "crud-matrix").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(crud))) {
            Row h = wb.getSheetAt(0).getRow(0);
            List<String> heads = new ArrayList<>();
            h.forEach(c -> heads.add(c.getStringCellValue()));
            assertThat(heads).contains("화면ID", "TB_EMP", "TB_DEPT");
            assertThat(text(wb.getSheetAt(0))).contains("CRU");
        }
        byte[] ifs = mvc.perform(get(base + "interface-list").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(ifs))) {
            assertThat(text(wb.getSheetAt(0))).contains("/hr/emp/save.do", "EmpController.saveEmp()", "HR0101");
        }
        // 형식·유형 오류, 템플릿 미지원 유형
        mvc.perform(get(base + "table-spec?format=pptx").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get(base + "nope").header("Authorization", admin)).andExpect(status().isNotFound());
        mvc.perform(get(base + "screen-spec?format=pptx&templateId=x").header("Authorization", admin))
                .andExpect(status().isBadRequest());

        // 템플릿 매핑 (프로그램 사양서 → as-program-spec 데이터셋)
        byte[] tpl;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("양식");
            sh.createRow(0).createCell(0).setCellValue("프로그램 사양서 (고객사 양식)");
            wb.write(bos);
            tpl = bos.toByteArray();
        }
        String meta = "{\"name\":\"사양서 양식\",\"datasetId\":\"program-spec\",\"mapping\":{\"sheet\":\"양식\","
                + "\"startRow\":4,\"columns\":{\"screenCode\":\"A\",\"element\":\"B\",\"apis\":\"C\"},"
                + "\"cells\":{\"systemName\":\"B2\"}}}";
        JsonNode t = body(mvc.perform(multipart("/api/v1/excel/templates")
                        .file(new MockMultipartFile("file", "양식.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", tpl))
                        .part(new MockPart("meta", meta.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", admin))
                .andExpect(status().isCreated()));
        byte[] mapped = mvc.perform(get(base + "program-spec?templateId=" + t.get("templateId").asText())
                        .header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(mapped))) {
            Sheet sh = wb.getSheet("양식");
            assertThat(sh.getRow(0).getCell(0).getStringCellValue()).isEqualTo("프로그램 사양서 (고객사 양식)");
            assertThat(text(sh)).contains("HR0101", "POST /hr/emp/save.do");
        }
        // 감사 로그
        JsonNode logs = json(admin, "/api/v1/admin/audit-logs?action=DOWNLOAD&q=deliverable");
        assertThat(logs.get("totalElements").asInt()).isGreaterThanOrEqualTo(5);
    }

    private static String text(Sheet s) {
        StringBuilder sb = new StringBuilder();
        for (Row r : s) {
            r.forEach(c -> sb.append(c.toString()).append('\t'));
            sb.append('\n');
        }
        return sb.toString();
    }

    @Test
    void 첨부_권한_대상유형별() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String[] ids = setup(admin, "R1F", null);
        String screenId = ids[1];
        MockMultipartFile file = new MockMultipartFile("file", "spec.pdf", "application/pdf", "%PDF-1.4".getBytes());
        mvc.perform(multipart("/api/v1/files").file(file).param("targetType", "AS_SCREEN").param("targetId", screenId)
                .header("Authorization", analyst)).andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/files").file(file).param("targetType", "UNKNOWN").param("targetId", "x")
                        .header("Authorization", analyst))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.targetType").exists());
        mvc.perform(multipart("/api/v1/files").file(file).param("targetType", "AS_SCREEN").param("targetId", "nope")
                .header("Authorization", analyst)).andExpect(status().isNotFound());

        // 프로젝트 비멤버는 조회·다운로드 불가
        String login = "outsider" + System.nanoTime() % 100000;
        send(post("/api/v1/admin/users"), admin, "{\"loginId\":\"" + login + "\",\"name\":\"외부인\",\"orgType\":"
                + "\"PARTNER\",\"authType\":\"LOCAL\",\"roles\":[],\"password\":\"Outsider!2026x\"}")
                .andExpect(status().isCreated());
        String token = om.readTree(mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + login + "\",\"password\":\"Outsider!2026x\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("accessToken").asText();
        String outsider = "Bearer " + token;
        mvc.perform(get("/api/v1/attachments?targetType=AS_SCREEN&targetId=" + screenId)
                .header("Authorization", outsider)).andExpect(status().isForbidden());
        String fileId = json(analyst, "/api/v1/attachments?targetType=AS_SCREEN&targetId=" + screenId).get(0)
                .get("fileId").asText();
        mvc.perform(get("/api/v1/files/" + fileId).header("Authorization", outsider))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/files/" + fileId).header("Authorization", analyst)).andExpect(status().isOk());

        // 캡처 이미지: 화면 표시용 조회는 감사 기록 없음, download=1 만 기록
        String captureId = json(admin, "/api/v1/screens/" + screenId).at("/capture/captureId").asText();
        long before = json(admin, "/api/v1/admin/audit-logs?action=DOWNLOAD&q=capture:" + captureId)
                .get("totalElements").asLong();
        mvc.perform(get("/api/v1/captures/" + captureId + "/image").header("Authorization", admin))
                .andExpect(status().isOk());
        assertThat(json(admin, "/api/v1/admin/audit-logs?action=DOWNLOAD&q=capture:" + captureId)
                .get("totalElements").asLong()).isEqualTo(before);
        mvc.perform(get("/api/v1/captures/" + captureId + "/image?download=1").header("Authorization", admin))
                .andExpect(status().isOk());
        assertThat(json(admin, "/api/v1/admin/audit-logs?action=DOWNLOAD&q=capture:" + captureId)
                .get("totalElements").asLong()).isEqualTo(before + 1);
    }

    @Test
    void 순환_호출과_SP_연쇄도_탐색이_끝나고_재방문을_표시() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String[] ids = setup(admin, "R1C", m -> {
            var sqls = m.withArray("sqlStatements");
            sqls.addObject().put("key", "sp:HR.P_A").put("kind", "procedure");
            sqls.addObject().put("key", "sp:HR.P_B").put("kind", "procedure");
            var edges = m.withArray("edges");
            // Nexacro 콜백처럼 fn_search → fn_save 로 되돌아가는 순환
            edges.addObject().put("from", "fn:js:/js/hr/HR0101.js#fn_search").put("to", "fn:js:/js/hr/HR0101.js#fn_save")
                    .put("kind", "calls").put("source", "static").put("confidence", 0.8);
            edges.addObject().put("from", "sql:EmpMapper.insertEmp").put("to", "sp:HR.P_A").put("kind", "calls")
                    .put("source", "static").put("confidence", 0.9);
            edges.addObject().put("from", "sp:HR.P_A").put("to", "sp:HR.P_B").put("kind", "calls")
                    .put("source", "static").put("confidence", 0.9);
            edges.addObject().put("from", "sp:HR.P_B").put("to", "sp:HR.P_A").put("kind", "calls")
                    .put("source", "static").put("confidence", 0.9);
            edges.addObject().put("from", "sp:HR.P_B").put("to", "tbl:HR.TB_DEPT").put("kind", "accesses")
                    .put("crud", "U").put("source", "static").put("confidence", 0.9);
        });
        String systemId = ids[0];
        String screenId = ids[1];
        JsonNode screen = json(admin, "/api/v1/screens/" + screenId);
        String btnSave = find(screen.get("elements"), "elementCode", "btnSave").get("elementId").asText();
        JsonNode steps = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> json(admin, "/api/v1/elements/" + btnSave + "/workflow")).at("/events/0/steps");
        List<String> revisits = new ArrayList<>();
        boolean spChain = false;
        boolean deptUpdate = false;
        for (JsonNode st : steps) {
            if (st.path("subtitle").asText("").contains("(위와 동일 흐름)")) {
                revisits.add(st.get("title").asText());
            }
            if ("HR.P_B".equals(st.get("title").asText())) {
                spChain = "SQL".equals(st.get("lane").asText());
            }
            if ("TB_DEPT".equals(st.get("title").asText()) && "U".equals(st.path("crud").asText())) {
                deptUpdate = true;
            }
        }
        assertThat(revisits).contains("fn_save()", "HR.P_A");
        assertThat(spChain).isTrue();
        assertThat(deptUpdate).isTrue();
        assertThat(steps.size()).isLessThan(60);

        String fnSave = nodeId(admin, systemId, "fn_save", "FN");
        JsonNode tree = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> json(admin, "/api/v1/nodes/" + fnSave + "/call-tree?direction=down&depth=6"));
        assertThat(tree.findValuesAsText("truncated")).contains("true");
        String spA = nodeId(admin, systemId, "P_A", "SP");
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10), () -> {
            json(admin, "/api/v1/nodes/" + spA + "/call-tree?direction=up&depth=6");
            json(admin, "/api/v1/nodes/" + spA + "/usage");
            JsonNode crud = json(admin, "/api/v1/systems/" + systemId + "/crud?by=element");
            String dept = find(crud.get("tables"), "name", "TB_DEPT").get("nodeId").asText();
            assertThat(find(crud.get("rows"), "label", "저장").at("/cells/" + dept).asText()).contains("U");
            String deptTable = find(json(admin, "/api/v1/systems/" + systemId + "/tables").get("content"), "name",
                    "TB_DEPT").get("tableId").asText();
            assertThat(json(admin, "/api/v1/tables/" + deptTable).get("usedBy").findValuesAsText("sqlName"))
                    .contains("HR.P_B");
        });
    }

    /** 수집기 R1 실제 샘플 패키지 (hr-legacy·hr-nexacro·hr-spa) 적재 — 샘플이 없으면 건너뜀 */
    @Test
    void 수집기_샘플_패키지_세가지_적재와_조회() throws Exception {
        java.nio.file.Path samples = java.nio.file.Path.of("..", "samples");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(samples), "samples 폴더 없음");
        String admin = TestSupport.login(mvc, om, "admin");
        String projectId = newProject(admin);
        int loaded = 0;
        for (String[] spec : new String[][] {{"hr-legacy", "HR"}, {"hr-nexacro", "PY"}, {"hr-spa", "ED"},
                {"hr-winforms", "HW"}, {"hr-websquare", "BW"}, {"hr-xplatform", "AT"}, {"hr-aspnet", "RC"}}) {
            java.nio.file.Path zip = latestZip(samples.resolve(spec[0]).resolve("out"));
            if (zip == null) {
                continue;
            }
            JsonNode sys = body(send(post("/api/v1/projects/" + projectId + "/systems"), admin, "{\"code\":\""
                    + spec[1] + "\",\"name\":\"샘플 " + spec[1] + "\",\"uiTech\":[],\"serverTech\":[],"
                    + "\"dbType\":\"oracle\"}").andExpect(status().isCreated()));
            String systemId = sys.get("systemId").asText();
            JsonNode run = body(mvc.perform(multipart("/api/v1/systems/" + systemId + "/runs")
                            .file(new MockMultipartFile("file", zip.getFileName().toString(), "application/zip",
                                    java.nio.file.Files.readAllBytes(zip)))
                            .header("Authorization", admin))
                    .andExpect(status().isCreated()));
            loaded++;
            assertThat(run.at("/diff/screensAdded").asInt()).as(spec[0]).isGreaterThan(0);
            assertThat(json(admin, "/api/v1/systems/" + systemId + "/menu-tree")).as(spec[0]).isNotEmpty();
            JsonNode grid = json(admin, "/api/v1/systems/" + systemId + "/analysis/screens?size=200");
            int workflows = 0;
            int frontSteps = 0;
            for (JsonNode row : grid.get("content")) {
                JsonNode screen = json(admin, "/api/v1/screens/" + row.get("screenId").asText());
                if ("PY".equals(spec[1]) && screen.hasNonNull("capture")) {
                    assertThat(screen.at("/capture/state").asText()).isEqualTo("wireframe");
                }
                for (JsonNode el : screen.get("elements")) {
                    JsonNode wf = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                            java.time.Duration.ofSeconds(10),
                            () -> json(admin, "/api/v1/elements/" + el.get("elementId").asText() + "/workflow"));
                    if (wf.get("events").size() > 0) {
                        workflows++;
                    }
                    for (JsonNode ev : wf.get("events")) {
                        for (JsonNode st : ev.get("steps")) {
                            if ("FRONT".equals(st.get("lane").asText())) {
                                frontSteps++;
                            }
                        }
                    }
                }
            }
            assertThat(workflows).as(spec[0] + " 워크플로우가 있는 요소").isGreaterThan(0);
            if ("HW".equals(spec[1]) || "RC".equals(spec[1])) {
                // WinForms·WebForms: 코드비하인드(cs)는 화면 코드(FRONT)로 표시
                assertThat(frontSteps).as(spec[0] + " 화면 코드 단계").isGreaterThan(0);
            }
            json(admin, "/api/v1/systems/" + systemId + "/crud");
            json(admin, "/api/v1/systems/" + systemId + "/navigation");
            json(admin, "/api/v1/systems/" + systemId + "/erd");
            json(admin, "/api/v1/systems/" + systemId + "/analysis/progress");
            mvc.perform(get("/api/v1/systems/" + systemId + "/deliverables/screen-spec").header("Authorization", admin))
                    .andExpect(status().isOk());
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(loaded > 0, "샘플 패키지 없음");
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    /** 샘플 전용 새 프로젝트 (시드 HR 시스템과 코드 충돌 방지), admin 을 PM 으로 */
    private String newProject(String admin) throws Exception {
        String id = kr.pmsx.common.Ulid.next();
        String adminId = json(admin, "/api/v1/auth/me").get("userId").asText();
        jdbc.update("INSERT INTO PJ_PROJECT (PROJECT_ID, TYPE, NAME, CUSTOMER, CREATED_AT) VALUES (?, 'SI', ?, '샘플', ?)",
                id, "샘플 프로젝트 " + id.substring(20), kr.pmsx.common.Times.now());
        jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID) VALUES (?, ?, 'PM')", id, adminId);
        return id;
    }

    private static java.nio.file.Path latestZip(java.nio.file.Path dir) throws java.io.IOException {
        if (!java.nio.file.Files.isDirectory(dir)) {
            return null;
        }
        try (var s = java.nio.file.Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".zip"))
                    .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElse(null);
        }
    }

    @Test
    void 워크플로우_lane_규칙_확장() {
        assertThat(WorkflowService.lane("FN", "vue")).isEqualTo("FRONT");
        assertThat(WorkflowService.stem("Forms/EmpForm.Designer.cs")).isEqualTo("empform");
        assertThat(WorkflowService.stem("Forms\\EmpForm.cs")).isEqualTo("empform");
        assertThat(WorkflowService.stem("Emp/List.aspx.cs")).isEqualTo(WorkflowService.stem("Emp/List.aspx"));
        assertThat(WorkflowService.stem("winforms:HrWin.Forms.EmpForm")).isEqualTo("empform");
        assertThat(WorkflowService.lane("FN", "xjs")).isEqualTo("FRONT");
        assertThat(WorkflowService.lane("FN", "tsx")).isEqualTo("FRONT");
        assertThat(WorkflowService.lane("FN", "XFDL")).isEqualTo("FRONT");
        assertThat(WorkflowService.lane("FN", "cs")).isEqualTo("SERVER");
        assertThat(WorkflowService.lane("FN", "vb")).isEqualTo("SERVER");
        assertThat(WorkflowService.lane("FN", "java")).isEqualTo("SERVER");
        assertThat(WorkflowService.lane("SP", null)).isEqualTo("SQL");
    }
}
