package kr.pmsx.sm;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.cm.notify.NotificationService;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.pj.ProjectSeq;
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

/**
 * 변경·배포 관리 (FR-SM-008, docs/13 §4). CAB: 반대 1건이면 REJECTED, PM 승인 1건 또는 승인
 * pmsx.sm.cab-approvals-required(기본 2)건 이상이면 CAB_APPROVED.
 * STANDARD·EMERGENCY 는 CAB 없이 배포(DEPLOYED) 가능, 배포 후 CLOSED / FAILED / ROLLED_BACK.
 */
@RestController
@RequestMapping("/api/v1")
public class ChangeController {

    public record Cab(String userId, String name, String decision, String comment, OffsetDateTime at) {
    }

    public record Change(String changeId, String changeNo, String title, String type, String risk, String plan,
                         String rollbackPlan, OffsetDateTime scheduledAt, OffsetDateTime deployedAt, String status,
                         List<String> srIds, List<IncidentController.SrRef> srs, List<String> screenIds,
                         List<Cab> cab, List<String> commits, OffsetDateTime updatedAt) {
    }

    public record ChangeRequest(String title, String type, String risk, String plan, String rollbackPlan,
                                OffsetDateTime scheduledAt, List<String> srIds, List<String> screenIds,
                                List<String> commits) {
    }

    public record CabRequest(String decision, String comment) {
    }

    public record TransitionRequest(String to, String comment) {
    }

