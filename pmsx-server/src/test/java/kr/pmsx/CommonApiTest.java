package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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

/** docs/10 공통 기능: 공통코드·용어, 첨부, 사용자·멤버, 비밀번호 정책·잠금, 감사 로그 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommonApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;

    private JsonNode body(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String uniq(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Test
    void 공통코드_그룹_코드_일괄저장_콤보조회() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");

        JsonNode groups = TestSupport.json(mvc, om, analyst, "/api/v1/codes/groups");
        assertThat(groups.findValuesAsText("groupCode")).contains("PROJECT_TYPE", "ELEMENT_TYPE", "GRADE");

        String g = uniq("G_").toUpperCase();
        // 분석자는 변경 불가
        mvc.perform(post("/api/v1/codes/groups").header("Authorization", analyst).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupCode\":\"" + g + "\",\"name\":\"테스트\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/codes/groups").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupCode\":\"" + g + "\",\"name\":\"테스트 그룹\",\"description\":\"설명\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.system").value(false));
        mvc.perform(post("/api/v1/codes/groups").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupCode\":\"" + g + "\",\"name\":\"중복\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));

        // 일괄 저장: 중복 코드 → 400 fields
        mvc.perform(put("/api/v1/codes/groups/" + g + "/codes").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"A\",\"name\":\"가\",\"sortOrder\":1,\"useYn\":true},"
                                + "{\"code\":\"A\",\"name\":\"나\",\"sortOrder\":2,\"useYn\":true}]"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['[1].code']").exists());
        mvc.perform(put("/api/v1/codes/groups/" + g + "/codes").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"code\":\"B\",\"name\":\"나\",\"sortOrder\":2,\"useYn\":true},"
                                + "{\"code\":\"A\",\"name\":\"가\",\"sortOrder\":1,\"useYn\":true},"
                                + "{\"code\":\"Z\",\"name\":\"미사용\",\"sortOrder\":3,\"useYn\":false}]"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].code").value("A"));
        JsonNode combo = TestSupport.json(mvc, om, analyst, "/api/v1/codes?groups=" + g + ",PROJECT_TYPE");
        assertThat(combo.get(g)).hasSize(2);   // 미사용 Z 제외
        assertThat(combo.get("PROJECT_TYPE").get(0).get("code").asText()).isEqualTo("SI");

        // 기준 그룹 삭제 불가, 사용자 그룹 삭제 가능
        mvc.perform(delete("/api/v1/codes/groups/PROJECT_TYPE").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/codes/groups/" + g).header("Authorization", admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/codes/groups/" + g + "/codes").header("Authorization", admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void 용어_CRUD_검색() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String term = uniq("사원번호");
        JsonNode created = body(mvc.perform(post("/api/v1/terms").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"" + term + "\",\"abbr\":\"EMP_NO\",\"definition\":\"사원 식별 번호\","
                                + "\"domain\":\"번호\"}"))
                .andExpect(status().isCreated()));
        String id = created.get("termId").asText();
        JsonNode page = TestSupport.json(mvc, om, admin, "/api/v1/terms?q=" + term + "&page=0&size=10");
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        assertThat(page.at("/content/0/domain").asText()).isEqualTo("번호");
        mvc.perform(put("/api/v1/terms/" + id).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"" + term + "\",\"definition\":\"변경\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.definition").value("변경"));
        mvc.perform(post("/api/v1/terms").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.term").exists());
        mvc.perform(delete("/api/v1/terms/" + id).header("Authorization", admin)).andExpect(status().isNoContent());
    }

    @Test
    void 첨부_버전_목록_다운로드_미리보기_제한() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String target = uniq("T");

        JsonNode v1 = body(mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "회의록.txt", "text/plain", "v1".getBytes()))
                        .param("targetType", "TEST").param("targetId", target).header("Authorization", analyst))
                .andExpect(status().isCreated()));
        assertThat(v1.get("version").asInt()).isEqualTo(1);
        JsonNode v2 = body(mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "회의록.txt", "text/plain", "v2-content".getBytes()))
                        .param("targetType", "TEST").param("targetId", target).header("Authorization", analyst))
                .andExpect(status().isCreated()));
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("attachId").asText()).isEqualTo(v1.get("attachId").asText());
        assertThat(v2.get("versions")).hasSize(2);
        assertThat(v2.get("size").asLong()).isEqualTo(10);
        assertThat(v2.at("/uploadedBy/name").asText()).isEqualTo("김분석");

        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "도면.png", "image/png", TestSupport.onePixelPng()))
                        .param("targetType", "TEST").param("targetId", target).header("Authorization", analyst))
                .andExpect(status().isCreated());
        JsonNode list = TestSupport.json(mvc, om, analyst, "/api/v1/attachments?targetType=TEST&targetId=" + target);
        assertThat(list).hasSize(2);

        String fileId = v2.get("fileId").asText();
        mvc.perform(get("/api/v1/files/" + fileId).header("Authorization", analyst))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")));
        mvc.perform(get("/api/v1/files/" + fileId + "/preview").header("Authorization", analyst))
                .andExpect(status().isUnsupportedMediaType());
        String pngId = list.get(1).get("fileId").asText();
        mvc.perform(get("/api/v1/files/" + pngId + "/preview").header("Authorization", analyst))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/png"));

        // 허용되지 않는 확장자
        mvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("file", "run.exe", "application/octet-stream", new byte[] {1}))
                        .param("targetType", "TEST").param("targetId", target).header("Authorization", analyst))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));

        // 삭제: 올린 사람이 아닌 일반 사용자는 불가, 관리자는 가능
        String attachId = v2.get("attachId").asText();
        mvc.perform(delete("/api/v1/attachments/" + attachId).header("Authorization", admin))
                .andExpect(status().isNoContent());
        assertThat(TestSupport.json(mvc, om, analyst, "/api/v1/attachments?targetType=TEST&targetId=" + target))
                .hasSize(1);
        // 삭제된 첨부 파일은 다운로드 불가
        mvc.perform(get("/api/v1/files/" + fileId).header("Authorization", analyst)).andExpect(status().isNotFound());

        // 감사 로그에 업로드·다운로드가 남는다
        JsonNode logs = TestSupport.json(mvc, om, admin, "/api/v1/admin/audit-logs?q=" + target + "&size=50");
        assertThat(logs.get("content").findValuesAsText("action")).contains("UPLOAD", "DELETE");
        JsonNode dl = TestSupport.json(mvc, om, admin, "/api/v1/admin/audit-logs?action=DOWNLOAD&q=" + fileId);
        assertThat(dl.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 사용자관리_비밀번호정책_잠금_변경_감사로그() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String loginId = uniq("u");

        mvc.perform(get("/api/v1/admin/users").header("Authorization", analyst)).andExpect(status().isForbidden());
        // 정책 위반
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(loginId, "short1!")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(userJson(loginId, "onlyletters!!")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
        JsonNode u = body(mvc.perform(post("/api/v1/admin/users").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(userJson(loginId, "Start123!x")))
                .andExpect(status().isCreated()));
        String userId = u.get("userId").asText();
        assertThat(u.get("orgType").asText()).isEqualTo("PARTNER");
        mvc.perform(post("/api/v1/admin/users").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content(userJson(loginId, "Start123!x"))).andExpect(status().isConflict());

        JsonNode page = TestSupport.json(mvc, om, admin, "/api/v1/admin/users?q=" + loginId);
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);

        // 5회 실패 → 잠금(423)
        for (int i = 1; i <= 4; i++) {
            login(loginId, "wrong-pass1!").andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
        }
        login(loginId, "wrong-pass1!").andExpect(status().isLocked()).andExpect(jsonPath("$.code").value("AUTH_LOCKED"));
        login(loginId, "Start123!x").andExpect(status().isLocked());
        assertThat(TestSupport.json(mvc, om, admin, "/api/v1/admin/users?q=" + loginId).at("/content/0/locked")
                .asBoolean()).isTrue();

        // 잠금 해제 후 로그인
        mvc.perform(post("/api/v1/admin/users/" + userId + "/unlock").header("Authorization", admin))
                .andExpect(status().isNoContent());
        String auth = "Bearer " + body(login(loginId, "Start123!x").andExpect(status().isOk()))
                .get("accessToken").asText();

        // 본인 비밀번호 변경: 현재 비밀번호 틀림 / 같은 비밀번호 재사용 / 성공
        changePw(auth, "nope", "Next456!y").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.currentPassword").exists());
        changePw(auth, "Start123!x", "Start123!x").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PASSWORD_POLICY"));
        changePw(auth, "Start123!x", "Next456!y").andExpect(status().isNoContent());
        login(loginId, "Next456!y").andExpect(status().isOk());
        // 관리자 초기화: 직전 비밀번호 재사용 불가
        mvc.perform(post("/api/v1/admin/users/" + userId + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Start123!x\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/users/" + userId + "/reset-password").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"Reset789!z\"}"))
                .andExpect(status().isNoContent());

        // 잠금 → refresh 무효, 로그인 불가
        mvc.perform(post("/api/v1/admin/users/" + userId + "/lock").header("Authorization", admin))
                .andExpect(status().isNoContent());
        login(loginId, "Reset789!z").andExpect(status().isLocked());

        // 사용자 정보 변경 (시스템 역할 PMO 부여)
        mvc.perform(put("/api/v1/admin/users/" + userId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"변경된이름\",\"orgType\":\"INTERNAL\",\"authType\":\"LOCAL\","
                                + "\"roles\":[\"PMO\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.roles[0]").value("PMO"))
                .andExpect(jsonPath("$.name").value("변경된이름"));
        mvc.perform(put("/api/v1/admin/users/" + userId).header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"orgType\":\"INTERNAL\",\"authType\":\"LOCAL\",\"roles\":[\"PM\"]}"))
                .andExpect(status().isBadRequest());   // PM 은 프로젝트 역할

        // 감사 로그: 로그인 실패·잠금·로그인 기록
        JsonNode logs = TestSupport.json(mvc, om, admin, "/api/v1/admin/audit-logs?q=" + loginId + "&size=100");
        assertThat(logs.get("content").findValuesAsText("action")).contains("LOGIN_FAIL", "LOGIN", "CREATE", "UPDATE");
        // 분석자는 감사 로그 조회 불가
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", analyst)).andExpect(status().isForbidden());
        // 웹 화면 접근 기록
        mvc.perform(post("/api/v1/audit/access").header("Authorization", analyst)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"path\":\"/asis/viewer/x\"}"))
                .andExpect(status().isNoContent());
        JsonNode access = TestSupport.json(mvc, om, admin, "/api/v1/admin/audit-logs?action=ACCESS&q=/asis/viewer/x");
        assertThat(access.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);

        JsonNode roles = TestSupport.json(mvc, om, analyst, "/api/v1/admin/roles");
        assertThat(roles.findValuesAsText("roleId")).contains("SYS_ADMIN", "ANALYST");
    }

    @Test
    void 프로젝트_멤버_추가_변경_제외() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String projectId = TestSupport.seedProjectId(mvc, om, admin);
        String loginId = uniq("m");
        String userId = body(mvc.perform(post("/api/v1/admin/users").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content(userJson(loginId, "Member123!")))
                .andExpect(status().isCreated())).get("userId").asText();

        String url = "/api/v1/projects/" + projectId + "/members";
        mvc.perform(post(url).header("Authorization", analyst).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"roleId\":\"DEV\"}")).andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"roleId\":\"SYS_ADMIN\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"" + userId + "\",\"roleId\":\"PARTNER\",\"inDate\":\"2026-11-02\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.roleId").value("PARTNER"));
        mvc.perform(post(url).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\",\"roleId\":\"DEV\"}")).andExpect(status().isConflict());
        mvc.perform(put(url + "/" + userId).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"DEV\",\"inDate\":\"2026-11-02\",\"outDate\":\"2026-10-01\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields.outDate").exists());
        mvc.perform(put(url + "/" + userId).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roleId\":\"DEV\",\"inDate\":\"2026-11-02\",\"outDate\":\"2027-03-31\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outDate").value("2027-03-31"));
        JsonNode members = TestSupport.json(mvc, om, analyst, url);
        assertThat(members.findValuesAsText("loginId")).contains(loginId, "admin", "analyst");
        mvc.perform(delete(url + "/" + userId).header("Authorization", admin)).andExpect(status().isNoContent());
        assertThat(TestSupport.json(mvc, om, admin, url).findValuesAsText("loginId")).doesNotContain(loginId);
    }

    @Test
    void 라이선스_검사_생략_프로필과_시스템정보() throws Exception {
        mvc.perform(get("/api/v1/system/license-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));
        String admin = TestSupport.login(mvc, om, "admin");
        JsonNode info = TestSupport.json(mvc, om, admin, "/api/v1/system/info");
        assertThat(info.get("db").asText()).startsWith("H2");
        assertThat(info.get("javaVersion").asText()).startsWith("21");
        assertThat(info.at("/users/total").asLong()).isGreaterThanOrEqualTo(2);
    }

    private ResultActions login(String loginId, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions changePw(String auth, String current, String next) throws Exception {
        return mvc.perform(post("/api/v1/auth/change-password").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"" + current + "\",\"newPassword\":\"" + next + "\"}"));
    }

    private static String userJson(String loginId, String password) {
        return "{\"loginId\":\"" + loginId + "\",\"name\":\"테스트 사용자\",\"email\":\"" + loginId + "@example.com\","
                + "\"orgType\":\"PARTNER\",\"authType\":\"LOCAL\",\"roles\":[],\"password\":\"" + password + "\"}";
    }
}
