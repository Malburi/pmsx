package kr.pmsx.rs;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import kr.pmsx.cm.notify.NotificationService;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.DateParams;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 작업일지 (FR-RS-006·007, docs/12 §4). 사용자·주 단위 1건, 항목마다 대상(WBS·화면·SR·기타)과 프로젝트.
 * 승인은 항목이 속한 프로젝트의 PM·PL. 승인된 시간 / pmsx.mm.hours-per-month = 실적 MM.
 */
@RestController
@RequestMapping("/api/v1")
public class TimesheetController {

    /** projectId 는 ETC 항목에서 프로젝트를 지정할 때만 사용(나머지는 대상에서 결정) */
    public record Entry(LocalDate date, String targetType, String targetId, String targetLabel, double hours,
                        String note, String projectId) {
    }

    public record Timesheet(String timesheetId, String userId, String userName, LocalDate weekStart, String status,
                            String comment, List<Entry> entries) {
    }

    public record DecisionRequest(String comment) {
    }

    public record MmSummaryRow(String userId, String name, String company, String orgType, String grade,
                               Map<String, Double> plan, Map<String, Double> actual) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final WorkTargets targets;
    private final NotificationService notify;
    private final AuditService audit;
    private final PmsxProperties props;
    private final PjSupport support;

    public TimesheetController(JdbcTemplate jdbc, ProjectAccess access, WorkTargets targets,
            NotificationService notify, AuditService audit, PmsxProperties props, PjSupport support) {
        this.jdbc = jdbc;
        this.access = access;
        this.targets = targets;
        this.notify = notify;
        this.audit = audit;
        this.props = props;
        this.support = support;
    }

    @GetMapping("/timesheets")
    public Timesheet get(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String userId,
            @RequestParam String weekStart) {
        String uid = userId == null || userId.isBlank() ? user.userId() : userId;
        if (!uid.equals(user.userId()) && !canApproveUser(user, uid)) {
            throw ApiException.forbidden();
        }
        LocalDate ws = monday(DateParams.localDate("weekStart", weekStart));
        String id = jdbc.query("SELECT TIMESHEET_ID FROM RS_TIMESHEET WHERE USER_ID = ? AND WEEK_START = ?",
                (rs, i) -> rs.getString(1), uid, ws).stream().findFirst().orElse(null);
        return id == null ? new Timesheet(null, uid, support.user(uid).name(), ws, "DRAFT", null, List.of()) : load(id);
    }

