package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.pmsx.common.Times;
import kr.pmsx.sm.SmScheduler;
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

/** docs/13 R3: SR·SLA·포털·메일 접수·KB·장애·문제·변경·정기 작업·운영 자산·월간 보고·SI→SM 인수인계·영향도 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SmFlowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    SmScheduler scheduler;

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
        if (auth != null) {
            b.header("Authorization", auth);
        }
        if (json != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(json instanceof String s ? s : om.writeValueAsString(json));
        }
        return mvc.perform(b);
    }

    JsonNode ok(MockHttpServletRequestBuilder b, String auth, Object json) throws Exception {
        return body(send(b, auth, json).andExpect(status().is2xxSuccessful()));
    }

    JsonNode fail(MockHttpServletRequestBuilder b, String auth, Object json, int code) throws Exception {
        return body(send(b, auth, json).andExpect(status().is(code)));
    }

    JsonNode json(String auth, String url) throws Exception {
        return TestSupport.json(mvc, om, auth, url);
    }

    static String uniq(String p) {
        return p + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
    }

    String adminId() throws Exception {
        return json(admin, "/api/v1/auth/me").get("userId").asText();
    }

    String newUser(String login, String orgType, String company) throws Exception {
        String id = ok(post("/api/v1/admin/users"), admin, Map.of("loginId", login, "name", login, "orgType", orgType,
                "authType", "LOCAL", "roles", List.of(), "password", "pmsx1234!")).get("userId").asText();
        if (company != null) {
            jdbc.update("UPDATE CM_USER SET COMPANY = ? WHERE USER_ID = ?", company, id);
        }
        return id;
    }

    void addMember(String projectId, String userId, String role) throws Exception {
        ok(post("/api/v1/projects/" + projectId + "/members"), admin, Map.of("userId", userId, "roleId", role));
    }

    String newProject(String type) throws Exception {
        return ok(post("/api/v1/projects"), admin, Map.of("type", type, "name", uniq("R3 " + type + " "),
                "customer", "○○공사", "startDate", "2026-01-01", "endDate", "2027-12-31", "pmUserId", adminId()))
                .get("projectId").asText();
    }

    JsonNode newSr(String projectId, String auth, String type, String priority, String title) throws Exception {
        return ok(post("/api/v1/projects/" + projectId + "/srs"), auth, Map.of("type", type, "priority", priority,
                "title", title, "description", title + " 상세", "requester", Map.of("name", "박인사", "org", "○○공사 인사팀")));
    }

    JsonNode transition(String auth, String srId, String to, String comment, String assigneeId) throws Exception {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("to", to);
        m.put("comment", comment);
        m.put("assigneeId", assigneeId);
        return ok(post("/api/v1/srs/" + srId + "/transition"), auth, m);
    }

    // ───────────────────────── SR·SLA ─────────────────────────

    @Test
    void SR_등록_전이_SLA_보류_해결_위반알림() throws Exception {
        String pid = newProject("SM");
        assertThat(json(admin, "/api/v1/projects/" + pid + "/sla-policies")).hasSize(4);
        String op = newUser(uniq("op"), "INTERNAL", null);
        String op2 = newUser(uniq("op"), "INTERNAL", null);
        addMember(pid, op, "OPERATOR");
        addMember(pid, op2, "OPERATOR");
        String opAuth = TestSupport.login(mvc, om, login(op));
        String op2Login = jdbc.queryForObject("SELECT LOGIN_ID FROM CM_USER WHERE USER_ID = ?", String.class, op2);
        String op2Auth = TestSupport.login(mvc, om, op2Login);

        JsonNode sr = newSr(pid, admin, "INCIDENT", "P1", "인사시스템 로그인 불가");
        String id = sr.get("srId").asText();
        assertThat(sr.get("srNo").asText()).isEqualTo("SR-0001");
        assertThat(sr.get("status").asText()).isEqualTo("RECEIVED");
        assertThat(sr.get("channel").asText()).isEqualTo("MANUAL");
        assertThat(sr.at("/sla/policyName").asText()).contains("24x7");
        assertThat(sr.at("/sla/state").asText()).isEqualTo("DUE_SOON");      // 응답 30분 ≤ max(6, 30)
        assertThat(sr.at("/sla/remainingMinutes").asLong()).isBetween(29L, 30L);

        assertThat(fail(post("/api/v1/srs/" + id + "/transition"), admin, Map.of("to", "RESOLVED"), 409)
                .get("code").asText()).isEqualTo("CONFLICT");
        fail(post("/api/v1/srs/" + id + "/transition"), admin, Map.of("to", "ASSIGNED"), 400);
        transition(admin, id, "ASSIGNED", null, op);
        assertThat(json(opAuth, "/api/v1/notifications?unread=true").get("content").findValuesAsText("title"))
                .anyMatch(t -> t.contains("SR-0001"));
        fail(post("/api/v1/srs/" + id + "/transition"), op2Auth, Map.of("to", "IN_PROGRESS"), 403);
        JsonNode prog = transition(opAuth, id, "IN_PROGRESS", null, null);
        assertThat(prog.at("/sla/respondedAt").isNull()).isFalse();
        JsonNode hold = transition(opAuth, id, "ON_HOLD", "고객 회신 대기", null);
        assertThat(hold.at("/sla/state").asText()).isEqualTo("PAUSED");
        // 보류 시작을 1시간 전으로 → 일시정지 60분, 해결 기한 = 접수 + 240 + 60분 (24x7)
        jdbc.update("UPDATE SM_SLA_EVENT SET EVENT_AT = ? WHERE SR_ID = ? AND EVENT = 'PAUSE'",
                Times.now().minusMinutes(60), id);
        JsonNode held = json(admin, "/api/v1/srs/" + id);
        assertThat(held.at("/sla/pausedMinutes").asLong()).isBetween(59L, 61L);
        OffsetDateTime created = OffsetDateTime.parse(held.get("createdAt").asText());
        OffsetDateTime due = OffsetDateTime.parse(held.at("/sla/resolveDue").asText());
        assertThat(java.time.Duration.between(created, due).toMinutes()).isBetween(299L, 301L);
        transition(opAuth, id, "IN_PROGRESS", null, null);
        JsonNode done = transition(opAuth, id, "RESOLVED", "세션 저장소 정리", null);
        assertThat(done.at("/sla/state").asText()).isEqualTo("MET");
        assertThat(done.at("/sla/resolvedAt").isNull()).isFalse();
        assertThat(done.get("history").findValuesAsText("to")).containsSubsequence("ASSIGNED", "IN_PROGRESS",
                "ON_HOLD", "IN_PROGRESS", "RESOLVED");

        // 반려 사유 필수, 우선순위 변경 → 정책 재매칭
        String id2 = newSr(pid, admin, "INQUIRY", "P1", "문의").get("srId").asText();
        fail(post("/api/v1/srs/" + id2 + "/transition"), admin, Map.of("to", "REJECTED"), 400);
        JsonNode upd = ok(put("/api/v1/srs/" + id2), admin, Map.of("type", "INQUIRY", "priority", "P3", "title", "문의",
                "requester", Map.of("name", "박인사")));
        assertThat(upd.at("/sla/policyName").asText()).startsWith("P3");

        // 위반: 접수를 2시간 전으로 → 응답 위반, 목록 필터, 스케줄러 알림 1회
        String id3 = newSr(pid, admin, "INCIDENT", "P1", "배치 실패").get("srId").asText();
        OffsetDateTime past = Times.now().minusHours(2);
        jdbc.update("UPDATE SM_SR SET CREATED_AT = ? WHERE SR_ID = ?", past, id3);
        jdbc.update("UPDATE SM_SLA_EVENT SET EVENT_AT = ? WHERE SR_ID = ? AND EVENT = 'START'", past, id3);
        JsonNode br = json(admin, "/api/v1/srs/" + id3);
        assertThat(br.at("/sla/state").asText()).isEqualTo("BREACHED");
        assertThat(br.at("/sla/responseBreached").asBoolean()).isTrue();
        assertThat(json(admin, "/api/v1/projects/" + pid + "/srs?slaState=BREACHED").get("content")
                .findValuesAsText("srId")).containsExactly(id3);
        int before = notifications(id3);
        assertThat(scheduler.slaAlerts(Times.now())).isGreaterThanOrEqualTo(1);
        int after = notifications(id3);
        assertThat(after).isGreaterThan(before);
        scheduler.slaAlerts(Times.now());
        assertThat(notifications(id3)).isEqualTo(after);
        assertThat(jdbc.queryForObject("SELECT NOTIFY_FLAGS FROM SM_SR WHERE SR_ID = ?", String.class, id3))
                .contains("RESP_BREACH");

        JsonNode st = json(admin, "/api/v1/projects/" + pid + "/sla/status");
        assertThat(st.get("total").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(st.get("breached").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(st.get("breachedList").findValuesAsText("srId")).contains(id3);
        assertThat(st.at("/byType/INCIDENT/total").asInt()).isEqualTo(2);
        assertThat(json(admin, "/api/v1/projects/" + pid + "/dashboard").at("/sm/openSrs").asInt())
                .isGreaterThanOrEqualTo(2);
    }

    int notifications(String srId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_NOTIFICATION WHERE LINK = ?", Integer.class,
                "/sm/srs/" + srId);
        return n == null ? 0 : n;
    }

    // ───────────────────────── 고객 포털 ─────────────────────────

    @Test
    void 고객_포털_범위와_내부코멘트_확인_재요청() throws Exception {
        String pid = newProject("SM");
        String c1 = newUser(uniq("cust"), "CUSTOMER", "고객사A");
        String c2 = newUser(uniq("cust"), "CUSTOMER", "고객사B");
        String op = newUser(uniq("op"), "INTERNAL", null);
        addMember(pid, c1, "CUSTOMER");
        addMember(pid, c2, "CUSTOMER");
        addMember(pid, op, "OPERATOR");
        String c1Auth = TestSupport.login(mvc, om, login(c1));
        String c2Auth = TestSupport.login(mvc, om, login(c2));
        String opAuth = TestSupport.login(mvc, om, login(op));

        assertThat(json(c1Auth, "/api/v1/portal/projects").findValuesAsText("projectId")).contains(pid);
        JsonNode sr = ok(post("/api/v1/portal/projects/" + pid + "/srs"), c1Auth, Map.of("type", "INQUIRY",
                "title", "사원 조회가 안 됩니다", "description", "오류 화면 첨부"));
        String id = sr.get("srId").asText();
        assertThat(sr.get("channel").asText()).isEqualTo("PORTAL");
        assertThat(sr.at("/requester/userId").asText()).isEqualTo(c1);
        assertThat(sr.at("/requester/org").asText()).isEqualTo("고객사A");

        ok(post("/api/v1/srs/" + id + "/comments"), admin, Map.of("content", "내부메모: DB 확인 필요", "internal", true));
        assertThat(json(admin, "/api/v1/srs/" + id).at("/sla/respondedAt").isNull()).isTrue();   // 내부 코멘트는 응답 아님
        ok(post("/api/v1/srs/" + id + "/comments"), admin, Map.of("content", "확인 중입니다", "internal", false));
        JsonNode mine = json(c1Auth, "/api/v1/portal/srs/" + id);
        assertThat(mine.get("comments").findValuesAsText("content")).contains("확인 중입니다")
                .doesNotContain("내부메모: DB 확인 필요");
        assertThat(mine.get("similar").isNull()).isTrue();
        assertThat(mine.at("/sla/respondedAt").isNull()).isFalse();
        assertThat(json(c1Auth, "/api/v1/srs/" + id).get("comments").findValuesAsText("internal")).containsOnly("false");

        fail(get("/api/v1/portal/srs/" + id), c2Auth, null, 404);
        assertThat(json(c2Auth, "/api/v1/portal/projects/" + pid + "/srs").get("totalElements").asInt()).isZero();
        fail(post("/api/v1/srs/" + id + "/transition"), c1Auth, Map.of("to", "CLASSIFIED"), 403);
        assertThat(json(c1Auth, "/api/v1/projects/" + pid + "/srs").get("totalElements").asInt()).isEqualTo(1);

        transition(admin, id, "ASSIGNED", null, op);
        transition(opAuth, id, "IN_PROGRESS", null, null);
        transition(opAuth, id, "RESOLVED", null, null);
        fail(post("/api/v1/portal/srs/" + id + "/reopen"), c1Auth, Map.of(), 400);
        JsonNode re = ok(post("/api/v1/portal/srs/" + id + "/reopen"), c1Auth, Map.of("comment", "아직 안 됩니다"));
        assertThat(re.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(re.get("comments").findValuesAsText("content")).anyMatch(c -> c.startsWith("[재요청]"));
        transition(opAuth, id, "RESOLVED", null, null);
        assertThat(ok(post("/api/v1/portal/srs/" + id + "/confirm"), c1Auth, Map.of()).get("status").asText())
                .isEqualTo("CONFIRMED");
        // 비고객·비멤버
        fail(post("/api/v1/portal/projects/" + pid + "/srs"), opAuth, Map.of("title", "x"), 403);
        String stranger = newUser(uniq("st"), "CUSTOMER", "고객사A");
        assertThat(json(TestSupport.login(mvc, om, login(stranger)), "/api/v1/portal/projects")).isEmpty();
        // 첨부: 고객 본인 SR 업로드 가능, 다른 고객 불가
        mvc.perform(multipart("/api/v1/files").file(new MockMultipartFile("file", "a.txt", "text/plain",
                        "x".getBytes())).param("targetType", "SM_SR").param("targetId", id).header("Authorization", c1Auth))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/files").file(new MockMultipartFile("file", "b.txt", "text/plain",
                        "x".getBytes())).param("targetType", "SM_SR").param("targetId", id).header("Authorization", c2Auth))
                .andExpect(status().isNotFound());
    }

    String login(String userId) {
        return jdbc.queryForObject("SELECT LOGIN_ID FROM CM_USER WHERE USER_ID = ?", String.class, userId);
    }

    // ───────────────────────── 메일·KB·공수 ─────────────────────────

    @Test
    void 메일접수_유사추천_KB전환_SR공수() throws Exception {
        String pid = newProject("SM");
        Map<String, Object> mail = Map.of("projectCode", pid, "from", "\"김민원\" <minwon@example.com>",
                "subject", "비밀번호 초기화 요청", "body", "계정이 잠겼습니다. 비밀번호 초기화 부탁드립니다.",
                "messageId", "<" + uniq("m") + "@mail>");
        fail(post("/api/v1/sm/inbound-mail"), null, mail, 401);
        JsonNode sr = body(mvc.perform(post("/api/v1/sm/inbound-mail").header("X-PMSX-KEY", "test-mail-key")
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mail)))
                .andExpect(status().isCreated()));
        String id = sr.get("srId").asText();
        assertThat(sr.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(sr.at("/requester/name").asText()).isEqualTo("김민원");
        assertThat(sr.at("/requester/contact").asText()).isEqualTo("minwon@example.com");
        JsonNode again = body(mvc.perform(post("/api/v1/sm/inbound-mail").header("X-PMSX-KEY", "test-mail-key")
                .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(mail)))
                .andExpect(status().isOk()));
        assertThat(again.get("srId").asText()).isEqualTo(id);

        JsonNode kb = ok(post("/api/v1/projects/" + pid + "/kb"), admin, Map.of("title", "비밀번호 초기화 절차",
                "content", "## 절차\n계정 잠금 해제 후 임시 비밀번호 발급", "tags", List.of("계정", "비밀번호")));
        assertThat(kb.get("kbNo").asText()).isEqualTo("KB-0001");
        assertThat(kb.get("tags").findValuesAsText("")).isEmpty();
        assertThat(kb.get("tags")).hasSize(2);
        JsonNode detail = json(admin, "/api/v1/srs/" + id);
        assertThat(detail.get("similar").findValuesAsText("id")).contains(kb.get("kbId").asText());
        assertThat(json(admin, "/api/v1/projects/" + pid + "/kb/similar?text=비밀번호 잠김").findValuesAsText("kind"))
                .contains("KB");
        JsonNode toKb = ok(post("/api/v1/srs/" + id + "/to-kb"), admin, null);
        assertThat(toKb.get("sourceSrId").asText()).isEqualTo(id);
        assertThat(toKb.get("content").asText()).contains("증상");
        assertThat(json(admin, "/api/v1/projects/" + pid + "/kb?q=초기화").get("totalElements").asInt()).isEqualTo(2);

        // SR 배정·작업일지(R2 잔여)
        String me = adminId();
        assertThat(ok(post("/api/v1/assignments"), admin, Map.of("targetType", "SR", "targetId", id, "userId", me,
                "allocPct", 20)).get("targetType").asText()).isEqualTo("SR");
        LocalDate ws = LocalDate.of(2031, 3, 3).with(DayOfWeek.MONDAY).plusWeeks(UUID.randomUUID().hashCode() & 255);
        JsonNode ts = ok(put("/api/v1/timesheets"), admin, Map.of("weekStart", ws.toString(), "entries", List.of(
                Map.of("date", ws.toString(), "targetType", "SR", "targetId", id, "targetLabel", "-", "hours", 3))));
        ok(post("/api/v1/timesheets/" + ts.get("timesheetId").asText() + "/submit"), admin, Map.of());
        assertThat(json(admin, "/api/v1/srs/" + id).get("effortHours").asDouble()).isEqualTo(3.0);
        // 통합 검색
        assertThat(json(admin, "/api/v1/search?q=" + "비밀번호&projectId=" + pid).findValuesAsText("type"))
                .contains("SR", "KB");
    }

    // ───────────────────────── 장애·문제·변경·정기 작업·자산·보고 ─────────────────────────

    @Test
    void 장애_문제후보_변경CAB_정기작업_자산알림_월간보고() throws Exception {
        String pid = newProject("SM");
        JsonNode inc = ok(post("/api/v1/projects/" + pid + "/incidents"), admin, Map.of("title", "로그인 장애",
                "grade", 2, "startedAt", Times.now().minusMinutes(90).toString(), "impact", "전 직원",
                "timeline", List.of(Map.of("at", Times.now().minusMinutes(80).toString(), "content", "경보 수신"))));
        String incId = inc.get("incidentId").asText();
        assertThat(inc.get("incidentNo").asText()).isEqualTo("INC-0001");
        assertThat(inc.get("timeline")).hasSize(1);
        ok(post("/api/v1/incidents/" + incId + "/timeline"), admin, Map.of("content", "세션 정리"));
        JsonNode rec = ok(put("/api/v1/incidents/" + incId), admin, Map.of("title", "로그인 장애", "status", "RECOVERED",
                "rca", "세션 저장소 용량 초과"));
        assertThat(rec.get("durationMinutes").asLong()).isBetween(89L, 91L);
        assertThat(rec.get("timeline")).hasSize(2);
        byte[] xlsx = mvc.perform(get("/api/v1/excel/export/incident-report?incidentId=" + incId)
                .header("Authorization", admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(xlsx.length).isGreaterThan(100);

        List<String> similar = new ArrayList<>();
        for (String t : List.of("사원 저장 오류 발생", "사원 저장 오류 재발", "사원 저장 시 오류")) {
            similar.add(newSr(pid, admin, "IMPROVE", "P3", t).get("srId").asText());
        }
        newSr(pid, admin, "DATA", "P4", "부서 코드 일괄 변경");
        JsonNode cands = json(admin, "/api/v1/projects/" + pid + "/problems/candidates");
        assertThat(cands.findValuesAsText("reason")).contains("SIMILAR_TITLE");
        assertThat(cands.get(0).get("count").asInt()).isEqualTo(3);
        assertThat(cands.get(0).get("srs").findValuesAsText("srId")).containsExactlyInAnyOrderElementsOf(similar);
        ok(post("/api/v1/projects/" + pid + "/problems"), admin, Map.of("title", "사원 저장 반복 오류",
                "srIds", similar, "knownError", true));
        assertThat(json(admin, "/api/v1/projects/" + pid + "/problems/candidates")).isEmpty();

        JsonNode chg = ok(post("/api/v1/projects/" + pid + "/changes"), admin, Map.of("title", "입사일 검증 배포",
                "type", "NORMAL", "risk", "M", "plan", "배포", "rollbackPlan", "원복",
                "scheduledAt", Times.now().plusDays(1).toString(), "commits", List.of("abc123 입사일 검증")));
        String cid = chg.get("changeId").asText();
        assertThat(chg.get("status").asText()).isEqualTo("REQUESTED");
        fail(post("/api/v1/changes/" + cid + "/transition"), admin, Map.of("to", "DEPLOYED"), 409);
        fail(post("/api/v1/changes/" + cid + "/cab"), admin, Map.of("decision", "REJECT"), 400);
        assertThat(ok(post("/api/v1/changes/" + cid + "/cab"), admin, Map.of("decision", "APPROVE"))
                .get("status").asText()).isEqualTo("CAB_APPROVED");
        JsonNode dep = ok(post("/api/v1/changes/" + cid + "/transition"), admin, Map.of("to", "DEPLOYED"));
        assertThat(dep.get("deployedAt").isNull()).isFalse();
        assertThat(json(admin, "/api/v1/projects/" + pid + "/changes/calendar?from=" + LocalDate.now(Times.ZONE)
                .minusDays(1) + "&to=" + LocalDate.now(Times.ZONE).plusDays(2)).findValuesAsText("kind"))
                .contains("DEPLOYED");
        // CAB 승인 2건 규칙: PL 승인만으로는 대기, 고객 승인이 더해지면 승인
        String pl = newUser(uniq("pl"), "INTERNAL", null);
        String cu = newUser(uniq("cu"), "CUSTOMER", "○○공사");
        addMember(pid, pl, "PL");
        addMember(pid, cu, "CUSTOMER");
        String c2 = ok(post("/api/v1/projects/" + pid + "/changes"), admin, Map.of("title", "정렬 변경", "type", "NORMAL"))
                .get("changeId").asText();
        assertThat(ok(post("/api/v1/changes/" + c2 + "/cab"), TestSupport.login(mvc, om, login(pl)),
                Map.of("decision", "APPROVE")).get("status").asText()).isEqualTo("REQUESTED");
        JsonNode two = ok(post("/api/v1/changes/" + c2 + "/cab"), TestSupport.login(mvc, om, login(cu)),
                Map.of("decision", "APPROVE"));
        assertThat(two.get("status").asText()).isEqualTo("CAB_APPROVED");
        assertThat(two.get("cab")).hasSize(2);
        JsonNode em = ok(post("/api/v1/projects/" + pid + "/changes"), admin, Map.of("title", "긴급 패치",
                "type", "EMERGENCY", "srIds", List.of(similar.get(0))));
        assertThat(em.get("srs").get(0).get("srNo").asText()).startsWith("SR-");
        ok(post("/api/v1/changes/" + em.get("changeId").asText() + "/transition"), admin, Map.of("to", "DEPLOYED"));

        String me = adminId();
        JsonNode job = ok(post("/api/v1/projects/" + pid + "/routine-jobs"), admin, Map.of("name", "백업 확인",
                "rule", Map.of("freq", "WEEKLY", "dow", List.of(1, 2, 3, 4, 5, 6, 7)), "ownerId", me,
                "checklist", List.of("DB 백업")));
        LocalDate today = LocalDate.now(Times.ZONE);
        ok(post("/api/v1/routine-jobs/" + job.get("jobId").asText() + "/check"), admin, Map.of("date",
                today.toString(), "done", true, "note", "정상"));
        JsonNode occ = json(admin, "/api/v1/projects/" + pid + "/routine-jobs/occurrences?from=" + today + "&to=" + today);
        assertThat(occ).hasSize(1);
        assertThat(occ.get(0).get("done").asBoolean()).isTrue();
        JsonNode monthly = ok(post("/api/v1/projects/" + pid + "/routine-jobs"), admin, Map.of("name", "월마감",
                "rule", Map.of("freq", "MONTHLY", "day", 15)));
        fail(post("/api/v1/routine-jobs/" + monthly.get("jobId").asText() + "/check"), admin,
                Map.of("date", today.withDayOfMonth(16).toString()), 400);

        JsonNode asset = ok(post("/api/v1/projects/" + pid + "/ops-assets"), admin, Map.of("type", "CERT",
                "name", "SSL 인증서", "owner", "서운영", "ownerId", me, "expiresAt", today.plusDays(5).toString()));
        assertThat(asset.get("daysLeft").asLong()).isEqualTo(5);
        int n1 = assetNotices(asset.get("assetId").asText());
        scheduler.assetAlerts(today);
        int n2 = assetNotices(asset.get("assetId").asText());
        assertThat(n2).isEqualTo(n1 + 1);
        scheduler.assetAlerts(today);
        assertThat(assetNotices(asset.get("assetId").asText())).isEqualTo(n2);
        scheduler.assetAlerts(today.plusDays(6));                       // 만료일 경과 단계
        assertThat(assetNotices(asset.get("assetId").asText())).isEqualTo(n2 + 1);

        String ym = YearMonth.now(Times.ZONE).toString();
        JsonNode rep = ok(post("/api/v1/projects/" + pid + "/monthly-reports/generate"), admin, Map.of("month", ym));
        assertThat(rep.at("/sr/total").asInt()).isEqualTo(4);
        assertThat(rep.get("incidents")).hasSize(1);
        assertThat(rep.get("changes")).hasSize(2);
        assertThat(rep.get("summary").asText()).contains("SR 4건");
        fail(post("/api/v1/projects/" + pid + "/monthly-reports/generate"), admin, Map.of("month", ym), 409);
        assertThat(ok(put("/api/v1/monthly-reports/" + rep.get("reportId").asText()), admin,
                Map.of("summary", "총평 수정", "plan", "다음 달 계획")).get("plan").asText()).isEqualTo("다음 달 계획");
        for (String ds : List.of("srs?projectId=" + pid, "changes?projectId=" + pid, "ops-assets?projectId=" + pid,
                "kb?projectId=" + pid, "monthly-ops-report?reportId=" + rep.get("reportId").asText())) {
            mvc.perform(get("/api/v1/excel/export/" + ds).header("Authorization", admin)).andExpect(status().isOk());
        }
    }

    int assetNotices(String assetId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_NOTIFICATION WHERE LINK = ?", Integer.class,
                "/sm/ops-assets?focus=" + assetId);
        return n == null ? 0 : n;
    }

    // ───────────────────────── 인수인계·영향도 ─────────────────────────

    @Test
    void SI_SM_인수인계_복사정합성과_영향도() throws Exception {
        String si = newProject("SI");
        String sys = ok(post("/api/v1/projects/" + si + "/systems"), admin, Map.of("code", "HR", "name", "인사시스템",
                "uiTech", List.of("jsp-jquery"), "serverTech", List.of("spring"), "dbType", "oracle")).get("systemId")
                .asText();
        mvc.perform(multipart("/api/v1/systems/" + sys + "/runs").file(new MockMultipartFile("file", "p.zip",
                "application/zip", TestSupport.fixtureZip(om, null))).header("Authorization", admin))
                .andExpect(status().isCreated());
        String screenId = screen(sys, "HR0101").get("screenId").asText();
        String saveEl = element(screenId, "btnSave");
        ok(post("/api/v1/notes"), admin, Map.of("targetType", "SCREEN", "targetId", screenId, "content", "업무 규칙 메모"));
        ok(post("/api/v1/trace-links"), admin, Map.of("fromType", "ELEMENT", "fromId", saveEl, "toType", "SCREEN",
                "toId", screenId, "linkType", "구성"));
        ok(post("/api/v1/projects/" + si + "/defects"), admin, Map.of("title", "저장 시 중복 사번", "severity", "MAJOR",
                "elementId", saveEl));
        String analyst = jdbc.queryForObject("SELECT USER_ID FROM CM_USER WHERE LOGIN_ID = 'analyst'", String.class);
        addMember(si, analyst, "ANALYST");

        Map<String, Object> req = Map.of("target", Map.of("newProject", Map.of("name", uniq("SM 운영 "), "customer",
                "○○공사", "startDate", "2027-01-01", "endDate", "2027-12-31", "pmUserId", adminId())),
                "systemIds", List.of(sys), "memberUserIds", List.of(analyst),
                "include", Map.of("notes", true, "traceLinks", true, "deliverables", false, "openDefectsAsSr", true));
        JsonNode pv = ok(post("/api/v1/projects/" + si + "/handover/preview"), admin, req);
        int edges = pv.at("/systems/0/edges").asInt();
        assertThat(pv.at("/systems/0/screens").asInt()).isEqualTo(2);
        assertThat(pv.at("/systems/0/elements").asInt()).isEqualTo(5);
        assertThat(edges).isGreaterThan(20);
        assertThat(pv.get("traceLinks").asInt()).isEqualTo(1);
        assertThat(pv.get("openDefects").asInt()).isEqualTo(1);

        JsonNode res = ok(post("/api/v1/projects/" + si + "/handover"), admin, req);
        String sm = res.get("smProjectId").asText();
        assertThat(res.at("/copied/screens").asInt()).isEqualTo(2);
        assertThat(res.at("/copied/elements").asInt()).isEqualTo(5);
        assertThat(res.at("/copied/edges").asInt()).isEqualTo(edges);
        assertThat(res.at("/copied/notes").asInt()).isEqualTo(1);
        assertThat(res.at("/copied/traceLinks").asInt()).isEqualTo(1);
        assertThat(res.at("/copied/srs").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ORIGIN_PROJECT_ID FROM PJ_PROJECT WHERE PROJECT_ID = ?", String.class,
                sm)).isEqualTo(si);
        assertThat(jdbc.queryForObject("SELECT ROLE_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                String.class, sm, analyst)).isEqualTo("OPERATOR");
        assertThat(json(admin, "/api/v1/projects/" + sm + "/sla-policies")).hasSize(4);
        JsonNode smSystems = json(admin, "/api/v1/projects/" + sm + "/systems");
        String smSys = smSystems.get(0).get("systemId").asText();
        assertThat(smSystems.get(0).get("screenCount").asInt()).isEqualTo(2);

        // 워크플로우·메뉴 트리 동일
        String smScreen = screen(smSys, "HR0101").get("screenId").asText();
        String smSave = element(smScreen, "btnSave");
        assertThat(smSave).isNotEqualTo(saveEl);
        assertThat(steps(smSave)).isEqualTo(steps(saveEl));
        assertThat(json(admin, "/api/v1/systems/" + smSys + "/menu-tree").toString().length())
                .isEqualTo(json(admin, "/api/v1/systems/" + sys + "/menu-tree").toString()
                        .replace(sys, smSys).length());
        JsonNode smScreenDetail = json(admin, "/api/v1/screens/" + smScreen);
        assertThat(smScreenDetail.get("capture").isNull()).isFalse();
        mvc.perform(get("/api/v1/captures/" + smScreenDetail.at("/capture/captureId").asText() + "/image")
                .header("Authorization", admin)).andExpect(status().isOk());
        assertThat(json(admin, "/api/v1/notes?targetType=SCREEN&targetId=" + smScreen).findValuesAsText("content"))
                .contains("업무 규칙 메모");
        assertThat(json(admin, "/api/v1/elements/" + smSave + "/links").get("links")).hasSize(1);

        // 결함 → SR
        JsonNode srs = json(admin, "/api/v1/projects/" + sm + "/srs");
        JsonNode migrated = srs.get("content").get(0);
        assertThat(migrated.get("title").asText()).startsWith("[이관 결함]");
        assertThat(migrated.get("elementId").asText()).isEqualTo(smSave);
        assertThat(migrated.get("screenId").asText()).isEqualTo(smScreen);
        assertThat(migrated.get("priority").asText()).isEqualTo("P2");

        // 같은 코드 재인수인계 → 409
        Map<String, Object> again = Map.of("target", Map.of("projectId", sm), "systemIds", List.of(sys));
        fail(post("/api/v1/projects/" + si + "/handover"), admin, again, 409);

        // 영향도: TB_EMP
        String tbEmp = null;
        for (JsonNode t : json(admin, "/api/v1/systems/" + smSys + "/tables").get("content")) {
            if ("TB_EMP".equals(t.get("name").asText())) {
                tbEmp = t.get("tableId").asText();
            }
        }
        JsonNode imp = json(admin, "/api/v1/systems/" + smSys + "/impact?targetId=" + tbEmp);
        JsonNode hr0101 = imp.get("screens").get(0);
        assertThat(hr0101.get("screenId").asText()).isEqualTo(smScreen);
        assertThat(hr0101.get("elements").findValuesAsText("label")).contains("저장", "조회");
        for (JsonNode el : hr0101.get("elements")) {
            if (el.get("elementId").asText().equals(smSave)) {
                assertThat(el.get("crud").asText()).contains("C").contains("U");
                assertThat(el.get("path").get(0).asText()).isEqualTo("저장");
                assertThat(el.get("path").get(el.get("path").size() - 1).asText()).contains("TB_EMP");
            }
        }
        assertThat(imp.get("apis")).hasSize(2);
        assertThat(imp.get("serverFunctions").findValuesAsText("name")).anyMatch(n -> n.contains("saveEmp"));
        assertThat(imp.get("batches")).isEmpty();
        assertThat(imp.get("srs").findValuesAsText("srId")).contains(migrated.get("srId").asText());
        assertThat(imp.at("/summary/screens").asInt()).isEqualTo(1);
        // 컬럼 단위: EMP_NO 는 insertEmp 만 사용 → 저장 버튼(C)만
        JsonNode col = json(admin, "/api/v1/systems/" + smSys + "/impact?targetId=" + tbEmp + "&column=EMP_NO");
        assertThat(col.get("screens").get(0).get("elements").findValuesAsText("label")).containsExactly("저장");
        assertThat(col.get("screens").get(0).get("elements").get(0).get("crud").asText()).isEqualTo("C");
        assertThat(json(admin, "/api/v1/systems/" + smSys + "/impact?targetId=" + tbEmp + "&column=NOPE")
                .get("screens")).isEmpty();
        mvc.perform(get("/api/v1/excel/export/impact?systemId=" + smSys + "&targetId=" + tbEmp)
                .header("Authorization", admin)).andExpect(status().isOk());
        // 고객: 화면 선택용 조회는 가능, 소스 스니펫은 숨김
        String cust = newUser(uniq("cu"), "CUSTOMER", "○○공사");
        addMember(sm, cust, "CUSTOMER");
        String custAuth = TestSupport.login(mvc, om, login(cust));
        assertThat(json(custAuth, "/api/v1/systems/" + smSys + "/menu-tree")).isNotEmpty();
        assertThat(json(custAuth, "/api/v1/screens/" + smScreen).get("elements")).hasSize(4);
        String saveFn = imp.get("serverFunctions").get(0).get("nodeId").asText();
        if (json(admin, "/api/v1/nodes/" + saveFn).hasNonNull("snippet")) {
            assertThat(json(custAuth, "/api/v1/nodes/" + saveFn).get("snippet").isNull()).isTrue();
        }
        fail(get("/api/v1/systems/" + smSys + "/impact?targetId=" + saveFn + "&column=X"), admin, null, 400);
    }

    JsonNode screen(String systemId, String code) throws Exception {
        for (JsonNode r : json(admin, "/api/v1/systems/" + systemId + "/analysis/screens?size=200").get("content")) {
            if (code.equals(r.get("screenCode").asText())) {
                return r;
            }
        }
        throw new AssertionError(code);
    }

    String element(String screenId, String code) throws Exception {
        for (JsonNode e : json(admin, "/api/v1/screens/" + screenId).get("elements")) {
            if (code.equals(e.get("elementCode").asText())) {
                return e.get("elementId").asText();
            }
        }
        throw new AssertionError(code);
    }

    List<String> steps(String elementId) throws Exception {
        List<String> out = new ArrayList<>();
        for (JsonNode ev : json(admin, "/api/v1/elements/" + elementId + "/workflow").get("events")) {
            for (JsonNode s : ev.get("steps")) {
                out.add(s.get("lane").asText() + ":" + s.get("title").asText() + ":" + s.get("depth").asInt());
            }
        }
        return out;
    }
}
