package kr.pmsx;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 날짜 파라미터(날짜/ISO 일시 모두 수용)와 요청 형식 오류의 400 처리 (500 금지). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ParamValidationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    @Test
    void 감사로그_from_to_날짜와_ISO일시_모두_수용() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", admin)
                        .param("from", "2026-10-08T00:00:00+09:00").param("to", "2099-10-15T23:59:59.999+09:00"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").isNumber());
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", admin)
                        .param("from", "2026-10-08").param("to", "2099-12-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isArray());
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", admin)
                        .param("from", "2026-10-08T00:00:00").param("to", "2099-12-31T00:00:00Z"))
                .andExpect(status().isOk());
        // 엑셀 내보내기도 같은 해석
        mvc.perform(get("/api/v1/excel/export/audit-logs").header("Authorization", admin)
                        .param("from", "2026-10-08T00:00:00+09:00").param("to", "2099-10-15"))
                .andExpect(status().isOk());
    }

    @Test
    void 잘못된_파라미터는_400_VALIDATION() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", admin).param("from", "어제"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.from").exists());
        // 숫자 파라미터 타입 불일치 (MethodArgumentTypeMismatchException)
        mvc.perform(get("/api/v1/admin/audit-logs").header("Authorization", admin).param("page", "abc"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.page").exists());
        // 읽을 수 없는 본문 (HttpMessageNotReadableException)
        mvc.perform(post("/api/v1/audit/access").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"));
        // 필수 파라미터 누락 (MissingServletRequestParameterException)
        mvc.perform(get("/api/v1/users/lookup").header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION"))
                .andExpect(jsonPath("$.fields.q").exists());
    }
}