    /** 대상 사용자가 멤버인 프로젝트 중 하나에서라도 PM·PL 이면 조회 가능 */
    private boolean canApproveUser(AuthUser user, String targetUserId) {
        if (user.isSysAdmin()) {
            return true;
        }
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM PJ_MEMBER A JOIN PJ_MEMBER B ON B.PROJECT_ID = A.PROJECT_ID
                 WHERE A.USER_ID = ? AND A.ROLE_ID IN ('PM', 'PL') AND B.USER_ID = ?""", Integer.class, user.userId(),
                targetUserId);
        return n != null && n > 0;
    }

    @PutMapping("/timesheets")
    @Transactional
    public Timesheet save(@AuthenticationPrincipal AuthUser user, @RequestBody Timesheet req) {
        if (req.userId() != null && !req.userId().equals(user.userId())) {
            throw ApiException.forbidden();
        }
        if (req.weekStart() == null) {
            throw PjSupport.validation("weekStart", "필수 입력입니다.");
        }
        LocalDate ws = monday(req.weekStart());
        var cur = jdbc.query("SELECT TIMESHEET_ID, STATUS FROM RS_TIMESHEET WHERE USER_ID = ? AND WEEK_START = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, user.userId(), ws).stream().findFirst()
                .orElse(null);
        if (cur != null && !"DRAFT".equals(cur[1]) && !"REJECTED".equals(cur[1])) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "제출·승인된 작업일지는 수정할 수 없습니다.");
        }
        List<Entry> entries = req.entries() == null ? List.of() : req.entries();
        Map<LocalDate, Double> daily = new HashMap<>();
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String f = "entries[" + i + "]";
            if (e.date() == null || e.date().isBefore(ws) || e.date().isAfter(ws.plusDays(6))) {
                throw PjSupport.validation(f + ".date", "해당 주의 날짜여야 합니다.");
            }
            if (e.hours() <= 0 || e.hours() > 24) {
                throw PjSupport.validation(f + ".hours", "0 초과 24 이하여야 합니다.");
            }
            if (daily.merge(e.date(), e.hours(), Double::sum) > 24) {
                throw PjSupport.validation(f + ".hours", e.date() + " 하루 합계가 24시간을 넘습니다.");
            }
            String type = PjSupport.oneOf(f + ".targetType", e.targetType(), null, "WBS", "SCREEN", "SR", "ETC");
            String label;
            String projectId;
            String targetId = null;
            if ("ETC".equals(type)) {
                label = PjSupport.required(f + ".targetLabel", e.targetLabel(), 300);
                projectId = e.projectId() == null || e.projectId().isBlank() ? null : e.projectId();
            } else {
                WorkTargets.Target t = targets.resolve(f, type, e.targetId());
                label = t.label().length() > 300 ? t.label().substring(0, 300) : t.label();
                projectId = t.projectId();
                targetId = t.id();
            }
            if (projectId != null) {
                Integer m = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                        Integer.class, projectId, user.userId());
                if (m == null || m == 0) {
                    throw PjSupport.validation(f + ".targetId", "본인이 참여한 프로젝트의 작업만 입력할 수 있습니다.");
                }
            }
            rows.add(new Object[] {e.date(), type, targetId, label, projectId, BigDecimal.valueOf(e.hours()),
                    PjSupport.optional(f + ".note", e.note(), 1000), i});
        }
        String id = cur == null ? Ulid.next() : cur[0];
        if (cur == null) {
            jdbc.update("INSERT INTO RS_TIMESHEET (TIMESHEET_ID, USER_ID, WEEK_START, STATUS, UPDATED_AT)"
                    + " VALUES (?, ?, ?, 'DRAFT', ?)", id, user.userId(), ws, Times.now());
        } else {
            jdbc.update("UPDATE RS_TIMESHEET SET UPDATED_AT = ? WHERE TIMESHEET_ID = ?", Times.now(), id);
        }
        jdbc.update("DELETE FROM RS_TIMESHEET_ENTRY WHERE TIMESHEET_ID = ?", id);
        for (Object[] r : rows) {
            jdbc.update("""
                    INSERT INTO RS_TIMESHEET_ENTRY (ENTRY_ID, TIMESHEET_ID, WORK_DT, TARGET_TYPE, TARGET_ID, TARGET_LABEL,
                                                    PROJECT_ID, HOURS, NOTE, ORD)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", Ulid.next(), id, r[0], r[1], r[2], r[3], r[4], r[5], r[6],
                    r[7]);
        }
        return load(id);
    }

    @PostMapping("/timesheets/{timesheetId}/submit")
    @Transactional
    public Timesheet submit(@AuthenticationPrincipal AuthUser user, @PathVariable String timesheetId,
            @RequestBody(required = false) DecisionRequest req) {
        Timesheet t = load(timesheetId);
        if (!t.userId().equals(user.userId())) {
            throw ApiException.forbidden();
        }
        if (!"DRAFT".equals(t.status()) && !"REJECTED".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 제출된 작업일지입니다.");
        }
        if (t.entries().isEmpty()) {
            throw PjSupport.validation("entries", "작업 내역을 1건 이상 입력해주세요.");
        }
        jdbc.update("UPDATE RS_TIMESHEET SET STATUS = 'SUBMITTED', SUBMITTED_AT = ?, COMMENT_TXT = ?, UPDATED_AT = ?"
                        + " WHERE TIMESHEET_ID = ?", Times.now(), req == null ? null : req.comment(), Times.now(),
                timesheetId);
        Set<String> approvers = new LinkedHashSet<>();
        for (String pid : projects(timesheetId)) {
            approvers.addAll(jdbc.queryForList("SELECT USER_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND ROLE_ID IN ('PM', 'PL')",
                    String.class, pid));
        }
        approvers.remove(user.userId());
        notify.notifyAll(approvers, Event.REVIEW_REQUESTED, t.userName() + " 작업일지 승인 요청 (" + t.weekStart() + " 주)",
                null, "/resources/timesheets/approvals");
        return load(timesheetId);
    }

    @PostMapping("/timesheets/{timesheetId}/approve")
    @Transactional
    public Timesheet approve(@AuthenticationPrincipal AuthUser user, @PathVariable String timesheetId,
            @RequestBody(required = false) DecisionRequest req) {
        Timesheet t = decide(user, timesheetId, "APPROVED", req);
        // WBS 실적 시작일 기록·상태 진행 (FR-RS-006)
        Map<String, LocalDate> firstDay = new HashMap<>();
        for (Entry e : t.entries()) {
            if ("WBS".equals(e.targetType()) && e.targetId() != null) {
                firstDay.merge(e.targetId(), e.date(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        firstDay.forEach((wbsId, d) -> jdbc.update("""
                UPDATE PJ_WBS SET ACT_START = COALESCE(ACT_START, ?),
                       STATUS = CASE WHEN STATUS = 'TODO' THEN 'IN_PROGRESS' ELSE STATUS END
                 WHERE WBS_ID = ? AND NOT EXISTS (SELECT 1 FROM PJ_WBS C WHERE C.PARENT_ID = PJ_WBS.WBS_ID)""", d,
                wbsId));
        notify.notify(t.userId(), Event.APPROVED, "작업일지가 승인되었습니다 (" + t.weekStart() + " 주)", null,
                "/resources/timesheets?week=" + t.weekStart());
        return t;
    }

    @PostMapping("/timesheets/{timesheetId}/reject")
    @Transactional
    public Timesheet reject(@AuthenticationPrincipal AuthUser user, @PathVariable String timesheetId,
            @RequestBody(required = false) DecisionRequest req) {
        if (req == null || req.comment() == null || req.comment().isBlank()) {
            throw PjSupport.validation("comment", "반려 사유를 입력해주세요.");
        }
        Timesheet t = decide(user, timesheetId, "REJECTED", req);
        notify.notify(t.userId(), Event.TIMESHEET_REJECTED, "작업일지가 반려되었습니다 (" + t.weekStart() + " 주)",
                req.comment(), "/resources/timesheets?week=" + t.weekStart());
        return t;
    }

    private Timesheet decide(AuthUser user, String timesheetId, String to, DecisionRequest req) {
        Timesheet t = load(timesheetId);
        if (!"SUBMITTED".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "제출된 작업일지만 승인·반려할 수 있습니다.");
        }
        boolean ok = false;
        for (String pid : projects(timesheetId)) {
            try {
                ok |= access.forProject(user, pid).has(Perm.TIMESHEET_APPROVE);
            } catch (ApiException ignore) {
                // 비멤버 프로젝트
            }
        }
        if (!ok || t.userId().equals(user.userId())) {
            throw ApiException.forbidden();
        }
        jdbc.update("UPDATE RS_TIMESHEET SET STATUS = ?, COMMENT_TXT = ?, DECIDED_AT = ?, DECIDED_BY = ?, UPDATED_AT = ?"
                        + " WHERE TIMESHEET_ID = ?", to, req == null ? t.comment() : req.comment(), Times.now(),
                user.userId(), Times.now(), timesheetId);
        audit.log(Action.UPDATE, "timesheet:" + timesheetId, "작업일지 " + to, projects(timesheetId).stream()
                .findFirst().orElse(null));
        return load(timesheetId);
    }

    @GetMapping("/projects/{projectId}/timesheets/pending")
    public List<Timesheet> pending(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId).require(Perm.TIMESHEET_APPROVE);
        return jdbc.queryForList("""
                SELECT DISTINCT T.TIMESHEET_ID FROM RS_TIMESHEET T JOIN RS_TIMESHEET_ENTRY E ON E.TIMESHEET_ID = T.TIMESHEET_ID
                 WHERE T.STATUS = 'SUBMITTED' AND E.PROJECT_ID = ?""", String.class, projectId).stream()
                .map(this::load).sorted(java.util.Comparator.comparing(Timesheet::weekStart)
                        .thenComparing(Timesheet::userName)).toList();
    }

    private List<String> projects(String timesheetId) {
        return jdbc.queryForList("SELECT DISTINCT PROJECT_ID FROM RS_TIMESHEET_ENTRY WHERE TIMESHEET_ID = ?"
                + " AND PROJECT_ID IS NOT NULL", String.class, timesheetId);
    }

    Timesheet load(String id) {
        var h = jdbc.query("""
                SELECT T.TIMESHEET_ID, T.USER_ID, U.NAME, T.WEEK_START, T.STATUS, T.COMMENT_TXT FROM RS_TIMESHEET T
                  JOIN CM_USER U ON U.USER_ID = T.USER_ID WHERE T.TIMESHEET_ID = ?""",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getString(5), rs.getString(6)}, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("작업일지"));
        List<Entry> entries = jdbc.query("""
                SELECT WORK_DT, TARGET_TYPE, TARGET_ID, TARGET_LABEL, HOURS, NOTE, PROJECT_ID FROM RS_TIMESHEET_ENTRY
                 WHERE TIMESHEET_ID = ? ORDER BY ORD""", (rs, i) -> new Entry(rs.getObject(1, LocalDate.class),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5).doubleValue(), rs.getString(6),
                rs.getString(7)), id);
        return new Timesheet((String) h[0], (String) h[1], (String) h[2], (LocalDate) h[3], (String) h[4],
                (String) h[5], entries);
    }

    // ───────────────────────── MM 실적 ─────────────────────────

    @GetMapping("/projects/{projectId}/mm-summary")
    public List<MmSummaryRow> mmSummary(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        access.forProject(user, projectId).require(Perm.MM_READ);
        return summary(projectId, ResourceController.ym("from", from), ResourceController.ym("to", to));
    }

    public List<MmSummaryRow> summary(String projectId, YearMonth from, YearMonth to) {
        Map<String, MmSummaryRow> rows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT U.USER_ID, U.NAME, U.COMPANY, U.ORG_TYPE, U.GRADE FROM PJ_MEMBER M JOIN CM_USER U ON U.USER_ID = M.USER_ID
                 WHERE M.PROJECT_ID = ? ORDER BY U.ORG_TYPE, U.NAME""", rs -> {
            rows.put(rs.getString(1), new MmSummaryRow(rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4), rs.getString(5), new TreeMap<>(), new TreeMap<>()));
        }, projectId);
        jdbc.query("SELECT USER_ID, YM, PLAN_MM FROM RS_MM_PLAN WHERE PROJECT_ID = ?", rs -> {
            String ym = rs.getString(2);
            MmSummaryRow r = rows.get(rs.getString(1));
            if (r != null && in(ym, from, to)) {
                r.plan().put(ym, rs.getBigDecimal(3).doubleValue());
            }
        }, projectId);
        double hpm = props.hoursPerMonth();
        jdbc.query("""
                SELECT T.USER_ID, E.WORK_DT, E.HOURS FROM RS_TIMESHEET_ENTRY E JOIN RS_TIMESHEET T ON T.TIMESHEET_ID = E.TIMESHEET_ID
                 WHERE E.PROJECT_ID = ? AND T.STATUS = 'APPROVED'""", rs -> {
            String ym = YearMonth.from(rs.getObject(2, LocalDate.class)).toString();
            MmSummaryRow r = rows.get(rs.getString(1));
            if (r != null && in(ym, from, to)) {
                r.actual().merge(ym, rs.getBigDecimal(3).doubleValue() / hpm, Double::sum);
            }
        }, projectId);
        for (MmSummaryRow r : rows.values()) {
            r.actual().replaceAll((k, v) -> Math.round(v * 100) / 100.0);
        }
        return new ArrayList<>(rows.values());
    }

    /** 기간 내 승인 실적 MM 합계 (대시보드·주간보고) */
    public double actualMm(String projectId, LocalDate from, LocalDate to) {
        BigDecimal h = jdbc.queryForObject("""
                SELECT COALESCE(SUM(E.HOURS), 0) FROM RS_TIMESHEET_ENTRY E JOIN RS_TIMESHEET T ON T.TIMESHEET_ID = E.TIMESHEET_ID
                 WHERE E.PROJECT_ID = ? AND T.STATUS = 'APPROVED' AND E.WORK_DT >= ? AND E.WORK_DT <= ?""",
                BigDecimal.class, projectId, from, to);
        return h == null ? 0 : Math.round(h.doubleValue() / props.hoursPerMonth() * 100) / 100.0;
    }

    private static boolean in(String ym, YearMonth from, YearMonth to) {
        return (from == null || ym.compareTo(from.toString()) >= 0) && (to == null || ym.compareTo(to.toString()) <= 0);
    }

    static LocalDate monday(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }
}
