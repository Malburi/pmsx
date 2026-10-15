package kr.pmsx;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 테스트 공통: 로그인, 픽스처 패키지(zip) 생성. */
public final class TestSupport {

    private TestSupport() {
    }

    public static String login(MockMvc mvc, ObjectMapper om, String loginId) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"pmsx1234!\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + om.readTree(body).get("accessToken").asText();
    }

    /** 시드된 프로젝트의 HR 시스템 ID */
    public static String hrSystemId(MockMvc mvc, ObjectMapper om, String auth) throws Exception {
        String projectId = seedProjectId(mvc, om, auth);
        JsonNode systems = json(mvc, om, auth, "/api/v1/projects/" + projectId + "/systems");
        for (JsonNode s : systems) {
            if ("HR".equals(s.get("code").asText())) {
                return s.get("systemId").asText();
            }
        }
        throw new IllegalStateException("HR 시스템 시드가 없습니다.");
    }

    /** 시드 프로젝트에 새 시스템을 만들고 ID 를 돌려준다 (테스트 간 데이터 간섭 방지) */
    public static String newSystem(MockMvc mvc, ObjectMapper om, String auth, String code) throws Exception {
        String projectId = seedProjectId(mvc, om, auth);
        String body = mvc.perform(post("/api/v1/projects/" + projectId + "/systems").header("Authorization", auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"테스트 " + code + "\",\"uiTech\":[\"jsp-jquery\"],"
                                + "\"serverTech\":[\"spring-mybatis\"],\"dbType\":\"oracle\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body).get("systemId").asText();
    }

    /** 시드 프로젝트(○○공사 차세대 SI) ID — 다른 테스트가 프로젝트를 추가해도 순서에 의존하지 않는다 */
    public static String seedProjectId(MockMvc mvc, ObjectMapper om, String auth) throws Exception {
        for (JsonNode p : json(mvc, om, auth, "/api/v1/projects")) {
            if (p.get("name").asText().contains("차세대")) {
                return p.get("projectId").asText();
            }
        }
        throw new IllegalStateException("시드 프로젝트가 없습니다.");
    }

    public static JsonNode json(MockMvc mvc, ObjectMapper om, String auth, String url) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", auth))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return om.readTree(body);
    }

    public static ObjectNode fixtureModel(ObjectMapper om) throws IOException {
        try (InputStream in = TestSupport.class.getResourceAsStream("/fixture/model.json")) {
            return (ObjectNode) om.readTree(in);
        }
    }

    public static byte[] fixtureZip(ObjectMapper om, Consumer<ObjectNode> modify) throws IOException {
        ObjectNode model = fixtureModel(om);
        if (modify != null) {
            modify.accept(model);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry("model.json"));
            zip.write(om.writeValueAsBytes(model));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("captures/HR0101.png"));
            zip.write(onePixelPng());
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("report.html"));
            zip.write("<html><body><h1>수집 리포트</h1></body></html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    public static byte[] onePixelPng() throws IOException {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
