package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
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

/** docs/10 §3 엑셀 엔진: 데이터셋, 내보내기, 가져오기(검증→커밋), 템플릿 셀 매핑 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExcelApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;

    private JsonNode body(ResultActions r) throws Exception {
        return om.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void 데이터셋_목록() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        JsonNode ds = TestSupport.json(mvc, om, admin, "/api/v1/excel/datasets");
        assertThat(ds.findValuesAsText("datasetId"))
                .contains("codes", "terms", "users", "audit-logs", "as-elements", "as-screens");
    }

    @Test
    void 공통코드_가져오기_검증_커밋_내보내기() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        String g = ("X_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)).toUpperCase();
        mvc.perform(post("/api/v1/codes/groups").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupCode\":\"" + g + "\",\"name\":\"엑셀 테스트\"}")).andExpect(status().isCreated());

        byte[] xlsx = workbook(new String[][] {
                {"코드", "코드명", "정렬순서", "사용여부"},
                {"A01", "첫째", "1", "Y"},
                {"A02", "", "2", "Y"},          // 코드명 누락
                {"A03", "셋째", "삼", "N"},       // 숫자 아님
                {"A04", "넷째", "4", "X"},        // 사용여부 오류
                {"", "", "", ""},                 // 빈 행 무시
                {"A05", "다섯째", "5", "N"}});
        MockMultipartFile file = new MockMultipartFile("file", "codes.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        mvc.perform(multipart("/api/v1/excel/import/codes/validate").file(file).param("groupCode", g)
                .header("Authorization", analyst)).andExpect(status().isForbidden());
        JsonNode preview = body(mvc.perform(multipart("/api/v1/excel/import/codes/validate").file(file)
                .param("groupCode", g).header("Authorization", admin)).andExpect(status().isOk()));
        assertThat(preview.get("validCount").asInt()).isEqualTo(2);
        assertThat(preview.get("errorCount").asInt()).isEqualTo(3);
        assertThat(preview.at("/rows/1/rowNo").asInt()).isEqualTo(3);
        assertThat(preview.at("/rows/1/errors/name").asText()).contains("필수");
        assertThat(preview.at("/rows/2/errors/sortOrder").asText()).contains("숫자");
        assertThat(preview.at("/rows/3/errors/useYn").asText()).isNotEmpty();

        // 다른 사용자/다른 데이터셋 토큰은 거부
        String token = preview.get("token").asText();
        mvc.perform(post("/api/v1/excel/import/terms/commit").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/excel/import/codes/commit").header("Authorization", admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(0));
        // 토큰은 1회용
        mvc.perform(post("/api/v1/excel/import/codes/commit").header("Authorization", admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isBadRequest());

        JsonNode codes = TestSupport.json(mvc, om, admin, "/api/v1/codes/groups/" + g + "/codes");
        assertThat(codes.findValuesAsText("code")).containsExactly("A01", "A05");
        assertThat(codes.get(1).get("useYn").asBoolean()).isFalse();

        // 내보내기 → 다시 읽어 확인
        byte[] out = mvc.perform(get("/api/v1/excel/export/codes").param("groupCode", g).header("Authorization", analyst))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getRow(0).getCell(0).getStringCellValue()).isEqualTo("코드");
            assertThat(sh.getRow(1).getCell(1).getStringCellValue()).isEqualTo("첫째");
            assertThat(sh.getRow(2).getCell(3).getStringCellValue()).isEqualTo("N");
            assertThat(sh.getLastRowNum()).isEqualTo(2);
        }
        // 감사 로그 DOWNLOAD
        JsonNode logs = TestSupport.json(mvc, om, admin, "/api/v1/admin/audit-logs?action=DOWNLOAD&q=excel:codes");
        assertThat(logs.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void 템플릿_셀매핑_내보내기() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        byte[] tpl = workbook(new String[][] {
                {"인사시스템 공통코드 현황"},
                {"고객사:", "", "출력일:"},
                {""},
                {"No", "코드", "코드명"},
                {"", "", ""}});
        String meta = "{\"name\":\"고객사 코드 양식\",\"datasetId\":\"codes\",\"mapping\":{\"sheet\":\"Sheet1\","
                + "\"startRow\":5,\"columns\":{\"code\":\"B\",\"name\":\"C\"},\"cells\":{\"exportedBy\":\"B2\","
                + "\"exportedAt\":\"D2\"}}}";
        JsonNode t = body(mvc.perform(multipart("/api/v1/excel/templates")
                        .file(new MockMultipartFile("file", "양식.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", tpl))
                        .part(new MockPart("meta", meta.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", admin))
                .andExpect(status().isCreated()));
        String templateId = t.get("templateId").asText();
        assertThat(t.at("/mapping/columns/code").asText()).isEqualTo("B");

        // 잘못된 매핑
        String bad = "{\"name\":\"x\",\"datasetId\":\"codes\",\"mapping\":{\"startRow\":0,\"columns\":{\"nope\":\"1\"}}}";
        mvc.perform(multipart("/api/v1/excel/templates")
                        .file(new MockMultipartFile("file", "양식.xlsx", "application/octet-stream", tpl))
                        .part(new MockPart("meta", bad.getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fields['mapping.startRow']").exists());

        byte[] out = mvc.perform(get("/api/v1/excel/export/codes").param("groupCode", "PROJECT_TYPE")
                        .param("templateId", templateId).header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(out))) {
            Sheet sh = wb.getSheet("Sheet1");
            assertThat(sh.getRow(0).getCell(0).getStringCellValue()).isEqualTo("인사시스템 공통코드 현황");
            assertThat(sh.getRow(1).getCell(1).getStringCellValue()).isEqualTo("관리자");
            assertThat(sh.getRow(1).getCell(3).getStringCellValue()).startsWith("20");
            assertThat(sh.getRow(4).getCell(1).getStringCellValue()).isEqualTo("SI");
            assertThat(sh.getRow(5).getCell(2).getStringCellValue()).isEqualTo("SM 운영");
        }

        // 메타만 수정(PUT, 파일 생략)
        String meta2 = meta.replace("고객사 코드 양식", "고객사 코드 양식 v2");
        mvc.perform(multipart("/api/v1/excel/templates/" + templateId).part(
                                new MockPart("meta", meta2.getBytes(StandardCharsets.UTF_8)))
                        .with(r -> {
                            r.setMethod("PUT");
                            return r;
                        }).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("고객사 코드 양식 v2"));
        JsonNode list = TestSupport.json(mvc, om, admin, "/api/v1/excel/templates?datasetId=codes");
        assertThat(list.findValuesAsText("templateId")).contains(templateId);
        // 템플릿 파일은 /files 로 내려받을 수 있다
        mvc.perform(get("/api/v1/files/" + t.get("fileId").asText()).header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/excel/templates/" + templateId).header("Authorization", admin))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void 사용자_감사로그_화면목록_내보내기_권한() throws Exception {
        String admin = TestSupport.login(mvc, om, "admin");
        String analyst = TestSupport.login(mvc, om, "analyst");
        mvc.perform(get("/api/v1/excel/export/users").header("Authorization", analyst)).andExpect(status().isForbidden());
        byte[] users = mvc.perform(get("/api/v1/excel/export/users").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(users))) {
            assertThat(wb.getSheetAt(0).getLastRowNum()).isGreaterThanOrEqualTo(2);
        }
        mvc.perform(get("/api/v1/excel/export/audit-logs").param("from", "2026-01-01").header("Authorization", admin))
                .andExpect(status().isOk());
        String systemId = TestSupport.hrSystemId(mvc, om, admin);
        mvc.perform(get("/api/v1/excel/export/as-screens").param("systemId", systemId)
                .header("Authorization", analyst)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/excel/export/as-elements").header("Authorization", analyst))
                .andExpect(status().isBadRequest());
    }

    private static byte[] workbook(String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sh.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(bos);
            return bos.toByteArray();
        }
    }
}
