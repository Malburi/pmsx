package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import kr.pmsx.cm.notify.DueScheduler;
import kr.pmsx.common.Times;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** docs/12 R2: WBS·CPM·베이스라인·진척, 인력·작업일지, 산출물·추적성, 품질, 알림·대시보드, 권한 매트릭스 핵심 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class R2FlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DueScheduler due;

    String admin;

    @BeforeEach
    void login() throws Exception {
        admin = TestSupport.login(mvc, om, "admin");
    }

    // ───────────────────────── 도우미 ─────────────────────────

    JsonNode body(ResultActions r) throws Exception {
        String s = r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return s.isEmpty() ? om.nullNode() : om.readTree(s);
    }

    ResultActions send(MockHttpServletRequestBuilder b, String auth, Object json) throws Exception {
        b.header("Authorization", auth);
        if (json != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json instanceof String s ? s : om.writeValueAsString(json));
        }
        return mvc.perform(b);
    }

    JsonNode ok(MockHttpServletRequestBuilder b, String auth, Object json) throws Exception {
        return body(send(b, auth, json).andExpect(status().is2xxSuccessful()));
    }

    JsonNode json(String auth, String url) throws Exception {
        return TestSupport.json(mvc, om, auth, url);
    }

    static String uniq(String p) {
        return p + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    /** 새 사용자(비밀번호 pmsx1234!) → userId */
    String newUser(String login, String name, String orgType) throws Exception {
        return ok(post("/api/v1/admin/users"), admin, String.format(
                "{\"loginId\":\"%s\",\"name\":\"%s\",\"orgType\":\"%s\",\"authType\":\"LOCAL\",\"roles\":[],"
                        + "\"password\":\"pmsx1234!\"}", login, name, orgType)).get("userId").asText();
    }

    void addMember(String projectId, String userId, String role) throws Exception {
        ok(post("/api/v1/projects/" + projectId + "/members"), admin,
                String.format("{\"userId\":\"%s\",\"roleId\":\"%s\"}", userId, role));
    }

    String adminId() throws Exception {
        return json(admin, "/api/v1/auth/me").get("userId").asText();
    }

    /** admin 을 PM 으로 하는 새 프로젝트 */
    String newProject(String type) throws Exception {
        return ok(post("/api/v1/projects"), admin, String.format(
                "{\"type\":\"%s\",\"name\":\"%s\",\"customer\":\"테스트 고객\",\"startDate\":\"2026-11-02\","
                        + "\"endDate\":\"2027-10-29\",\"contractMm\":60,\"pmUserId\":\"%s\"}", type, uniq("R2 테스트 "),
                adminId())).get("projectId").asText();
    }

    static JsonNode byName(JsonNode arr, String field, String value) {
        for (JsonNode n : arr) {
            if (value.equals(n.get(field).asText())) {
                return n;
            }
        }
        throw new AssertionError(field + "=" + value + " 없음");
    }

    // ───────────────────────── WBS·CPM ─────────────────────────

    @Test
    void WBS_일괄저장_롤업_크리티컬패스_순환검사_베이스라인() throws Exception {
        String pid = newProject("SI");
        String wbs = "[" +
                "{\"wbsId\":\"t-p\",\"name\":\"설계\",\"weight\":1,\"progress\":0,\"milestone\":false,\"deps\":[]}," +
                "{\"wbsId\":\"t-a\",\"parentId\":\"t-p\",\"name\":\"A\",\"planStart\":\"2026-11-02\",\"planEnd\":\"2026-11-04\","
                + "\"weight\":1,\"progress\":100,\"status\":\"DONE\",\"milestone\":false,\"deps\":[]}," +
                "{\"wbsId\":\"t-b\",\"parentId\":\"t-p\",\"name\":\"B\",\"planStart\":\"2026-11-05\",\"planEnd\":\"2026-11-06\","
                + "\"weight\":1,\"progress\":0,\"milestone\":false,\"deps\":[{\"predId\":\"t-a\",\"type\":\"FS\",\"lagDays\":0}]}," +
                "{\"wbsId\":\"t-c\",\"parentId\":\"t-p\",\"name\":\"C\",\"planStart\":\"2026-11-02\",\"planEnd\":\"2026-11-02\","
                + "\"weight\":2,\"progress\":0,\"milestone\":false,\"deps\":[]}," +
                "{\"wbsId\":\"t-m\",\"name\":\"설계 완료\",\"planStart\":\"2026-11-09\",\"planEnd\":\"2026-11-09\","
                + "\"weight\":0.1,\"progress\":0,\"milestone\":true,\"deps\":[{\"predId\":\"t-b\",\"type\":\"FS\",\"lagDays\":0}]}]";
        JsonNode items = ok(put("/api/v1/projects/" + pid + "/wbs"), admin, wbs);
        assertThat(items.findValuesAsText("wbsNo")).containsExactly("1", "1.1", "1.2", "1.3", "2");
        JsonNode p = byName(items, "name", "설계");
        assertThat(p.get("progress").asDouble()).isEqualTo(25.0);   // (1×100 + 1×0 + 2×0) / 4
        assertThat(p.get("planStart").asText()).isEqualTo("2026-11-02");
        assertThat(p.get("planEnd").asText()).isEqualTo("2026-11-06");
        assertThat(byName(items, "name", "A").get("critical").asBoolean()).isTrue();
        assertThat(byName(items, "name", "B").get("critical").asBoolean()).isTrue();
        assertThat(byName(items, "name", "설계 완료").get("critical").asBoolean()).isTrue();
        assertThat(byName(items, "name", "C").get("critical").asBoolean()).isFalse();
        assertThat(p.get("critical").asBoolean()).isTrue();

        // 순환
        String a = byName(items, "name", "A").get("wbsId").asText();
        String b = byName(items, "name", "B").get("wbsId").asText();
        List<JsonNode> list = new ArrayList<>();
        items.forEach(list::add);
        String cyc = om.writeValueAsString(list).replace("\"deps\":[]", "\"deps\":[]");
        JsonNode copy = om.readTree(cyc);
        ((com.fasterxml.jackson.databind.node.ArrayNode) byName(copy, "name", "A").get("deps"))
                .add(om.readTree("{\"predId\":\"" + b + "\",\"type\":\"FS\",\"lagDays\":0}"));
        JsonNode err = body(send(put("/api/v1/projects/" + pid + "/wbs"), admin, copy).andExpect(status().isBadRequest()));
        assertThat(err.get("code").asText()).isEqualTo("WBS_CYCLE");

        // 상위 진척 수정 불가, 말단 PATCH
        String parentId = p.get("wbsId").asText();
        send(patch("/api/v1/wbs/" + parentId), admin, "{\"progress\":50}").andExpect(status().isBadRequest());
        JsonNode pb = ok(patch("/api/v1/wbs/" + b), admin, "{\"progress\":40}");
        assertThat(pb.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(pb.get("actStart").isNull()).isFalse();

        // 베이스라인 → 일정 변경 → 비교
        ok(post("/api/v1/projects/" + pid + "/baselines"), admin, "{\"name\":\"착수\"}");
        ok(patch("/api/v1/wbs/" + a), admin, "{\"planEnd\":\"2026-11-05\"}");
        JsonNode bl = json(admin, "/api/v1/projects/" + pid + "/baselines");
        JsonNode cmp = json(admin, "/api/v1/baselines/" + bl.get(0).get("baselineId").asText() + "/compare");
        assertThat(byName(cmp, "name", "A").get("endVarDays").asInt()).isEqualTo(1);
        assertThat(byName(json(admin, "/api/v1/projects/" + pid + "/wbs"), "name", "A").get("baselineEnd").asText())
                .isEqualTo("2026-11-04");

        // 진척: 2026-11-04 기준 계획
        JsonNode prog = json(admin, "/api/v1/projects/" + pid + "/progress?asOf=2026-11-30");
        assertThat(prog.get("planned").asDouble()).isEqualTo(100.0);
        assertThat(prog.get("sCurve").size()).isGreaterThan(0);
        assertThat(prog.get("byPhase").size()).isEqualTo(2);
    }

    @Test
    void 방법론으로_WBS_생성_산출물_승인시_작업완료() throws Exception {
        String pid = newProject("SI");
        JsonNode m = json(admin, "/api/v1/methodologies");
        String mid = m.get(0).get("methodologyId").asText();
        JsonNode items = ok(post("/api/v1/projects/" + pid + "/wbs/generate"), admin,
                "{\"methodologyId\":\"" + mid + "\",\"startDate\":\"2026-11-02\",\"replace\":true}");
        assertThat(items.size()).isGreaterThanOrEqualTo(15);
        assertThat(items.get(0).get("wbsNo").asText()).isEqualTo("1");
        JsonNode deliverables = json(admin, "/api/v1/projects/" + pid + "/deliverables");
        assertThat(deliverables.size()).isGreaterThan(5);
        JsonNode d = deliverables.get(0);
        String did = d.get("deliverableId").asText();
        String wbsId = d.get("wbsId").asText();
        ok(post("/api/v1/deliverables/" + did + "/request-review"), admin, "{\"comment\":\"검토 부탁\"}");
        // 다른 상태에서 승인은 409, 반려는 사유 필요
        send(post("/api/v1/deliverables/" + did + "/reject"), admin, "{}").andExpect(status().isBadRequest());
        JsonNode ap = ok(post("/api/v1/deliverables/" + did + "/approve"), admin, "{}");
        assertThat(ap.get("status").asText()).isEqualTo("APPROVED");
        send(post("/api/v1/deliverables/" + did + "/approve"), admin, "{}").andExpect(status().isConflict());
        JsonNode w = byName(json(admin, "/api/v1/projects/" + pid + "/wbs"), "wbsId", wbsId);
        assertThat(w.get("progress").asDouble()).isEqualTo(100.0);
        assertThat(w.get("status").asText()).isEqualTo("DONE");
        assertThat(json(admin, "/api/v1/deliverables/" + did + "/history").findValuesAsText("action"))
                .contains("REQUEST_REVIEW", "APPROVE");
    }

    @Test
    void MS_Project_XML_가져오기() throws Exception {
        String pid = newProject("SI");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Project xmlns="http://schemas.microsoft.com/project">
                  <Tasks>
                    <Task><UID>0</UID><ID>0</ID><Name>프로젝트</Name><OutlineLevel>0</OutlineLevel></Task>
                    <Task><UID>1</UID><ID>1</ID><Name>분석</Name><OutlineLevel>1</OutlineLevel>
                      <Start>2026-11-02T09:00:00</Start><Finish>2026-11-13T18:00:00</Finish></Task>
                    <Task><UID>2</UID><ID>2</ID><Name>요구사항 정의</Name><OutlineLevel>2</OutlineLevel>
                      <Start>2026-11-02T09:00:00</Start><Finish>2026-11-06T18:00:00</Finish><PercentComplete>100</PercentComplete></Task>
                    <Task><UID>3</UID><ID>3</ID><Name>현행 분석</Name><OutlineLevel>2</OutlineLevel>
                      <Start>2026-11-09T09:00:00</Start><Finish>2026-11-13T18:00:00</Finish>
                      <PredecessorLink><PredecessorUID>2</PredecessorUID><Type>1</Type><LinkLag>4800</LinkLag></PredecessorLink></Task>
                    <Task><UID>4</UID><ID>4</ID><Name>분석 완료</Name><OutlineLevel>1</OutlineLevel><Milestone>1</Milestone>
                      <Start>2026-11-13T18:00:00</Start><Finish>2026-11-13T18:00:00</Finish></Task>
                  </Tasks>
                </Project>""";
        JsonNode items = body(mvc.perform(multipart("/api/v1/projects/" + pid + "/wbs/import-msproject")
                .file(new MockMultipartFile("file", "plan.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8)))
                .param("replace", "true").header("Authorization", admin)).andExpect(status().isOk()));
        assertThat(items.findValuesAsText("wbsNo")).containsExactly("1", "1.1", "1.2", "2");
        JsonNode cur = byName(items, "name", "현행 분석");
        assertThat(cur.get("deps").get(0).get("lagDays").asInt()).isEqualTo(1);
        assertThat(byName(items, "name", "요구사항 정의").get("status").asText()).isEqualTo("DONE");
        assertThat(byName(items, "name", "분석 완료").get("milestone").asBoolean()).isTrue();
        // XXE 차단
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///c:/windows/win.ini\">]><Project>&e;</Project>";
        mvc.perform(multipart("/api/v1/projects/" + pid + "/wbs/import-msproject")
                .file(new MockMultipartFile("file", "x.xml", "application/xml", evil.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", admin)).andExpect(status().isBadRequest());
    }

    // ───────────────────────── 인력·작업일지 ─────────────────────────

    @Test
    void 배정_경고_작업일지_제출_승인_MM실적() throws Exception {
        String pid = newProject("SI");
        String devLogin = uniq("dev");
        String devId = newUser(devLogin, "개발자", "PARTNER");
        addMember(pid, devId, "DEV");
        String dev = TestSupport.login(mvc, om, devLogin);
        JsonNode items = ok(put("/api/v1/projects/" + pid + "/wbs"), admin, "[" +
                "{\"wbsId\":\"x1\",\"name\":\"개발1\",\"planStart\":\"2026-11-02\",\"planEnd\":\"2026-11-13\",\"weight\":1,\"progress\":0,\"milestone\":false,\"deps\":[]}," +
                "{\"wbsId\":\"x2\",\"name\":\"개발2\",\"planStart\":\"2026-11-09\",\"planEnd\":\"2026-11-20\",\"weight\":1,\"progress\":0,\"milestone\":false,\"deps\":[]}]");
        String w1 = byName(items, "name", "개발1").get("wbsId").asText();
        String w2 = byName(items, "name", "개발2").get("wbsId").asText();
        assertThat(json(admin, "/api/v1/projects/" + pid + "/work-items/unassigned?type=WBS").size()).isEqualTo(2);
        JsonNode a1 = ok(post("/api/v1/assignments"), admin, "{\"targetType\":\"WBS\",\"targetId\":\"" + w1
                + "\",\"userId\":\"" + devId + "\",\"allocPct\":80}");
        assertThat(a1.get("warning").isNull()).isTrue();
        JsonNode a2 = ok(post("/api/v1/assignments"), admin, "{\"targetType\":\"WBS\",\"targetId\":\"" + w2
                + "\",\"userId\":\"" + devId + "\",\"allocPct\":50}");
        assertThat(a2.get("warning").asText()).contains("130%");
        // 배정 알림
        JsonNode noti = json(dev, "/api/v1/notifications?unread=true");
        assertThat(noti.get("content").findValuesAsText("event")).contains("ASSIGNED");
        JsonNode heat = json(admin, "/api/v1/resources/heatmap?from=2026-11-02&to=2026-11-22&unit=week&projectId=" + pid);
        assertThat(byName(heat, "userId", devId).get("cells").get("2026-W46").asInt()).isEqualTo(130);
        // 비멤버 배정 불가, DEV 는 배정 권한 없음
        send(post("/api/v1/assignments"), dev, "{\"targetType\":\"WBS\",\"targetId\":\"" + w1 + "\",\"userId\":\""
                + devId + "\"}").andExpect(status().isForbidden());

        // 작업일지: 본인 저장·제출 → PM 승인 → MM 실적
        LocalDate ws = LocalDate.of(2026, 11, 2);
        String ts = "{\"weekStart\":\"" + ws + "\",\"entries\":[" +
                "{\"date\":\"2026-11-02\",\"targetType\":\"WBS\",\"targetId\":\"" + w1 + "\",\"hours\":8}," +
                "{\"date\":\"2026-11-03\",\"targetType\":\"WBS\",\"targetId\":\"" + w1 + "\",\"hours\":8.4}]}";
        JsonNode saved = ok(put("/api/v1/timesheets"), dev, ts);
        String tsId = saved.get("timesheetId").asText();
        assertThat(saved.get("entries").get(0).get("targetLabel").asText()).contains("개발1");
        send(put("/api/v1/timesheets"), dev, "{\"weekStart\":\"2026-11-02\",\"entries\":[{\"date\":\"2026-11-02\","
                + "\"targetType\":\"WBS\",\"targetId\":\"" + w1 + "\",\"hours\":20},{\"date\":\"2026-11-02\","
                + "\"targetType\":\"ETC\",\"targetLabel\":\"교육\",\"hours\":5}]}").andExpect(status().isBadRequest());
        ok(post("/api/v1/timesheets/" + tsId + "/submit"), dev, "{}");
        send(put("/api/v1/timesheets"), dev, ts).andExpect(status().isConflict());
        assertThat(json(admin, "/api/v1/projects/" + pid + "/timesheets/pending").size()).isEqualTo(1);
        send(post("/api/v1/timesheets/" + tsId + "/approve"), dev, "{}").andExpect(status().isForbidden());
        ok(post("/api/v1/timesheets/" + tsId + "/approve"), admin, "{}");
        JsonNode mm = json(admin, "/api/v1/projects/" + pid + "/mm-summary?from=2026-11&to=2026-11");
        assertThat(byName(mm, "userId", devId).get("actual").get("2026-11").asDouble()).isEqualTo(0.1);   // 16.4h / 168
        assertThat(byName(json(admin, "/api/v1/projects/" + pid + "/wbs"), "wbsId", w1).get("actStart").asText())
                .isEqualTo("2026-11-02");
        // DEV 는 MM 실적 조회 불가
        mvc.perform(get("/api/v1/projects/" + pid + "/mm-summary").header("Authorization", dev))
                .andExpect(status().isForbidden());
        // MM 계획 저장·조회
        ok(put("/api/v1/projects/" + pid + "/mm-plan"), admin, "[{\"userId\":\"" + devId
                + "\",\"months\":{\"2026-11\":1.0,\"2026-12\":0.5}}]");
        assertThat(byName(json(admin, "/api/v1/projects/" + pid + "/mm-plan"), "userId", devId).get("months")
                .get("2026-12").asDouble()).isEqualTo(0.5);
        // 투입 체크리스트 기본 항목
        JsonNode check = json(admin, "/api/v1/projects/" + pid + "/members/" + devId + "/checklist");
        assertThat(check.size()).isGreaterThanOrEqualTo(5);
    }

    // ───────────────────────── 품질·추적성 ─────────────────────────

    @Test
    void 결함_전이규칙_테스트결과_추적성매트릭스_품질리포트() throws Exception {
        String pid = newProject("SI");
        String devLogin = uniq("dv");
        String devId = newUser(devLogin, "개발자", "INTERNAL");
        addMember(pid, devId, "DEV");
        String req = ok(post("/api/v1/projects/" + pid + "/requirements"), admin,
                "{\"title\":\"사원 등록\",\"priority\":\"H\"}").get("reqId").asText();
        String req2 = ok(post("/api/v1/projects/" + pid + "/requirements"), admin,
                "{\"title\":\"사원 조회\"}").get("reqId").asText();
        JsonNode sc = ok(post("/api/v1/projects/" + pid + "/test-scenarios"), admin,
                "{\"level\":\"INTEGRATION\",\"name\":\"인사\",\"cases\":[{\"title\":\"등록\",\"reqIds\":[\"" + req + "\"]}]}");
        String caseId = sc.get("cases").get(0).get("caseId").asText();
        assertThat(sc.get("cases").get(0).get("tcNo").asText()).isEqualTo("TC-0001");
        String round = ok(post("/api/v1/projects/" + pid + "/test-rounds"), admin, "{\"name\":\"1차\"}")
                .get("roundId").asText();
        JsonNode d = ok(post("/api/v1/projects/" + pid + "/defects"), admin,
                "{\"title\":\"저장 오류\",\"severity\":\"CRITICAL\",\"caseId\":\"" + caseId + "\"}");
        String did = d.get("defectId").asText();
        assertThat(d.get("status").asText()).isEqualTo("NEW");
        assertThat(d.get("defectNo").asText()).isEqualTo("DEF-0001");
        ok(put("/api/v1/test-rounds/" + round + "/results"), admin,
                "[{\"caseId\":\"" + caseId + "\",\"result\":\"FAIL\",\"defectId\":\"" + did + "\"}]");

        // 전이 규칙
        JsonNode e = body(send(post("/api/v1/defects/" + did + "/transition"), admin, "{\"to\":\"FIXED\"}")
                .andExpect(status().isConflict()));
        assertThat(e.get("code").asText()).isEqualTo("INVALID_TRANSITION");
        send(post("/api/v1/defects/" + did + "/transition"), admin, "{\"to\":\"ASSIGNED\"}")
                .andExpect(status().isBadRequest());   // 담당자 없음
        JsonNode upd = ok(put("/api/v1/defects/" + did), admin, "{\"title\":\"저장 오류\",\"severity\":\"CRITICAL\","
                + "\"assigneeId\":\"" + devId + "\",\"caseId\":\"" + caseId + "\"}");
        assertThat(upd.get("status").asText()).isEqualTo("ASSIGNED");
        String dev = TestSupport.login(mvc, om, devLogin);
        assertThat(json(dev, "/api/v1/notifications").get("content").findValuesAsText("event"))
                .contains("DEFECT_ASSIGNED");
        for (String to : List.of("FIXED", "REOPENED", "ASSIGNED", "FIXED", "VERIFIED", "CLOSED")) {
            ok(post("/api/v1/defects/" + did + "/transition"), dev, "{\"to\":\"" + to + "\"}");
        }
        send(post("/api/v1/defects/" + did + "/transition"), admin, "{\"to\":\"REOPENED\"}")
                .andExpect(status().isConflict());   // CLOSED 이후 전이 없음

        // 추적성: req 는 TC(FAIL)·결함(종료) 연결, req2 는 테스트 없음
        JsonNode mx = json(admin, "/api/v1/projects/" + pid + "/trace-matrix");
        for (JsonNode r : mx) {
            String id = r.at("/req/reqId").asText();
            if (id.equals(req)) {
                assertThat(r.get("gaps").toString()).contains("TEST_FAILED", "NO_SCREEN").doesNotContain("OPEN_DEFECT");
                assertThat(r.get("defects").get(0).get("status").asText()).isEqualTo("CLOSED");
            } else if (id.equals(req2)) {
                assertThat(r.get("gaps").toString()).contains("NO_TEST");
            }
        }
        JsonNode qr = json(admin, "/api/v1/projects/" + pid + "/quality-report");
        assertThat(qr.get("reopenRate").asDouble()).isEqualTo(100.0);
        assertThat(qr.at("/bySeverity/CRITICAL").asInt()).isEqualTo(1);
        assertThat(qr.get("testProgress").get(0).get("fail").asInt()).isEqualTo(1);
        assertThat(json(admin, "/api/v1/defects/" + did + "/history").size()).isGreaterThanOrEqualTo(7);
    }

    @Test
    void 추적성_매트릭스_갭과_핫스팟_결함_연결이력() throws Exception {
        String pid = newProject("SI");
        String sysId = ok(post("/api/v1/projects/" + pid + "/systems"), admin, "{\"code\":\"TR\",\"name\":\"추적\","
                + "\"uiTech\":[\"jsp-jquery\"],\"serverTech\":[],\"dbType\":\"oracle\"}").get("systemId").asText();
        mvc.perform(multipart("/api/v1/systems/" + sysId + "/runs")
                .file(new MockMultipartFile("file", "p.zip", "application/zip", TestSupport.fixtureZip(om,
                        m -> ((com.fasterxml.jackson.databind.node.ObjectNode) m.get("system")).put("code", "TR")
                                .put("key", "sys:TR"))))
                .header("Authorization", admin)).andExpect(status().isCreated());
        JsonNode grid = json(admin, "/api/v1/systems/" + sysId + "/analysis/screens?size=50");
        String screenId = grid.get("content").get(0).get("screenId").asText();
        String elementId = json(admin, "/api/v1/screens/" + screenId).get("elements").get(0).get("elementId").asText();

        String req = ok(post("/api/v1/projects/" + pid + "/requirements"), admin, "{\"title\":\"요구1\"}")
                .get("reqId").asText();
        String req2 = ok(post("/api/v1/projects/" + pid + "/requirements"), admin, "{\"title\":\"요구2\"}")
                .get("reqId").asText();
        ok(post("/api/v1/trace-links"), admin, "{\"fromType\":\"REQ\",\"fromId\":\"" + req + "\",\"toType\":\"ELEMENT\","
                + "\"toId\":\"" + elementId + "\"}");
        send(post("/api/v1/trace-links"), admin, "{\"fromType\":\"ELEMENT\",\"fromId\":\"" + elementId
                + "\",\"toType\":\"REQ\",\"toId\":\"" + req + "\"}").andExpect(status().isConflict());
        JsonNode sc = ok(post("/api/v1/projects/" + pid + "/test-scenarios"), admin,
                "{\"level\":\"UNIT\",\"name\":\"s\",\"cases\":[{\"title\":\"c1\",\"reqIds\":[\"" + req + "\"]}]}");
        String caseId = sc.get("cases").get(0).get("caseId").asText();
        String round = ok(post("/api/v1/projects/" + pid + "/test-rounds"), admin, "{\"name\":\"1차\"}")
                .get("roundId").asText();
        ok(put("/api/v1/test-rounds/" + round + "/results"), admin, "[{\"caseId\":\"" + caseId + "\",\"result\":\"FAIL\"}]");
        // 핫스팟 결함 (요소 지정 → 화면 자동)
        JsonNode hd = ok(post("/api/v1/projects/" + pid + "/defects"), admin, "{\"title\":\"버튼 오류\",\"severity\":\"MINOR\","
                + "\"elementId\":\"" + elementId + "\",\"caseId\":\"" + caseId + "\"}");
        assertThat(hd.get("screenId").asText()).isEqualTo(screenId);

        JsonNode mx = json(admin, "/api/v1/projects/" + pid + "/trace-matrix");
        JsonNode row1 = null;
        JsonNode row2 = null;
        for (JsonNode r : mx) {
            if (r.at("/req/reqId").asText().equals(req)) {
                row1 = r;
            }
            if (r.at("/req/reqId").asText().equals(req2)) {
                row2 = r;
            }
        }
        assertThat(row1.get("screens").get(0).get("id").asText()).isEqualTo(screenId);
        assertThat(row1.get("tests").get(0).get("lastResult").asText()).isEqualTo("FAIL");
        assertThat(row1.get("gaps").toString()).contains("TEST_FAILED", "OPEN_DEFECT").doesNotContain("NO_SCREEN");
        assertThat(row2.get("gaps").toString()).contains("NO_SCREEN", "NO_TEST");

        JsonNode links = json(admin, "/api/v1/elements/" + elementId + "/links");
        assertThat(links.get("links").findValuesAsText("fromType")).contains("REQ");
        assertThat(links.get("defects").get(0).get("defectNo").asText()).startsWith("DEF-");
        assertThat(links.get("defects").get(0).get("severity").asText()).isEqualTo("MINOR");
        assertThat(links.get("issues").size()).isZero();
        JsonNode proj = byName(json(admin, "/api/v1/projects"), "projectId", pid);
        assertThat(proj.get("pmName").asText()).isEqualTo("관리자");
        assertThat(proj.get("contractMm").asInt()).isEqualTo(60);

        // 요구사항 변경 이력
        ok(put("/api/v1/requirements/" + req), admin, "{\"title\":\"요구1 수정\",\"status\":\"ACCEPTED\"}");
        assertThat(json(admin, "/api/v1/requirements/" + req + "/history").findValuesAsText("field"))
                .contains("title", "status");
    }

    @Test
    void 이슈_에스컬레이션_리스크_이슈전환_CR전이_회의록_주간보고() throws Exception {
        String pid = newProject("SI");
        String plLogin = uniq("pl");
        String plId = newUser(plLogin, "파트리더", "INTERNAL");
        addMember(pid, plId, "PL");
        JsonNode iss = ok(post("/api/v1/projects/" + pid + "/issues"), admin, "{\"title\":\"계정 미발급\",\"priority\":\"H\","
                + "\"ownerId\":\"" + plId + "\",\"dueDate\":\"" + LocalDate.now(Times.ZONE).minusDays(1) + "\"}");
        String issueId = iss.get("issueId").asText();
        assertThat(iss.get("issueNo").asText()).isEqualTo("ISS-0001");
        ok(post("/api/v1/issues/" + issueId + "/comments"), admin, "{\"content\":\"@" + plLogin + " 확인 부탁\"}");
        String pl = TestSupport.login(mvc, om, plLogin);
        assertThat(json(pl, "/api/v1/notifications").get("content").findValuesAsText("event"))
                .contains("ASSIGNED", "MENTION");
        ok(post("/api/v1/issues/" + issueId + "/escalate"), pl, null);
        assertThat(ok(post("/api/v1/issues/" + issueId + "/escalate"), pl, null).get("escalation").asInt()).isEqualTo(2);
        send(post("/api/v1/issues/" + issueId + "/escalate"), pl, null).andExpect(status().isConflict());
        assertThat(json(admin, "/api/v1/issues/" + issueId).get("comments").size()).isEqualTo(1);

        JsonNode risk = ok(post("/api/v1/projects/" + pid + "/risks"), admin,
                "{\"title\":\"인력 이탈\",\"probability\":4,\"impact\":5,\"response\":\"대체 인력\"}");
        assertThat(risk.get("score").asInt()).isEqualTo(20);
        JsonNode fromRisk = ok(post("/api/v1/risks/" + risk.get("riskId").asText() + "/to-issue"), admin, null);
        assertThat(fromRisk.get("priority").asText()).isEqualTo("H");
        send(post("/api/v1/risks/" + risk.get("riskId").asText() + "/to-issue"), admin, null)
                .andExpect(status().isConflict());

        JsonNode cr = ok(post("/api/v1/projects/" + pid + "/change-requests"), admin,
                "{\"title\":\"모바일 추가\",\"impact\":{\"scope\":\"화면 1\",\"mmDelta\":1.5,\"scheduleDeltaDays\":10}}");
        String crId = cr.get("crId").asText();
        send(post("/api/v1/change-requests/" + crId + "/transition"), admin, "{\"to\":\"APPLIED\"}")
                .andExpect(status().isConflict());
        ok(post("/api/v1/change-requests/" + crId + "/transition"), admin, "{\"to\":\"ANALYZED\"}");
        send(post("/api/v1/change-requests/" + crId + "/transition"), pl, "{\"to\":\"APPROVED\"}")
                .andExpect(status().isForbidden());   // PL 은 승인 불가
        assertThat(ok(post("/api/v1/change-requests/" + crId + "/transition"), admin, "{\"to\":\"APPROVED\"}")
                .get("status").asText()).isEqualTo("APPROVED");

        JsonNode mtg = ok(post("/api/v1/projects/" + pid + "/meetings"), admin, "{\"title\":\"주간회의\",\"actionItems\":["
                + "{\"content\":\"공문 발송\",\"ownerId\":\"" + plId + "\",\"dueDate\":\"" + LocalDate.now(Times.ZONE).plusDays(1)
                + "\"}]}");
        assertThat(mtg.get("actionItems").get(0).get("owner").get("userId").asText()).isEqualTo(plId);
        JsonNode open = json(admin, "/api/v1/projects/" + pid + "/action-items?open=true");
        assertThat(open.size()).isEqualTo(1);
        ok(patch("/api/v1/action-items/" + open.get(0).get("itemId").asText()), pl, "{\"done\":true}");
        assertThat(json(admin, "/api/v1/projects/" + pid + "/action-items?open=true").size()).isZero();

        // 주간보고
        ok(put("/api/v1/projects/" + pid + "/wbs"), admin, "[{\"wbsId\":\"w\",\"name\":\"이번주 작업\",\"planStart\":\""
                + LocalDate.now(Times.ZONE).with(DayOfWeek.MONDAY) + "\",\"planEnd\":\""
                + LocalDate.now(Times.ZONE).with(DayOfWeek.FRIDAY) + "\",\"weight\":1,\"progress\":30,\"milestone\":false,\"deps\":[]}]");
        LocalDate ws = LocalDate.now(Times.ZONE).with(DayOfWeek.MONDAY);
        JsonNode wr = ok(post("/api/v1/projects/" + pid + "/weekly-reports/generate"), admin,
                "{\"weekStart\":\"" + ws.plusDays(2) + "\"}");
        assertThat(wr.get("weekStart").asText()).isEqualTo(ws.toString());
        assertThat(wr.get("thisWeek").findValuesAsText("name")).contains("이번주 작업");
        assertThat(wr.get("issues").size()).isGreaterThanOrEqualTo(2);
        assertThat(wr.get("summary").asText()).contains("실적");
        send(post("/api/v1/projects/" + pid + "/weekly-reports/generate"), admin, "{\"weekStart\":\"" + ws + "\"}")
                .andExpect(status().isConflict());
        String rid = wr.get("reportId").asText();
        ok(put("/api/v1/weekly-reports/" + rid), pl, "{\"summary\":\"PL 편집\"}");
        ok(post("/api/v1/weekly-reports/" + rid + "/submit"), admin, null);
        send(put("/api/v1/weekly-reports/" + rid), admin, "{\"summary\":\"x\"}").andExpect(status().isConflict());
        mvc.perform(get("/api/v1/excel/export/weekly-report").param("reportId", rid).header("Authorization", admin))
                .andExpect(status().isOk());

        // 기한 알림 (이슈 기한 어제 → OVERDUE, 액션아이템은 완료라 제외), 같은 날 중복 없음
        int sent = due.run(LocalDate.now(Times.ZONE));
        assertThat(sent).isGreaterThanOrEqualTo(1);
        assertThat(json(pl, "/api/v1/notifications").get("content").findValuesAsText("event")).contains("OVERDUE");
        assertThat(due.run(LocalDate.now(Times.ZONE))).isZero();
        send(post("/api/v1/notifications/read-all"), pl, null).andExpect(status().isNoContent());
        assertThat(json(pl, "/api/v1/notifications?unread=true").get("totalElements").asInt()).isZero();

        // 대시보드·포트폴리오·검색
        JsonNode dash = json(admin, "/api/v1/projects/" + pid + "/dashboard");
        assertThat(dash.get("openIssues").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(dash.get("overdueIssues").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode pf = json(admin, "/api/v1/dashboard/portfolio");
        assertThat(byName(pf, "projectId", pid).get("health").asText()).isIn("YELLOW", "RED");
        assertThat(json(admin, "/api/v1/search?q=계정&projectId=" + pid).findValuesAsText("type")).contains("ISSUE");
        mvc.perform(get("/api/v1/dashboard/portfolio").header("Authorization", pl)).andExpect(status().isForbidden());
    }

    // ───────────────────────── 권한 매트릭스 ─────────────────────────

    @Test
    void 권한_고객_협력사_범위() throws Exception {
        String pid = newProject("SI");
        String custLogin = uniq("cu");
        String custId = newUser(custLogin, "고객", "CUSTOMER");
        addMember(pid, custId, "CUSTOMER");
        String partLogin = uniq("pa");
        String partId = newUser(partLogin, "협력사", "PARTNER");
        addMember(pid, partId, "PARTNER");
        String cust = TestSupport.login(mvc, om, custLogin);
        String part = TestSupport.login(mvc, om, partLogin);

        // 고객: WBS 조회 가능, 편집 불가
        json(cust, "/api/v1/projects/" + pid + "/wbs");
        send(put("/api/v1/projects/" + pid + "/wbs"), cust, "[]").andExpect(status().isForbidden());
        // 고객: 본인 이슈만 수정
        String adminIssue = ok(post("/api/v1/projects/" + pid + "/issues"), admin, "{\"title\":\"관리자 이슈\"}")
                .get("issueId").asText();
        String custIssue = ok(post("/api/v1/projects/" + pid + "/issues"), cust, "{\"title\":\"고객 이슈\"}")
                .get("issueId").asText();
        ok(put("/api/v1/issues/" + custIssue), cust, "{\"title\":\"고객 이슈 수정\"}");
        send(put("/api/v1/issues/" + adminIssue), cust, "{\"title\":\"x\"}").andExpect(status().isForbidden());
        // 고객: 대시보드는 고객 뷰(공수 숨김), 인력 화면 불가
        assertThat(json(cust, "/api/v1/projects/" + pid + "/dashboard").get("mm").isNull()).isTrue();
        mvc.perform(get("/api/v1/resources").header("Authorization", cust)).andExpect(status().isForbidden());
        // 고객 승인 산출물: 고객이 승인 가능, 일반 산출물은 불가
        String d1 = ok(post("/api/v1/projects/" + pid + "/deliverables"), admin,
                "{\"phase\":\"분석\",\"name\":\"요구사항정의서\",\"customerApproval\":true}").get("deliverableId").asText();
        String d2 = ok(post("/api/v1/projects/" + pid + "/deliverables"), admin,
                "{\"phase\":\"분석\",\"name\":\"내부 문서\"}").get("deliverableId").asText();
        ok(post("/api/v1/deliverables/" + d1 + "/request-review"), admin, null);
        ok(post("/api/v1/deliverables/" + d2 + "/request-review"), admin, null);
        ok(post("/api/v1/deliverables/" + d1 + "/approve"), cust, null);
        send(post("/api/v1/deliverables/" + d2 + "/approve"), cust, null).andExpect(status().isForbidden());

        // 협력사 열람 범위: 시스템 A 만 허용
        String sysA = ok(post("/api/v1/projects/" + pid + "/systems"), admin, "{\"code\":\"PA\",\"name\":\"A\","
                + "\"uiTech\":[],\"serverTech\":[],\"dbType\":\"oracle\"}").get("systemId").asText();
        String sysB = ok(post("/api/v1/projects/" + pid + "/systems"), admin, "{\"code\":\"PB\",\"name\":\"B\","
                + "\"uiTech\":[],\"serverTech\":[],\"dbType\":\"oracle\"}").get("systemId").asText();
        json(part, "/api/v1/systems/" + sysB + "/menu-tree");
        send(put("/api/v1/projects/" + pid + "/members/" + partId + "/scope"), part,
                "{\"systemIds\":[\"" + sysA + "\"],\"menuIds\":[]}").andExpect(status().isForbidden());
        ok(put("/api/v1/projects/" + pid + "/members/" + partId + "/scope"), admin,
                "{\"systemIds\":[\"" + sysA + "\"],\"menuIds\":[]}");
        json(part, "/api/v1/systems/" + sysA + "/menu-tree");
        mvc.perform(get("/api/v1/systems/" + sysB + "/menu-tree").header("Authorization", part))
                .andExpect(status().isForbidden());
        // 협력사: 작업일지 입력 가능(ETC), 승인 불가
        LocalDate ws = LocalDate.now(Times.ZONE).with(DayOfWeek.MONDAY);
        String tsId = ok(put("/api/v1/timesheets"), part, "{\"weekStart\":\"" + ws + "\",\"entries\":[{\"date\":\"" + ws
                + "\",\"targetType\":\"ETC\",\"targetLabel\":\"회의\",\"hours\":2,\"projectId\":\"" + pid + "\"}]}")
                .get("timesheetId").asText();
        ok(post("/api/v1/timesheets/" + tsId + "/submit"), part, null);
        send(post("/api/v1/timesheets/" + tsId + "/approve"), part, null).andExpect(status().isForbidden());
        // 다른 사람의 작업일지 조회는 PM·PL 만
        mvc.perform(get("/api/v1/timesheets").param("userId", partId).param("weekStart", ws.toString())
                .header("Authorization", cust)).andExpect(status().isForbidden());
        json(admin, "/api/v1/timesheets?userId=" + partId + "&weekStart=" + ws);
    }

    // ───────────────────────── 엑셀 ─────────────────────────

    @Test
    void 엑셀_요구사항_가져오기와_WBS_내보내기() throws Exception {
        String pid = newProject("SI");
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet s = wb.createSheet("요구사항");
            String[] head = {"요구사항ID", "요구사항명", "상세내용", "출처", "분류", "우선순위(H/M/L)", "상태"};
            Row h = s.createRow(0);
            for (int i = 0; i < head.length; i++) {
                h.createCell(i).setCellValue(head[i]);
            }
            String[][] rows = {{"REQ-0100", "엑셀 요구1", "", "RFP", "인사", "H", "ACCEPTED"},
                    {"", "엑셀 요구2", "", "", "", "M", ""}, {"", "잘못된 우선순위", "", "", "", "X", ""}};
            for (int r = 0; r < rows.length; r++) {
                Row row = s.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        JsonNode pre = body(mvc.perform(multipart("/api/v1/excel/import/requirements/validate")
                .file(new MockMultipartFile("file", "req.xlsx",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx))
                .param("projectId", pid).header("Authorization", admin)).andExpect(status().isOk()));
        assertThat(pre.get("validCount").asInt()).isEqualTo(2);
        assertThat(pre.get("errorCount").asInt()).isEqualTo(1);
        JsonNode res = ok(post("/api/v1/excel/import/requirements/commit"), admin,
                "{\"token\":\"" + pre.get("token").asText() + "\"}");
        assertThat(res.get("inserted").asInt()).isEqualTo(2);
        JsonNode reqs = json(admin, "/api/v1/projects/" + pid + "/requirements?size=50").get("content");
        assertThat(reqs.findValuesAsText("reqNo")).contains("REQ-0100", "REQ-0101");

        ok(put("/api/v1/projects/" + pid + "/wbs"), admin, "[{\"wbsId\":\"a\",\"name\":\"작업\",\"weight\":1,"
                + "\"progress\":0,\"milestone\":false,\"deps\":[]}]");
        byte[] out = mvc.perform(get("/api/v1/excel/export/wbs").param("projectId", pid).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new java.io.ByteArrayInputStream(out))) {
            assertThat(wb.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("작업");
        }
        assertThat(json(admin, "/api/v1/excel/datasets").findValuesAsText("datasetId")).contains("wbs", "mm-plan",
                "mm-settlement", "requirements", "trace-matrix", "deliverables", "issues", "risks", "defects",
                "test-cases", "weekly-report");
    }

    @Test
    void 감사로그_변경기록과_첨부권한() throws Exception {
        String pid = newProject("SI");
        String did = ok(post("/api/v1/projects/" + pid + "/deliverables"), admin, "{\"phase\":\"설계\",\"name\":\"ERD\"}")
                .get("deliverableId").asText();
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_AUDIT_LOG WHERE TARGET = ? AND ACTION = 'CREATE'",
                Integer.class, "deliverable:" + did);
        assertThat(n).isEqualTo(1);
        mvc.perform(multipart("/api/v1/files").file(new MockMultipartFile("file", "erd.png", "image/png",
                        TestSupport.onePixelPng())).param("targetType", "DL_DELIVERABLE").param("targetId", did)
                .header("Authorization", admin)).andExpect(status().isCreated());
        assertThat(json(admin, "/api/v1/projects/" + pid + "/deliverables").get(0).get("fileCount").asInt()).isEqualTo(1);
        // 비멤버 업로드 불가
        String outsider = newUser(uniq("out"), "외부", "INTERNAL");
        String outsiderLogin = jdbc.queryForObject("SELECT LOGIN_ID FROM CM_USER WHERE USER_ID = ?", String.class,
                outsider);
        String out = TestSupport.login(mvc, om, outsiderLogin);
        mvc.perform(multipart("/api/v1/files").file(new MockMultipartFile("file", "x.png", "image/png",
                        TestSupport.onePixelPng())).param("targetType", "DL_DELIVERABLE").param("targetId", did)
                .header("Authorization", out)).andExpect(status().isForbidden());
        send(delete("/api/v1/deliverables/" + did), admin, null).andExpect(status().isNoContent());
    }
}
