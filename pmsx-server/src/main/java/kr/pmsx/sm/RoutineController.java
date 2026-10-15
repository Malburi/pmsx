package kr.pmsx.sm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.DateParams;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.WorkCalendar;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 정기 작업·운영 자산 (FR-SM-009·010, docs/13 §5). */
@RestController
@RequestMapping("/api/v1")
public class RoutineController {

    public record Rule(String freq, List<Integer> dow, Integer day) {
    }

    public record RoutineJob(String jobId, String name, Rule rule, UserRef owner, List<String> checklist) {
    }

    public record RoutineJobRequest(String name, Rule rule, String ownerId, UserRef owner, List<String> checklist) {
        String ownerUserId() {
            return ownerId != null && !ownerId.isBlank() ? ownerId : owner == null ? null : owner.userId();
        }
    }

    public record Occurrence(String jobId, String name, LocalDate date, boolean done, String doneBy, String note) {
    }

    public record CheckRequest(LocalDate date, Boolean done, String note) {
    }

    public record OpsAsset(String assetId, String type, String name, String host, String owner, String ownerId,
                           LocalDate expiresAt, String note, Long daysLeft) {
    }

    public record OpsAssetRequest(String type, String name, String host, String owner, String ownerId,
                                  LocalDate expiresAt, String note) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final WorkCalendar calendar;
    private final ObjectMapper om;
    private final AuditService audit;

    public RoutineController(JdbcTemplate jdbc, ProjectAccess access, WorkCalendar calendar, ObjectMapper om,
            AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.calendar = calendar;
        this.om = om;
        this.audit = audit;
    }

    // ───────────────────────── 정기 작업 ─────────────────────────

    private RoutineJob mapJob(java.sql.ResultSet rs) throws java.sql.SQLException {
        String freq = rs.getString("FREQ");
        Integer day = (Integer) rs.getObject("DAY_OF_MONTH", Integer.class);
        List<Integer> dow = PjSupport.csv(rs.getString("DOW")).stream().map(Integer::parseInt).toList();
        String owner = rs.getString("OWNER_ID");
        return new RoutineJob(rs.getString("JOB_ID"), rs.getString("NAME"), new Rule(freq,
                "WEEKLY".equals(freq) ? dow : null, "MONTHLY".equals(freq) ? day : null),
                owner == null ? null : new UserRef(owner, rs.getString("OWNER_NM")), checklist(rs.getString("CHECKLIST_JSON")));
    }