    public record CalendarItem(String changeId, String changeNo, String title, String type, String status,
                               String kind, OffsetDateTime at) {
    }

    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "REQUESTED", Set.of("DEPLOYED", "REJECTED"),          // DEPLOYED 는 STANDARD·EMERGENCY 만
            "CAB_APPROVED", Set.of("DEPLOYED", "REJECTED"),
            "DEPLOYED", Set.of("CLOSED", "FAILED", "ROLLED_BACK"),
            "FAILED", Set.of("ROLLED_BACK", "CLOSED"),
            "ROLLED_BACK", Set.of("CLOSED"),
            "CLOSED", Set.of(),
            "REJECTED", Set.of());

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ProjectSeq seq;
    private final IncidentController ids;
    private final SrService srs;
    private final NotificationService notify;
    private final AuditService audit;
    private final kr.pmsx.common.PmsxProperties props;

    public ChangeController(JdbcTemplate jdbc, ProjectAccess access, ProjectSeq seq, IncidentController ids,
            SrService srs, NotificationService notify, AuditService audit, kr.pmsx.common.PmsxProperties props) {
        this.props = props;
        this.jdbc = jdbc;
        this.access = access;
        this.seq = seq;
        this.ids = ids;
        this.srs = srs;
        this.notify = notify;
        this.audit = audit;
    }

    private static final String COLS = """
            SELECT CHANGE_ID, CHANGE_NO, TITLE, TYPE, RISK, PLAN_TXT, ROLLBACK_PLAN, SCHEDULED_AT, DEPLOYED_AT, STATUS,
                   SR_IDS, SCREEN_IDS, COMMITS, UPDATED_AT, PROJECT_ID FROM SM_CHANGE""";

    private Change map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String id = rs.getString("CHANGE_ID");
        return new Change(id, rs.getString("CHANGE_NO"), rs.getString("TITLE"), rs.getString("TYPE"),
                rs.getString("RISK"), rs.getString("PLAN_TXT"), rs.getString("ROLLBACK_PLAN"),
                Times.read(rs, "SCHEDULED_AT"), Times.read(rs, "DEPLOYED_AT"), rs.getString("STATUS"),
                PjSupport.csv(rs.getString("SR_IDS")), ids.srRefs(PjSupport.csv(rs.getString("SR_IDS"))),
                PjSupport.csv(rs.getString("SCREEN_IDS")), cab(id),
                lines(rs.getString("COMMITS")), Times.read(rs, "UPDATED_AT"));
    }

    private static List<String> lines(String v) {
        if (v == null || v.isBlank()) {
            return List.of();
        }
        return List.of(v.split("\n")).stream().map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private List<Cab> cab(String changeId) {
        return jdbc.query("""
                SELECT C.USER_ID, U.NAME, C.DECISION, C.COMMENT_TXT, C.DECIDED_AT FROM SM_CHANGE_CAB C
                  LEFT JOIN CM_USER U ON U.USER_ID = C.USER_ID WHERE C.CHANGE_ID = ? ORDER BY C.DECIDED_AT""",
                (rs, i) -> new Cab(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        Times.read(rs, "DECIDED_AT")), changeId);
    }

    @GetMapping("/projects/{projectId}/changes")
    public List<Change> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String status) {
        access.forProject(user, projectId);
        List<Object> args = new ArrayList<>(List.of(projectId));
        String where = " WHERE PROJECT_ID = ?";
        if (status != null && !status.isBlank()) {
            List<String> st = PjSupport.csv(status);
            where += " AND STATUS IN (" + String.join(",", st.stream().map(x -> "?").toList()) + ")";
            args.addAll(st);
        }
        return jdbc.query(COLS + where + " ORDER BY COALESCE(DEPLOYED_AT, SCHEDULED_AT, CREATED_AT) DESC",
                (rs, i) -> map(rs), args.toArray());
    }

    /** 권한 확인 없이 프로젝트 전체 (엑셀 데이터셋용 — 호출자가 확인) */
    public List<Change> all(String projectId) {
        return jdbc.query(COLS + " WHERE PROJECT_ID = ? ORDER BY COALESCE(DEPLOYED_AT, SCHEDULED_AT, CREATED_AT) DESC",
                (rs, i) -> map(rs), projectId);
    }

    public Change change(String id) {
        return jdbc.query(COLS + " WHERE CHANGE_ID = ?", (rs, i) -> map(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("변경"));
    }

    private String projectOf(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_CHANGE WHERE CHANGE_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("변경"));
    }

    @PostMapping("/projects/{projectId}/changes")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Change create(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody ChangeRequest req) {
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        return insert(projectId, req, user);
    }

    public Change insert(String projectId, ChangeRequest req, AuthUser user) {
        Object[] v = validate(projectId, req, null);
        String id = Ulid.next();
        String no = seq.next(projectId, "CHG");
        OffsetDateTime now = Times.now();
        jdbc.update("""
                INSERT INTO SM_CHANGE (CHANGE_ID, PROJECT_ID, CHANGE_NO, TITLE, TYPE, RISK, PLAN_TXT, ROLLBACK_PLAN,
                                       SCHEDULED_AT, STATUS, SR_IDS, SCREEN_IDS, COMMITS, CREATED_AT, CREATED_BY,
                                       UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'REQUESTED', ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no, v[0], v[1],
                v[2], req.plan(), req.rollbackPlan(), req.scheduledAt(), v[3], v[4], v[5], now, user.userId(), now,
                user.userId());
        notify.notifyAll(srs.managers(projectId).stream().filter(u -> !u.equals(user.userId())).toList(),
                Event.REVIEW_REQUESTED, "변경 CAB 검토 요청: " + no + " " + v[0], null, "/sm/changes/" + id);
        audit.log(Action.CREATE, "change:" + id, "변경 등록 " + no, projectId);
        return change(id);
    }

    /** [title, type, risk, srIds, screenIds, commits] */
    private Object[] validate(String projectId, ChangeRequest req, Change cur) {
        String title = PjSupport.required("title", req.title(), 300);
        String type = PjSupport.oneOf("type", req.type(), cur == null ? "NORMAL" : cur.type(), "NORMAL", "STANDARD",
                "EMERGENCY");
        String risk = PjSupport.oneOf("risk", req.risk(), cur == null ? "M" : cur.risk(), "H", "M", "L");
        String srIds = ids.ids("srIds", projectId, req.srIds(),
                "SELECT COUNT(*) FROM SM_SR WHERE PROJECT_ID = ? AND SR_ID = ?");
        String screenIds = ids.ids("screenIds", projectId, req.screenIds(), """
                SELECT COUNT(*) FROM AS_SCREEN S JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID
                 WHERE Y.PROJECT_ID = ? AND S.SCREEN_ID = ?""");
        String commits = req.commits() == null || req.commits().isEmpty() ? null
                : String.join("\n", req.commits().stream().map(String::trim).filter(x -> !x.isEmpty()).toList());
        if (commits != null && commits.length() > 10_000) {
            throw PjSupport.validation("commits", "커밋 목록이 너무 깁니다.");
        }
        return new Object[] {title, type, risk, srIds, screenIds, commits};
    }

    @GetMapping("/changes/{changeId}")
    public Change get(@AuthenticationPrincipal AuthUser user, @PathVariable String changeId) {
        access.forProject(user, projectOf(changeId));
        return change(changeId);
    }

    @PutMapping("/changes/{changeId}")
    @Transactional
    public Change update(@AuthenticationPrincipal AuthUser user, @PathVariable String changeId,
            @RequestBody ChangeRequest req) {
        String projectId = projectOf(changeId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        Change cur = change(changeId);
        Object[] v = validate(projectId, req, cur);
        jdbc.update("""
                UPDATE SM_CHANGE SET TITLE = ?, TYPE = ?, RISK = ?, PLAN_TXT = ?, ROLLBACK_PLAN = ?, SCHEDULED_AT = ?,
                       SR_IDS = ?, SCREEN_IDS = ?, COMMITS = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE CHANGE_ID = ?""",
                v[0], v[1], v[2], req.plan(), req.rollbackPlan(), req.scheduledAt(), v[3], v[4], v[5], Times.now(),
                user.userId(), changeId);
        audit.log(Action.UPDATE, "change:" + changeId, "변경 수정 " + cur.changeNo(), projectId);
        return change(changeId);
    }

    @DeleteMapping("/changes/{changeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String changeId) {
        String projectId = projectOf(changeId);
        access.forProject(user, projectId).require(Set.of("PM"));
        jdbc.update("DELETE FROM SM_CHANGE_CAB WHERE CHANGE_ID = ?", changeId);
        jdbc.update("DELETE FROM SM_CHANGE WHERE CHANGE_ID = ?", changeId);
        audit.log(Action.DELETE, "change:" + changeId, "변경 삭제", projectId);
    }

    @PostMapping("/changes/{changeId}/cab")
    @Transactional
    public Change cab(@AuthenticationPrincipal AuthUser user, @PathVariable String changeId,
            @RequestBody CabRequest req) {
        String projectId = projectOf(changeId);
        var a = access.forProject(user, projectId);
        a.require(SmPerm.CAB);
        Change cur = change(changeId);
        if (!"REQUESTED".equals(cur.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "CAB 검토는 요청 상태에서만 할 수 있습니다.");
        }
        String decision = PjSupport.oneOf("decision", req.decision(), null, "APPROVE", "REJECT");
        if ("REJECT".equals(decision) && (req.comment() == null || req.comment().isBlank())) {
            throw PjSupport.validation("comment", "반대 사유를 입력해주세요.");
        }
        jdbc.update("DELETE FROM SM_CHANGE_CAB WHERE CHANGE_ID = ? AND USER_ID = ?", changeId, user.userId());
        jdbc.update("INSERT INTO SM_CHANGE_CAB (CHANGE_ID, USER_ID, DECISION, COMMENT_TXT, DECIDED_AT)"
                + " VALUES (?, ?, ?, ?, ?)", changeId, user.userId(), decision, SrService.cut(req.comment(), 1000),
                Times.now());
        String to = null;
        Integer approvals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM SM_CHANGE_CAB WHERE CHANGE_ID = ? AND DECISION = 'APPROVE'", Integer.class,
                changeId);
        if ("REJECT".equals(decision)) {
            to = "REJECTED";
        } else if ("PM".equals(a.projectRole()) || (approvals != null && approvals >= props.cabApprovalsRequired())) {
            to = "CAB_APPROVED";
        }
        if (to != null) {
            jdbc.update("UPDATE SM_CHANGE SET STATUS = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE CHANGE_ID = ?", to,
                    Times.now(), user.userId(), changeId);
            new HistWriter(jdbc).write("CHANGE", changeId, cur.status(), to, req.comment(), user.userId());
        }
        audit.log(Action.UPDATE, "change:" + changeId, "CAB " + decision + " " + cur.changeNo(), projectId);
        return change(changeId);
    }

    @PostMapping("/changes/{changeId}/transition")
    @Transactional
    public Change transition(@AuthenticationPrincipal AuthUser user, @PathVariable String changeId,
            @RequestBody TransitionRequest req) {
        String projectId = projectOf(changeId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        Change cur = change(changeId);
        Set<String> allowed = TRANSITIONS.getOrDefault(cur.status(), Set.of());
        boolean noCab = "STANDARD".equals(cur.type()) || "EMERGENCY".equals(cur.type());
        if (req.to() == null || !allowed.contains(req.to())
                || ("REQUESTED".equals(cur.status()) && "DEPLOYED".equals(req.to()) && !noCab)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    cur.status() + " → " + req.to() + " 전이는 허용되지 않습니다.");
        }
        OffsetDateTime deployed = "DEPLOYED".equals(req.to()) ? Times.now() : cur.deployedAt();
        jdbc.update("UPDATE SM_CHANGE SET STATUS = ?, DEPLOYED_AT = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE CHANGE_ID = ?",
                req.to(), deployed, Times.now(), user.userId(), changeId);
        new HistWriter(jdbc).write("CHANGE", changeId, cur.status(), req.to(), req.comment(), user.userId());
        audit.log(Action.UPDATE, "change:" + changeId, "변경 " + cur.status() + "→" + req.to(), projectId);
        return change(changeId);
    }

    /** 배포 예정(SCHEDULED)·배포 이력(DEPLOYED) — 기간은 YYYY-MM-DD */
    @GetMapping("/projects/{projectId}/changes/calendar")
    public List<CalendarItem> calendar(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        access.forProject(user, projectId);
        LocalDate f = from == null || from.isBlank() ? LocalDate.now(Times.ZONE).withDayOfMonth(1)
                : kr.pmsx.common.DateParams.localDate("from", from);
        LocalDate t = to == null || to.isBlank() ? f.plusMonths(1).minusDays(1) : kr.pmsx.common.DateParams.localDate("to", to);
        OffsetDateTime a = f.atStartOfDay(Times.ZONE).toOffsetDateTime();
        OffsetDateTime b = t.plusDays(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
        List<CalendarItem> out = new ArrayList<>();
        jdbc.query("""
                SELECT CHANGE_ID, CHANGE_NO, TITLE, TYPE, STATUS, SCHEDULED_AT, DEPLOYED_AT FROM SM_CHANGE
                 WHERE PROJECT_ID = ? AND ((DEPLOYED_AT >= ? AND DEPLOYED_AT < ?) OR (DEPLOYED_AT IS NULL AND SCHEDULED_AT >= ?
                   AND SCHEDULED_AT < ?)) ORDER BY COALESCE(DEPLOYED_AT, SCHEDULED_AT)""", rs -> {
            OffsetDateTime dep = Times.read(rs, "DEPLOYED_AT");
            out.add(new CalendarItem(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), dep != null ? "DEPLOYED" : "SCHEDULED",
                    dep != null ? dep : Times.read(rs, "SCHEDULED_AT")));
        }, projectId, a, b, a, b);
        return out;
    }

    /** CM_STATUS_HIST 기록 (현재 사용자 대신 userId 지정) */
    record HistWriter(JdbcTemplate jdbc) {
        void write(String type, String id, String from, String to, String comment, String userId) {
            jdbc.update("""
                    INSERT INTO CM_STATUS_HIST (HIST_ID, TARGET_TYPE, TARGET_ID, ACTION, FROM_STATUS, TO_STATUS,
                                                COMMENT_TXT, HIST_AT, USER_ID)
                    VALUES (?, ?, ?, 'STATUS', ?, ?, ?, ?, ?)""", Ulid.next(), type, id, from, to, comment,
                    Times.now(), userId);
        }
    }
}
