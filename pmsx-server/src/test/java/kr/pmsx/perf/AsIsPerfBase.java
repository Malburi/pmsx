package kr.pmsx.perf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * NFR-PRF-001·002 성능 측정 (docs/11 §6). 기본 테스트에서는 제외되고 `./gradlew perfTest` 로만 실행된다.
 * 합성 데이터: 화면 5,000 · 요소 200,000 · 노드 약 300,000 · 엣지 약 1,000,000 (한 시스템).
 * 측정값(중앙값·최대)을 build/perf/perf-{db}.md 로 남긴다.
 */
@Tag("perf")
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class AsIsPerfBase {

    static final int SCREENS = 5_000;
    static final int ELEMENTS_PER_SCREEN = 40;
    static final int FRONT_FN_PER_SCREEN = 20;
    static final int API_PER_SCREEN = 10;
    static final int TABLES = 3_000;
    static final int SQLS = 47_000;
    static final int MENU_GROUPS = 50;
    static final int BATCH = 5_000;

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper om;
    @Autowired
    JdbcTemplate jdbc;

    abstract String dbName();

    private final Map<String, Long> counts = new LinkedHashMap<>();

    @Test
    void 대규모_시스템_조회_성능() throws Exception {
        long t0 = System.nanoTime();
        String systemId = generate();
        long genMs = (System.nanoTime() - t0) / 1_000_000;
        // 데이터 생성이 access token 유효시간(30분)보다 길 수 있으므로 생성 후 로그인
        String auth = login();
        Random rnd = new Random(42);

        List<String> screenIds = jdbc.queryForList("SELECT SCREEN_ID FROM AS_SCREEN WHERE SYSTEM_ID = ?", String.class,
                systemId);
        List<String> elementIds = jdbc.queryForList("SELECT ELEMENT_ID FROM AS_ELEMENT WHERE SYSTEM_ID = ?"
                + " AND ELEMENT_CODE LIKE 'btn%' FETCH FIRST 5000 ROWS ONLY", String.class, systemId);

        Map<String, long[]> results = new LinkedHashMap<>();
        results.put("GET /systems/{id}/menu-tree", measure(auth, () -> "/api/v1/systems/" + systemId + "/menu-tree", 3));
        results.put("GET /screens/{id}", measure(auth,
                () -> "/api/v1/screens/" + screenIds.get(rnd.nextInt(screenIds.size())), 7));
        results.put("GET /elements/{id}/workflow", measure(auth,
                () -> "/api/v1/elements/" + elementIds.get(rnd.nextInt(elementIds.size())) + "/workflow", 7));
        results.put("GET /systems/{id}/search?q=화면 4321", measure(auth,
                () -> "/api/v1/systems/" + systemId + "/search?q=" + "화면 " + (1000 + rnd.nextInt(4000)), 7));
        results.put("GET /systems/{id}/search?q=fn_ (다수 일치)", measure(auth,
                () -> "/api/v1/systems/" + systemId + "/search?q=fn_", 5));
        results.put("GET /systems/{id}/analysis/progress", measure(auth,
                () -> "/api/v1/systems/" + systemId + "/analysis/progress", 3));
        results.put("GET /systems/{id}/crud (첫 호출: 색인 구성)", measure(auth,
                () -> "/api/v1/systems/" + systemId + "/crud", 1));
        results.put("GET /systems/{id}/crud (캐시)", measure(auth, () -> "/api/v1/systems/" + systemId + "/crud", 3));
        results.put("GET /systems/{id}/tables?page=0", measure(auth,
                () -> "/api/v1/systems/" + systemId + "/tables?size=50", 3));

        StringBuilder md = new StringBuilder();
        md.append("| 항목 | 중앙값(ms) | 최대(ms) | 목표 |\n|---|---|---|---|\n");
        Map<String, String> goals = Map.of("GET /screens/{id}", "≤ 2,000", "GET /elements/{id}/workflow", "≤ 1,000",
                "GET /systems/{id}/menu-tree", "≤ 2,000", "GET /systems/{id}/search?q=화면 4321", "≤ 1,000",
                "GET /systems/{id}/search?q=fn_ (다수 일치)", "≤ 1,000");
        results.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(v[0]).append(" | ").append(v[1])
                .append(" | ").append(goals.getOrDefault(k, "-")).append(" |\n"));
        md.append("\n데이터: ");
        counts.forEach((k, v) -> md.append(k).append(' ').append(String.format("%,d", v)).append(" · "));
        md.append("생성 ").append(String.format("%,d", genMs / 1000)).append("초\n");
        Path out = Path.of("build", "perf", "perf-" + dbName() + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, md.toString(), StandardCharsets.UTF_8);
        System.out.println("=== PERF " + dbName() + " ===\n" + md);
    }

    private interface Url {
        String next();
    }

    /** 워밍업 1회 후 n회 측정 → [중앙값, 최대] */
    private long[] measure(String auth, Url url, int n) throws Exception {
        mvc.perform(get(url.next()).header("Authorization", auth)).andExpect(status().isOk());
        long[] ms = new long[n];
        for (int i = 0; i < n; i++) {
            String u = url.next();
            long t = System.nanoTime();
            mvc.perform(get(u).header("Authorization", auth)).andExpect(status().isOk());
            ms[i] = (System.nanoTime() - t) / 1_000_000;
        }
        Arrays.sort(ms);
        return new long[] {ms[n / 2], ms[n - 1]};
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"admin\",\"password\":\"pmsx1234!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return "Bearer " + om.readTree(body).get("accessToken").asText();
    }

    // ───────────────────────── 합성 데이터 ─────────────────────────

    private final List<Object[]> buf = new ArrayList<>();

    private void flush(String sql) {
        if (!buf.isEmpty()) {
            jdbc.batchUpdate(sql, buf);
            buf.clear();
        }
    }

    private void add(String sql, Object... row) {
        buf.add(row);
        if (buf.size() >= BATCH) {
            flush(sql);
        }
    }

    private String generate() {
        String projectId = jdbc.queryForObject("SELECT PROJECT_ID FROM PJ_PROJECT ORDER BY CREATED_AT FETCH FIRST 1 ROWS"
                + " ONLY", String.class);
        String systemId = Ulid.next();
        OffsetDateTime now = Times.now();
        jdbc.update("INSERT INTO AS_SYSTEM (SYSTEM_ID, PROJECT_ID, CODE, NAME, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                systemId, projectId, "PERF" + systemId.substring(20), "성능 시스템", now);
        String runId = Ulid.next();
        jdbc.update("INSERT INTO AS_COLLECT_RUN (RUN_ID, SYSTEM_ID, SEQ, COLLECTED_AT, UPLOADED_AT) VALUES (?, ?, 1, ?, ?)",
                runId, systemId, now, now);
        String fileId = Ulid.next();
        jdbc.update("INSERT INTO CM_FILE (FILE_ID, PATH, MIME, SIZE_BYTES, SHA256, CREATED_AT) VALUES (?, 'x', 'image/png',"
                + " 1, 'x', ?)", fileId, now);

        // 공통 함수 1개
        String ins = "INSERT INTO AS_NODE (NODE_ID, SYSTEM_ID, NODE_KEY, NODE_TYPE, NAME, LANG, FILE_PATH, LINE_START,"
                + " COMMON, LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        add(ins, Ulid.next(), systemId, "fn:js:/js/cmm/common.js#gfn_ajax", "FN", "gfn_ajax", "js",
                "/js/cmm/common.js", 1, true, runId);
        long nodes = 1;
        for (int t = 0; t < TABLES; t++) {
            add(ins, Ulid.next(), systemId, "tbl:PERF.TB_" + t, "TABLE", "TB_" + t, null, null, null, false, runId);
            nodes++;
        }
        for (int q = 0; q < SQLS; q++) {
            add(ins, Ulid.next(), systemId, "sql:Mapper" + (q / 20) + ".stmt" + q, "SQL", "Mapper" + (q / 20) + ".stmt" + q,
                    "sql", "mapper/M" + (q / 20) + ".xml", q % 500, false, runId);
            nodes++;
        }
        for (int s = 0; s < SCREENS; s++) {
            for (int f = 0; f < FRONT_FN_PER_SCREEN; f++) {
                add(ins, Ulid.next(), systemId, frontKey(s, f), "FN", "fn_" + s + "_" + f, "js", "/js/S" + s + ".js",
                        f * 10, false, runId);
                nodes++;
            }
            for (int a = 0; a < API_PER_SCREEN; a++) {
                add(ins, Ulid.next(), systemId, apiKey(s, a), "API", "POST /s" + s + "/a" + a + ".do", null, null,
                        null, false, runId);
                add(ins, Ulid.next(), systemId, ctrlKey(s, a), "FN", "Ctrl" + s + ".act" + a, "java",
                        "web/Ctrl" + s + ".java", a * 10, false, runId);
                add(ins, Ulid.next(), systemId, svcKey(s, a), "FN", "Svc" + s + ".act" + a, "java",
                        "service/Svc" + s + ".java", a * 10, false, runId);
                nodes += 3;
            }
        }
        flush(ins);
        counts.put("노드", nodes);

        // 화면·메뉴·분석·캡처
        String scr = "INSERT INTO AS_SCREEN (SCREEN_ID, SYSTEM_ID, SCREEN_KEY, SCREEN_CODE, NAME, KIND, LAST_RUN_ID)"
                + " VALUES (?, ?, ?, ?, ?, 'page', ?)";
        String[] screenIds = new String[SCREENS];
        for (int s = 0; s < SCREENS; s++) {
            screenIds[s] = Ulid.next();
            add(scr, screenIds[s], systemId, "scr:P:S" + s, "S" + s, "화면 " + s, runId);
        }
        flush(scr);
        String menu = "INSERT INTO AS_MENU (MENU_ID, SYSTEM_ID, MENU_KEY, PARENT_KEY, NAME, SORT_ORD, SCREEN_KEY,"
                + " LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        for (int g = 0; g < MENU_GROUPS; g++) {
            add(menu, Ulid.next(), systemId, "menu:P:G" + g, null, "메뉴 " + g, g, null, runId);
        }
        for (int s = 0; s < SCREENS; s++) {
            add(menu, Ulid.next(), systemId, "menu:P:M" + s, "menu:P:G" + (s % MENU_GROUPS), "화면 " + s, s,
                    "scr:P:S" + s, runId);
        }
        flush(menu);
        String ana = "INSERT INTO AS_ANALYSIS (SCREEN_ID, STATUS, UPDATED_AT) VALUES (?, ?, ?)";
        String cap = "INSERT INTO AS_CAPTURE (CAPTURE_ID, SCREEN_ID, RUN_ID, FILE_ID, IDX, WIDTH, HEIGHT, COLLECTED_AT)"
                + " VALUES (?, ?, ?, ?, 0, 1280, 800, ?)";
        for (int s = 0; s < SCREENS; s++) {
            add(ana, screenIds[s], s % 4 == 0 ? "DONE" : "AUTO", now);
        }
        flush(ana);
        for (int s = 0; s < SCREENS; s++) {
            add(cap, Ulid.next(), screenIds[s], runId, fileId, now);
        }
        flush(cap);
        counts.put("화면", (long) SCREENS);

        String el = "INSERT INTO AS_ELEMENT (ELEMENT_ID, SYSTEM_ID, SCREEN_ID, ELEMENT_KEY, SEQ_NO, ELEMENT_CODE, LABEL,"
                + " TYPE, BOX_X, BOX_Y, BOX_W, BOX_H, MATCHED, LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'full', ?)";
        for (int s = 0; s < SCREENS; s++) {
            for (int e = 0; e < ELEMENTS_PER_SCREEN; e++) {
                String code = (e % 2 == 0 ? "btn" : "txt") + e;
                add(el, Ulid.next(), systemId, screenIds[s], elKey(s, code), e + 1, code, "요소 " + e,
                        e % 2 == 0 ? "button" : "input", 10.0 * e, 20.0, 60.0, 24.0, runId);
            }
        }
        flush(el);
        counts.put("요소", (long) SCREENS * ELEMENTS_PER_SCREEN);

        String ed = "INSERT INTO AS_EDGE (EDGE_ID, SYSTEM_ID, FROM_KEY, TO_KEY, KIND, EVENT, CRUD, SOURCE, CONFIDENCE,"
                + " MODEL_SEQ, LAST_RUN_ID) VALUES (?, ?, ?, ?, ?, ?, ?, 'static', 0.95, ?, ?)";
        long edges = 0;
        int seq = 0;
        Random rnd = new Random(7);
        for (int s = 0; s < SCREENS; s++) {
            for (int e = 0; e < ELEMENTS_PER_SCREEN; e++) {
                String code = (e % 2 == 0 ? "btn" : "txt") + e;
                add(ed, Ulid.next(), systemId, elKey(s, code), frontKey(s, e % FRONT_FN_PER_SCREEN), "event", "click",
                        null, seq++, runId);
                add(ed, Ulid.next(), systemId, elKey(s, code), "tbl:PERF.TB_" + rnd.nextInt(TABLES), "binds", "",
                        null, seq++, runId);
                edges += 2;
            }
            for (int f = 0; f < FRONT_FN_PER_SCREEN; f++) {
                add(ed, Ulid.next(), systemId, frontKey(s, f), "fn:js:/js/cmm/common.js#gfn_ajax", "calls", "", null,
                        seq++, runId);
                add(ed, Ulid.next(), systemId, frontKey(s, f), apiKey(s, f % API_PER_SCREEN), "request", "", null,
                        seq++, runId);
                add(ed, Ulid.next(), systemId, frontKey(s, f), apiKey(s, (f + 1) % API_PER_SCREEN), "request", "", null,
                        seq++, runId);
                add(ed, Ulid.next(), systemId, frontKey(s, f), frontKey(s, (f + 1) % FRONT_FN_PER_SCREEN), "calls", "",
                        null, seq++, runId);
                edges += 4;
            }
            for (int a = 0; a < API_PER_SCREEN; a++) {
                add(ed, Ulid.next(), systemId, apiKey(s, a), ctrlKey(s, a), "handles", "", null, seq++, runId);
                add(ed, Ulid.next(), systemId, ctrlKey(s, a), svcKey(s, a), "calls", "", null, seq++, runId);
                for (int k = 0; k < 3; k++) {
                    add(ed, Ulid.next(), systemId, svcKey(s, a), "sql:Mapper" + ((s * 30 + a * 3 + k) % SQLS / 20)
                            + ".stmt" + ((s * 30 + a * 3 + k) % SQLS), "executes", "", null, seq++, runId);
                }
                edges += 5;
            }
            if (s > 0) {
                add(ed, Ulid.next(), systemId, frontKey(s, 0), "scr:P:S" + (s - 1), "navigates", "", null, seq++, runId);
                edges++;
            }
        }
        String[] crud = {"R", "C", "U", "D", "CU"};
        for (int q = 0; q < SQLS; q++) {
            for (int k = 0; k < 2; k++) {
                add(ed, Ulid.next(), systemId, "sql:Mapper" + (q / 20) + ".stmt" + q,
                        "tbl:PERF.TB_" + ((q * 7 + k * 13) % TABLES), "accesses", "", crud[(q + k) % crud.length], seq++,
                        runId);
                edges++;
            }
        }
        flush(ed);
        counts.put("엣지", edges);
        return systemId;
    }

    private static String frontKey(int s, int f) {
        return "fn:js:/js/S" + s + ".js#fn_" + s + "_" + f;
    }

    private static String apiKey(int s, int a) {
        return "api:POST /s" + s + "/a" + a + ".do";
    }

    private static String ctrlKey(int s, int a) {
        return "fn:java:web/Ctrl" + s + ".java#Ctrl" + s + ".act" + a;
    }

    private static String svcKey(int s, int a) {
        return "fn:java:service/Svc" + s + ".java#Svc" + s + ".act" + a;
    }

    private static String elKey(int s, String code) {
        return "el:P:S" + s + "#" + code;
    }
}
