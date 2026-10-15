package kr.pmsx.sm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import kr.pmsx.as.ingest.IngestService;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.security.AuthUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 시연용 As-Is 수집 결과 적재 (demo·local 프로필의 데모 시드 전용).
 * <p>
 * 경로: {@code pmsx.demo.asis-packages} (zip 파일 또는 zip 폴더). 비어 있으면 {@code ${PMSX_HOME}/demo}·{@code ./demo}
 * (설치 패키지 동봉본) → 없으면 개발 환경의 {@code ../samples/*}{@code /out} 를 찾는다. 폴더 안에서는 시스템 코드별
 * 최신 zip 하나만 쓴다. 대상 프로젝트에 같은 코드의 시스템이 없으면 model.json 의 시스템 정보로 만든다.
 */
@Component
public class DemoAsIsLoader {

    private static final Logger log = LoggerFactory.getLogger(DemoAsIsLoader.class);
    private static final Pattern FILE_CODE = Pattern.compile("^pmsx-([A-Za-z0-9_]+)-.*\\.zip$");

    private final JdbcTemplate jdbc;
    private final PmsxProperties props;
    private final IngestService ingest;
    private final ObjectMapper json = new ObjectMapper();

    public DemoAsIsLoader(JdbcTemplate jdbc, PmsxProperties props, IngestService ingest) {
        this.jdbc = jdbc;
        this.props = props;
        this.ingest = ingest;
    }

    /** 적재한(또는 이미 적재돼 있던) 시스템 코드 → 시스템 ID */
    public Map<String, String> load(AuthUser admin, String projectId) {
        Map<String, String> loaded = new LinkedHashMap<>();
        for (Path zip : resolve()) {
            try {
                byte[] bytes = Files.readAllBytes(zip);
                JsonNode sys = systemOf(bytes);
                if (sys == null) {
                    log.warn("데모 As-Is 패키지에 model.json 시스템 정보가 없음: {}", zip);
                    continue;
                }
                String code = sys.path("code").asText();
                if (loaded.containsKey(code)) {
                    continue;
                }
                String systemId = systemFor(projectId, sys, admin.userId());
                Integer runs = jdbc.queryForObject("SELECT COUNT(*) FROM AS_COLLECT_RUN WHERE SYSTEM_ID = ?",
                        Integer.class, systemId);
                if (runs == null || runs == 0) {
                    ingest.ingest(admin, systemId, new BytesFile(zip.getFileName().toString(), bytes));
                    log.info("데모 As-Is 패키지 적재: {} → 시스템 {}", zip, code);
                }
                loaded.put(code, systemId);
            } catch (IOException | RuntimeException e) {
                log.warn("데모 As-Is 패키지 적재 실패({}): {}", zip, e.getMessage());
            }
        }
        if (loaded.isEmpty()) {
            log.info("데모 As-Is 패키지 없음 (pmsx.demo.asis-packages / ./demo / ../samples/*/out)");
        }
        return loaded;
    }

    /** 적재할 zip 목록 (설정 → 설치 패키지 demo 폴더 → 개발 환경 samples) */
    List<Path> resolve() {
        List<String> configured = props.demoAsisPackages();
        if (!configured.isEmpty()) {
            List<Path> out = new ArrayList<>();
            for (String c : configured) {
                out.addAll(zipsIn(Path.of(c)));
            }
            return out;
        }
        String home = System.getenv("PMSX_HOME");
        for (Path p : home == null ? List.of(Path.of("demo")) : List.of(Path.of(home, "demo"), Path.of("demo"))) {
            List<Path> z = zipsIn(p);
            if (!z.isEmpty()) {
                return z;
            }
        }
        Path samples = Path.of("..", "samples");
        List<Path> out = new ArrayList<>();
        if (Files.isDirectory(samples)) {
            // 인사시스템(hr-legacy) 을 먼저 — SM 데모가 이 시스템을 인수인계한다
            for (String s : List.of("hr-legacy", "hr-nexacro", "hr-spa")) {
                out.addAll(zipsIn(samples.resolve(s).resolve("out")));
            }
        }
        return out;
    }

    /** 파일이면 그대로, 폴더면 시스템 코드별 최신 zip */
    static List<Path> zipsIn(Path p) {
        if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".zip")) {
            return List.of(p);
        }
        if (!Files.isDirectory(p)) {
            return List.of();
        }
        Map<String, Path> latest = new LinkedHashMap<>();
        try (Stream<Path> s = Files.list(p)) {
            s.filter(x -> x.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(x -> x.getFileName().toString()))
                    .forEach(x -> {
                        Matcher m = FILE_CODE.matcher(x.getFileName().toString());
                        latest.put(m.matches() ? m.group(1) : x.getFileName().toString(), x);   // 정렬상 마지막이 최신
                    });
        } catch (IOException e) {
            return List.of();
        }
        // 인사시스템(HR) 먼저
        List<Path> out = new ArrayList<>(latest.values());
        out.sort(Comparator.comparing(x -> x.getFileName().toString().startsWith("pmsx-HR-") ? 0 : 1));
        return out;
    }

    private JsonNode systemOf(byte[] zip) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if ("model.json".equals(e.getName())) {
                    JsonNode sys = json.readTree(in.readAllBytes()).path("system");
                    return sys.isMissingNode() || sys.path("code").asText().isBlank() ? null : sys;
                }
            }
        }
        return null;
    }

    private String systemFor(String projectId, JsonNode sys, String userId) {
        String code = sys.path("code").asText();
        String existing = jdbc.query("SELECT SYSTEM_ID FROM AS_SYSTEM WHERE PROJECT_ID = ? AND CODE = ?",
                (rs, i) -> rs.getString(1), projectId, code).stream().findFirst().orElse(null);
        if (existing != null) {
            return existing;
        }
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO AS_SYSTEM (SYSTEM_ID, PROJECT_ID, CODE, NAME, UI_TECH, SERVER_TECH, DB_TYPE, CREATED_AT,
                                       CREATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, projectId, code, sys.path("name").asText(code), join(sys.path("uiTech")), join(sys.path("serverTech")),
                sys.path("dbType").asText("oracle"), Times.now(), userId);
        return id;
    }

    private static String join(JsonNode arr) {
        if (!arr.isArray()) {
            return arr.isMissingNode() || arr.isNull() ? null : arr.asText();
        }
        List<String> v = new ArrayList<>();
        arr.forEach(x -> v.add(x.asText()));
        return String.join(",", v);
    }

    /** 바이트 배열 MultipartFile (데모 적재용) */
    record BytesFile(String name, byte[] bytes) implements MultipartFile {

        @Override
        public String getName() {
            return "file";
        }

        @Override
        public String getOriginalFilename() {
            return name;
        }

        @Override
        public String getContentType() {
            return "application/zip";
        }

        @Override
        public boolean isEmpty() {
            return bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IOException {
            Files.write(dest.toPath(), bytes);
        }
    }
}