    private List<String> checklist(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return om.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final String JOB_COLS = """
            SELECT J.JOB_ID, J.NAME, J.FREQ, J.DOW, J.DAY_OF_MONTH, J.OWNER_ID, U.NAME OWNER_NM, J.CHECKLIST_JSON, J.PROJECT_ID
              FROM SM_ROUTINE_JOB J LEFT JOIN CM_USER U ON U.USER_ID = J.OWNER_ID""";

    @GetMapping("/projects/{projectId}/routine-jobs")
    public List<RoutineJob> jobs(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query(JOB_COLS + " WHERE J.PROJECT_ID = ? ORDER BY J.NAME", (rs, i) -> mapJob(rs), projectId);
    }

    private RoutineJob job(String id) {
        return jdbc.query(JOB_COLS + " WHERE J.JOB_ID = ?", (rs, i) -> mapJob(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("정기 작업"));
    }

    private String jobProject(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_ROUTINE_JOB WHERE JOB_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("정기 작업"));
    }

    @PostMapping("/projects/{projectId}/routine-jobs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public RoutineJob createJob(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody RoutineJobRequest req) {
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        return insertJob(projectId, req);
    }

    public RoutineJob insertJob(String projectId, RoutineJobRequest req) {
        Object[] v = validateJob(projectId, req);
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO SM_ROUTINE_JOB (JOB_ID, PROJECT_ID, NAME, FREQ, DOW, DAY_OF_MONTH, OWNER_ID, CHECKLIST_JSON,
                                            CREATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, v[0],
                v[1], v[2], v[3], req.ownerUserId(), v[4], Times.now());
        audit.log(Action.CREATE, "routine-job:" + id, "정기 작업 등록 " + v[0], projectId);
        return job(id);
    }

    /** [name, freq, dow, day, checklistJson] */
    private Object[] validateJob(String projectId, RoutineJobRequest req) {
        String name = PjSupport.required("name", req.name(), 200);
        Rule r = req.rule() == null ? new Rule("DAILY", null, null) : req.rule();
        String freq = PjSupport.oneOf("rule.freq", r.freq(), "DAILY", "DAILY", "WEEKLY", "MONTHLY");
        String dow = null;
        Integer day = null;
        if ("WEEKLY".equals(freq)) {
            if (r.dow() == null || r.dow().isEmpty()) {
                throw PjSupport.validation("rule.dow", "요일을 하나 이상 선택해주세요.");
            }
            for (Integer d : r.dow()) {
                if (d == null || d < 1 || d > 7) {
                    throw PjSupport.validation("rule.dow", "요일은 1(월)~7(일)입니다.");
                }
            }
            dow = PjSupport.csv(r.dow().stream().sorted().map(String::valueOf).toList());
        } else if ("MONTHLY".equals(freq)) {
            if (r.day() == null || r.day() < 1 || r.day() > 31) {
                throw PjSupport.validation("rule.day", "일자는 1~31 입니다.");
            }
            day = r.day();
        }
        String owner = req.ownerUserId();
        if (owner != null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                    Integer.class, projectId, owner);
            if (n == null || n == 0) {
                throw PjSupport.validation("ownerId", "프로젝트 멤버만 지정할 수 있습니다.");
            }
        }
        String json;
        try {
            json = om.writeValueAsString(req.checklist() == null ? List.of() : req.checklist().stream()
                    .filter(x -> x != null && !x.isBlank()).map(String::trim).toList());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new Object[] {name, freq, dow, day, json};
    }

    @PutMapping("/routine-jobs/{jobId}")
    @Transactional
    public RoutineJob updateJob(@AuthenticationPrincipal AuthUser user, @PathVariable String jobId,
            @RequestBody RoutineJobRequest req) {
        String projectId = jobProject(jobId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        Object[] v = validateJob(projectId, req);
        jdbc.update("""
                UPDATE SM_ROUTINE_JOB SET NAME = ?, FREQ = ?, DOW = ?, DAY_OF_MONTH = ?, OWNER_ID = ?, CHECKLIST_JSON = ?,
                       UPDATED_AT = ? WHERE JOB_ID = ?""", v[0], v[1], v[2], v[3], req.ownerUserId(), v[4], Times.now(),
                jobId);
        audit.log(Action.UPDATE, "routine-job:" + jobId, "정기 작업 수정", projectId);
        return job(jobId);
    }

    @DeleteMapping("/routine-jobs/{jobId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteJob(@AuthenticationPrincipal AuthUser user, @PathVariable String jobId) {
        String projectId = jobProject(jobId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        jdbc.update("DELETE FROM SM_ROUTINE_CHECK WHERE JOB_ID = ?", jobId);
        jdbc.update("DELETE FROM SM_ROUTINE_JOB WHERE JOB_ID = ?", jobId);
        audit.log(Action.DELETE, "routine-job:" + jobId, "정기 작업 삭제", projectId);
    }

    /**
     * 발생일: DAILY = 영업일(주말·공휴일 제외), WEEKLY = 지정 요일, MONTHLY = 지정 일자(그 달에 없으면 말일).
     */
    public static List<LocalDate> occurrences(Rule rule, LocalDate from, LocalDate to, WorkCalendar.Days days) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            boolean hit = switch (rule.freq()) {
                case "DAILY" -> days.isWorkday(d);
                case "WEEKLY" -> rule.dow() != null && rule.dow().contains(d.getDayOfWeek().getValue());
                case "MONTHLY" -> rule.day() != null
                        && d.getDayOfMonth() == Math.min(rule.day(), YearMonth.from(d).lengthOfMonth());
                default -> false;
            };
            if (hit) {
                out.add(d);
            }
        }
        return out;
    }

    @GetMapping("/projects/{projectId}/routine-jobs/occurrences")
    public List<Occurrence> occurrences(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        access.forProject(user, projectId);
        LocalDate f = from == null || from.isBlank() ? LocalDate.now(Times.ZONE).withDayOfMonth(1)
                : DateParams.localDate("from", from);
        LocalDate t = to == null || to.isBlank() ? YearMonth.from(f).atEndOfMonth() : DateParams.localDate("to", to);
        if (t.isBefore(f) || ChronoUnit.DAYS.between(f, t) > 366) {
            throw PjSupport.validation("to", "기간은 1년 이내여야 합니다.");
        }
        WorkCalendar.Days days = calendar.load();
        Map<String, Object[]> checks = new HashMap<>();
        jdbc.query("""
                SELECT C.JOB_ID, C.CHECK_DT, C.DONE, U.NAME, C.NOTE FROM SM_ROUTINE_CHECK C
                  JOIN SM_ROUTINE_JOB J ON J.JOB_ID = C.JOB_ID LEFT JOIN CM_USER U ON U.USER_ID = C.DONE_BY
                 WHERE J.PROJECT_ID = ? AND C.CHECK_DT >= ? AND C.CHECK_DT <= ?""", rs -> {
            checks.put(rs.getString(1) + "|" + rs.getObject(2, LocalDate.class),
                    new Object[] {rs.getBoolean(3), rs.getString(4), rs.getString(5)});
        }, projectId, f, t);
        List<Occurrence> out = new ArrayList<>();
        for (RoutineJob j : jdbc.query(JOB_COLS + " WHERE J.PROJECT_ID = ? ORDER BY J.NAME", (rs, i) -> mapJob(rs),
                projectId)) {
            for (LocalDate d : occurrences(j.rule(), f, t, days)) {
                Object[] c = checks.get(j.jobId() + "|" + d);
                out.add(new Occurrence(j.jobId(), j.name(), d, c != null && (Boolean) c[0],
                        c == null ? null : (String) c[1], c == null ? null : (String) c[2]));
            }
        }
        out.sort(java.util.Comparator.comparing(Occurrence::date).thenComparing(Occurrence::name));
        return out;
    }

    @PostMapping("/routine-jobs/{jobId}/check")
    @Transactional
    public Occurrence check(@AuthenticationPrincipal AuthUser user, @PathVariable String jobId,
            @RequestBody CheckRequest req) {
        String projectId = jobProject(jobId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        if (req.date() == null) {
            throw PjSupport.validation("date", "날짜를 입력해주세요.");
        }
        RoutineJob j = job(jobId);
        if (!occurrences(j.rule(), req.date(), req.date(), calendar.load()).contains(req.date())) {
            throw PjSupport.validation("date", "이 작업의 수행일이 아닙니다.");
        }
        boolean done = !Boolean.FALSE.equals(req.done());
        jdbc.update("DELETE FROM SM_ROUTINE_CHECK WHERE JOB_ID = ? AND CHECK_DT = ?", jobId, req.date());
        jdbc.update("INSERT INTO SM_ROUTINE_CHECK (JOB_ID, CHECK_DT, DONE, DONE_BY, DONE_AT, NOTE) VALUES (?, ?, ?, ?, ?, ?)",
                jobId, req.date(), done, done ? user.userId() : null, done ? Times.now() : null,
                SrService.cut(req.note(), 1000));
        return new Occurrence(jobId, j.name(), req.date(), done, done ? user.name() : null, req.note());
    }

    // ───────────────────────── 운영 자산 ─────────────────────────

    private static final String ASSET_COLS = """
            SELECT ASSET_ID, TYPE, NAME, HOST, OWNER, OWNER_ID, EXPIRES_AT, NOTE FROM SM_OPS_ASSET""";

    static OpsAsset mapAsset(java.sql.ResultSet rs) throws java.sql.SQLException {
        LocalDate exp = rs.getObject("EXPIRES_AT", LocalDate.class);
        return new OpsAsset(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), exp, rs.getString(8),
                exp == null ? null : ChronoUnit.DAYS.between(LocalDate.now(Times.ZONE), exp));
    }

    @GetMapping("/projects/{projectId}/ops-assets")
    public List<OpsAsset> assets(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String type) {
        access.forProject(user, projectId);
        List<Object> args = new ArrayList<>(List.of(projectId));
        String where = " WHERE PROJECT_ID = ?";
        if (type != null && !type.isBlank()) {
            where += " AND TYPE = ?";
            args.add(type);
        }
        return jdbc.query(ASSET_COLS + where + " ORDER BY EXPIRES_AT NULLS LAST, NAME", (rs, i) -> mapAsset(rs),
                args.toArray());
    }

    private OpsAsset asset(String id) {
        return jdbc.query(ASSET_COLS + " WHERE ASSET_ID = ?", (rs, i) -> mapAsset(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("운영 자산"));
    }

    private String assetProject(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_OPS_ASSET WHERE ASSET_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("운영 자산"));
    }

    @PostMapping("/projects/{projectId}/ops-assets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OpsAsset createAsset(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody OpsAssetRequest req) {
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        return insertAsset(projectId, req);
    }

    public OpsAsset insertAsset(String projectId, OpsAssetRequest req) {
        validateAsset(projectId, req);
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO SM_OPS_ASSET (ASSET_ID, PROJECT_ID, TYPE, NAME, HOST, OWNER, OWNER_ID, EXPIRES_AT, NOTE,
                                          CREATED_AT) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId,
                req.type(), req.name().trim(), SrService.blank(req.host()), SrService.blank(req.owner()),
                SrService.blank(req.ownerId()), req.expiresAt(), req.note(), Times.now());
        audit.log(Action.CREATE, "ops-asset:" + id, "운영 자산 등록 " + req.name(), projectId);
        return asset(id);
    }

