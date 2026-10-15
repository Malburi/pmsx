package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** docs/10 §6 라이선스: 검사 사용 시 무효면 SYS_ADMIN 만 로그인, 키 적용·만료·서명 검증 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LicenseTest {

    static final KeyPair KEYS;

    static {
        try {
            KEYS = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:pmsx-license;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                + "DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        r.add("pmsx.license.enforce", () -> "true");
        r.add("pmsx.license.public-key", () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    static String issue(String customer, String expires, int maxUsers) throws Exception {
        String json = "{\"customer\":\"" + customer + "\",\"issuedAt\":\"2026-10-15\",\"expiresAt\":\"" + expires
                + "\",\"maxUsers\":" + maxUsers + ",\"features\":[\"asis\"]}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(KEYS.getPrivate());
        s.update(payload);
        Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        return b.encodeToString(payload) + "." + b.encodeToString(s.sign());
    }

    private ResultActions login(String id) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginId\":\"" + id + "\",\"password\":\"pmsx1234!\"}"));
    }

    private ResultActions apply(String auth, String key) throws Exception {
        return mvc.perform(post("/api/v1/system/license").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + key + "\"}"));
    }

    @Test
    void 라이선스_무효시_관리자만_로그인하고_키_적용후_정상() throws Exception {
        mvc.perform(get("/api/v1/system/license-status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        login("analyst").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LICENSE_INVALID"));
        String admin = TestSupport.login(mvc, om, "admin");

        // 서명 위조 / 만료 키는 거부
        String good = issue("테스트고객", "2099-12-31", 100);
        String forged = good.substring(0, good.indexOf('.')) + "." + issue("x", "2099-12-31", 1).split("\\.")[1];
        apply(admin, forged).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("LICENSE_INVALID"));
        apply(admin, issue("만료고객", "2020-01-01", 100)).andExpect(status().isBadRequest());
        apply(admin, "not-a-key").andExpect(status().isBadRequest());
        String analystAuth = null;
        login("analyst").andExpect(status().isForbidden());

        // 정상 키 적용
        apply(admin, good).andExpect(status().isOk()).andExpect(jsonPath("$.customer").value("테스트고객"))
                .andExpect(jsonPath("$.valid").value(true));
        mvc.perform(get("/api/v1/system/license-status")).andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.expiresAt").value("2099-12-31"));
        analystAuth = TestSupport.login(mvc, om, "analyst");

        // 정보: 관리자는 상세, 일반 사용자는 valid 만
        JsonNode adminInfo = TestSupport.json(mvc, om, admin, "/api/v1/system/info");
        assertThat(adminInfo.at("/license/customer").asText()).isEqualTo("테스트고객");
        assertThat(adminInfo.at("/license/maxUsers").asInt()).isEqualTo(100);
        JsonNode userInfo = TestSupport.json(mvc, om, analystAuth, "/api/v1/system/info");
        assertThat(userInfo.at("/license/customer").isNull()).isTrue();
        assertThat(userInfo.at("/license/valid").asBoolean()).isTrue();
        mvc.perform(post("/api/v1/system/license").header("Authorization", analystAuth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"key\":\"" + good + "\"}"))
                .andExpect(status().isForbidden());

        // 사용자 수 초과 키: 적용은 되지만 무효 → 일반 사용자 로그인 차단
        apply(admin, issue("소규모", "2099-12-31", 1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        mvc.perform(get("/api/v1/system/license-status")).andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.reason").value(org.hamcrest.Matchers.containsString("사용자 수")));
        login("analyst").andExpect(status().isForbidden());
        login("admin").andExpect(status().isOk());

        // 원복
        apply(admin, good).andExpect(status().isOk());
        login("analyst").andExpect(status().isOk());
        JsonNode logs = TestSupport.json(mvc, om, admin, "/api/v1/admin/audit-logs?action=LICENSE");
        assertThat(logs.get("totalElements").asLong()).isGreaterThanOrEqualTo(3);
    }
}
