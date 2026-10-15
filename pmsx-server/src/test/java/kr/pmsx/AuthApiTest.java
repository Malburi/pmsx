package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    @Test
    void 로그인_성공시_토큰과_refresh_쿠키를_준다() throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"admin\",\"password\":\"pmsx1234!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.user.loginId").value("admin"))
                .andExpect(jsonPath("$.user.roles[0]").value("SYS_ADMIN"))
                .andReturn();
        Cookie rt = r.getResponse().getCookie("PMSX_RT");
        assertThat(rt).isNotNull();
        assertThat(rt.isHttpOnly()).isTrue();
        assertThat(rt.getPath()).isEqualTo("/api/v1/auth");

        // refresh → 새 access token, 쿠키 회전
        MvcResult r2 = mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("PMSX_RT", rt.getValue())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();
        String rotated = r2.getResponse().getCookie("PMSX_RT").getValue();
        assertThat(rotated).isNotEqualTo(rt.getValue());

        // 이미 쓴 refresh 토큰은 재사용 불가
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("PMSX_RT", rt.getValue())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_EXPIRED"));

        // 로그아웃 후 회전된 토큰도 무효
        mvc.perform(post("/api/v1/auth/logout").cookie(new Cookie("PMSX_RT", rotated)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("PMSX_RT", rotated)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 비밀번호가_틀리면_AUTH_INVALID() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID"));
    }

    @Test
    void 입력값이_비면_VALIDATION() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.loginId").exists());
    }

    @Test
    void 토큰_없이_보호_API를_부르면_AUTH_EXPIRED() throws Exception {
        mvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_EXPIRED"));
        mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer invalid.token.value"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void me는_프로젝트_역할까지_포함한다() throws Exception {
        String auth = TestSupport.login(mvc, om, "analyst");
        mvc.perform(get("/api/v1/auth/me").header("Authorization", auth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("김분석"))
                .andExpect(jsonPath("$.roles[0]").value("ANALYST"));
    }
}