    private void validateAsset(String projectId, OpsAssetRequest req) {
        PjSupport.oneOf("type", req.type(), null, "SERVER", "ACCOUNT", "CERT", "LICENSE", "DOMAIN");
        PjSupport.required("name", req.name(), 200);
        PjSupport.optional("host", req.host(), 300);
        PjSupport.optional("owner", req.owner(), 100);
        PjSupport.optional("note", req.note(), 2000);
        if (req.ownerId() != null && !req.ownerId().isBlank()) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                    Integer.class, projectId, req.ownerId());
            if (n == null || n == 0) {
                throw PjSupport.validation("ownerId", "프로젝트 멤버만 지정할 수 있습니다.");
            }
        }
    }

    @PutMapping("/ops-assets/{assetId}")
    @Transactional
    public OpsAsset updateAsset(@AuthenticationPrincipal AuthUser user, @PathVariable String assetId,
            @RequestBody OpsAssetRequest req) {
        String projectId = assetProject(assetId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        validateAsset(projectId, req);
        OpsAsset cur = asset(assetId);
        boolean expiryChanged = !java.util.Objects.equals(cur.expiresAt(), req.expiresAt());
        jdbc.update("""
                UPDATE SM_OPS_ASSET SET TYPE = ?, NAME = ?, HOST = ?, OWNER = ?, OWNER_ID = ?, EXPIRES_AT = ?, NOTE = ?,
                       NOTIFIED_STAGE = CASE WHEN ? = TRUE THEN 0 ELSE NOTIFIED_STAGE END, UPDATED_AT = ? WHERE ASSET_ID = ?""",
                req.type(), req.name().trim(), SrService.blank(req.host()), SrService.blank(req.owner()),
                SrService.blank(req.ownerId()), req.expiresAt(), req.note(), expiryChanged, Times.now(), assetId);
        audit.log(Action.UPDATE, "ops-asset:" + assetId, "운영 자산 수정", projectId);
        return asset(assetId);
    }

    @DeleteMapping("/ops-assets/{assetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteAsset(@AuthenticationPrincipal AuthUser user, @PathVariable String assetId) {
        String projectId = assetProject(assetId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        jdbc.update("DELETE FROM SM_OPS_ASSET WHERE ASSET_ID = ?", assetId);
        audit.log(Action.DELETE, "ops-asset:" + assetId, "운영 자산 삭제", projectId);
    }
}
