package kr.pmsx.as;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {

    public record SystemCreate(
            @NotBlank(message = "시스템 코드를 입력해주세요.")
            @Pattern(regexp = "[A-Za-z0-9_]{1,30}", message = "영문·숫자·_ 30자 이내로 입력해주세요.") String code,
            @NotBlank(message = "시스템명을 입력해주세요.") @Size(max = 200) String name,
            // 비워 두면 첫 수집 패키지(model.json system.*)의 감지 값으로 자동 채운다 (IngestService.fillDetectedTech)
            List<String> uiTech,
            List<String> serverTech,
            @Size(max = 30) String dbType,
            @Size(max = 500) String testUrl) {
    }

    public record LastRun(String runId, int seq, OffsetDateTime collectedAt) {
    }

    public record SystemSummary(String systemId, String projectId, String code, String name, List<String> uiTech,
                                List<String> serverTech, String dbType, String testUrl, int screenCount,
                                int doneCount, LastRun lastRun) {
    }

    private static final String SELECT = """
            SELECT S.SYSTEM_ID, S.PROJECT_ID, S.CODE, S.NAME, S.UI_TECH, S.SERVER_TECH, S.DB_TYPE, S.TEST_URL,
                   (SELECT COUNT(*) FROM AS_SCREEN C WHERE C.SYSTEM_ID = S.SYSTEM_ID) AS SCREEN_CNT,
                   (SELECT COUNT(*) FROM AS_SCREEN C JOIN AS_ANALYSIS A ON A.SCREEN_ID = C.SCREEN_ID
                     WHERE C.SYSTEM_ID = S.SYSTEM_ID AND A.STATUS = 'DONE') AS DONE_CNT,
                   R.RUN_ID, R.SEQ, R.COLLECTED_AT
              FROM AS_SYSTEM S
              LEFT JOIN AS_COLLECT_RUN R ON R.SYSTEM_ID = S.SYSTEM_ID
                    AND R.SEQ = (SELECT MAX(R2.SEQ) FROM AS_COLLECT_RUN R2 WHERE R2.SYSTEM_ID = S.SYSTEM_ID)
            """;

    private static final RowMapper<SystemSummary> MAPPER = (rs, i) -> new SystemSummary(
            rs.getString("SYSTEM_ID"), rs.getString("PROJECT_ID"), rs.getString("CODE"), rs.getString("NAME"),
            split(rs.getString("UI_TECH")), split(rs.getString("SERVER_TECH")), rs.getString("DB_TYPE"),
            rs.getString("TEST_URL"), rs.getInt("SCREEN_CNT"), rs.getInt("DONE_CNT"),
            rs.getString("RUN_ID") == null ? null
                    : new LastRun(rs.getString("RUN_ID"), rs.getInt("SEQ"), Times.read(rs, "COLLECTED_AT")));

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;

    public SystemController(JdbcTemplate jdbc, ProjectAccess access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping("/api/v1/projects/{projectId}/systems")
    public List<SystemSummary> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query(SELECT + " WHERE S.PROJECT_ID = ? ORDER BY S.CODE", MAPPER, projectId);
    }

    @GetMapping("/api/v1/systems/{systemId}")
    public SystemSummary get(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId) {
        access.forSystem(user, systemId);
        return find(systemId);
    }

    @PostMapping("/api/v1/projects/{projectId}/systems")
    @ResponseStatus(HttpStatus.CREATED)
    public SystemSummary create(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @Valid @RequestBody SystemCreate req) {
        access.forProject(user, projectId).require(ProjectAccess.SYSTEM_ADMIN);
        Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM AS_SYSTEM WHERE PROJECT_ID = ? AND CODE = ?",
                Integer.class, projectId, req.code());
        if (dup != null && dup > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 등록된 시스템 코드입니다: " + req.code());
        }
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO AS_SYSTEM (SYSTEM_ID, PROJECT_ID, CODE, NAME, UI_TECH, SERVER_TECH, DB_TYPE, TEST_URL,
                                       CREATED_AT, CREATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, projectId, req.code(), req.name(), joinOrNull(req.uiTech()),
                joinOrNull(req.serverTech()), blankToNull(req.dbType()), req.testUrl(), Times.now(), user.userId());
        return find(id);
    }

    private static String joinOrNull(List<String> values) {
        if (values == null) {
            return null;
        }
        String joined = String.join(",", values.stream().filter(v -> v != null && !v.isBlank()).toList());
        return joined.isEmpty() ? null : joined;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || "auto".equalsIgnoreCase(value) ? null : value;
    }

    SystemSummary find(String systemId) {
        return jdbc.query(SELECT + " WHERE S.SYSTEM_ID = ?", MAPPER, systemId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("시스템"));
    }

    static List<String> split(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
