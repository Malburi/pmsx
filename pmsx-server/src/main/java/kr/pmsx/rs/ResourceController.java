package kr.pmsx.rs;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import kr.pmsx.cm.notify.NotificationService;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.dao.DuplicateKeyException;
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

/** 인력 풀·MM 계획·히트맵·배정·투입 체크리스트·PARTNER 열람 범위 (FR-RS-001~005·009, docs/12 §4, §7). */
@RestController
@RequestMapping("/api/v1")
public class ResourceController {

    public record Skill(String skill, int level) {
    }

    public record ProjectRole(String projectId, String name, String roleId) {
    }

    public record Resource(String userId, String name, String orgType, String company, String grade,
                           List<Skill> skills, List<ProjectRole> currentProjects) {
    }

    public record ResourceRequest(String grade, String company, String phone, List<Skill> skills) {
    }

    public record MmPlanRow(String userId, String name, String grade, String roleId, Map<String, Double> months) {
    }

    public record HeatmapRow(String userId, String name, Map<String, Integer> cells) {
    }

    public record WorkItem(String type, String id, String label, String path, LocalDate planStart, LocalDate planEnd) {
    }

    public record AssignRequest(String targetType, String targetId, String userId, Integer allocPct) {
    }

    public record Assignment(String assignmentId, String targetType, String targetId, String userId, int allocPct,
                             String warning) {
    }

    public record CheckItem(String item, boolean done, java.time.OffsetDateTime doneAt) {
    }

    public record Scope(List<String> systemIds, List<String> menuIds) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final WorkTargets targets;
    private final NotificationService notify;
    private final AuditService audit;

    public ResourceController(JdbcTemplate jdbc, ProjectAccess access, WorkTargets targets,
            NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.targets = targets;
        this.notify = notify;
        this.audit = audit;
    }

    /** 프로젝트와 무관한 인력 화면 권한: SYS_ADMIN·PMO, 또는 어느 프로젝트에서든 allowed 역할 */
    private void requireAnywhere(AuthUser user, Set<String> allowed) {
        if ((user.isSysAdmin() && allowed.contains("SYS_ADMIN")) || (user.isPmo() && allowed.contains("PMO"))) {
            return;
        }
        List<String> roles = jdbc.queryForList("SELECT DISTINCT ROLE_ID FROM PJ_MEMBER WHERE USER_ID = ?", String.class,
                user.userId());
        if (roles.stream().noneMatch(allowed::contains)) {
            throw ApiException.forbidden();
        }
    }

    // ───────────────────────── 인력 풀 ─────────────────────────

    @GetMapping("/resources")
    public PageDto<Resource> resources(@AuthenticationPrincipal AuthUser user, @RequestParam(required = false) String q,
            @RequestParam(required = false) String skill, @RequestParam(required = false) String orgType,
            @RequestParam(required = false) String grade, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        requireAnywhere(user, Perm.RESOURCE_READ);
        int[] ps = PageDto.normalize(page, size);
        StringBuilder where = new StringBuilder(" WHERE U.ENABLED = TRUE");
        List<Object> args = new ArrayList<>();
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(U.NAME) LIKE ? OR LOWER(U.LOGIN_ID) LIKE ? OR LOWER(U.COMPANY) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (skill != null && !skill.isBlank()) {
            where.append(" AND EXISTS (SELECT 1 FROM RS_SKILL S WHERE S.USER_ID = U.USER_ID AND LOWER(S.SKILL) LIKE ?)");
            args.add("%" + skill.trim().toLowerCase() + "%");
        }
        if (orgType != null && !orgType.isBlank()) {
            where.append(" AND U.ORG_TYPE = ?");
            args.add(orgType);
        }
        if (grade != null && !grade.isBlank()) {
            where.append(" AND U.GRADE = ?");
            args.add(grade);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER U" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(ps[1]);
        pageArgs.add((long) ps[0] * ps[1]);
        List<String> ids = jdbc.queryForList("SELECT U.USER_ID FROM CM_USER U" + where + " ORDER BY U.NAME, U.LOGIN_ID"
                + " LIMIT ? OFFSET ?", String.class, pageArgs.toArray());
        return new PageDto<>(ids.stream().map(this::resource).toList(), ps[0], ps[1], total == null ? 0 : total);
    }

    Resource resource(String userId) {
        var u = jdbc.query("SELECT USER_ID, NAME, ORG_TYPE, COMPANY, GRADE FROM CM_USER WHERE USER_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5)}, userId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("사용자"));
        List<Skill> skills = jdbc.query("SELECT SKILL, LVL FROM RS_SKILL WHERE USER_ID = ? ORDER BY LVL DESC, SKILL",
                (rs, i) -> new Skill(rs.getString(1), rs.getInt(2)), userId);
        List<ProjectRole> projects = jdbc.query("""
                SELECT P.PROJECT_ID, P.NAME, M.ROLE_ID FROM PJ_MEMBER M JOIN PJ_PROJECT P ON P.PROJECT_ID = M.PROJECT_ID
                 WHERE M.USER_ID = ? AND (M.OUT_DT IS NULL OR M.OUT_DT >= ?) AND (P.END_DT IS NULL OR P.END_DT >= ?)
                 ORDER BY P.START_DT DESC""", (rs, i) -> new ProjectRole(rs.getString(1), rs.getString(2),
                rs.getString(3)), userId, PjSupport.today(), PjSupport.today());
        return new Resource(u[0], u[1], u[2], u[3], u[4], skills, projects);
    }

    @PutMapping("/resources/{userId}")
    @Transactional
    public Resource updateResource(@AuthenticationPrincipal AuthUser user, @PathVariable String userId,
            @RequestBody ResourceRequest req) {
        requireAnywhere(user, Perm.RESOURCE_EDIT);
        resource(userId);
        if (req.grade() != null && !req.grade().isBlank()) {
            PjSupport.oneOf("grade", req.grade(), null, "SPECIAL", "HIGH", "MIDDLE", "JUNIOR");
        }
        jdbc.update("UPDATE CM_USER SET GRADE = ?, COMPANY = ?, PHONE = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE USER_ID = ?",
                blank(req.grade()), PjSupport.optional("company", req.company(), 200),
                PjSupport.optional("phone", req.phone(), 50), Times.now(), user.userId(), userId);
        if (req.skills() != null) {
            jdbc.update("DELETE FROM RS_SKILL WHERE USER_ID = ?", userId);
            Set<String> seen = new HashSet<>();
            for (Skill s : req.skills()) {
                String name = PjSupport.required("skills.skill", s.skill(), 100);
                if (s.level() < 1 || s.level() > 5) {
                    throw PjSupport.validation("skills.level", "수준은 1~5 입니다.");
                }
                if (seen.add(name.toLowerCase())) {
                    jdbc.update("INSERT INTO RS_SKILL (USER_ID, SKILL, LVL) VALUES (?, ?, ?)", userId, name, s.level());
                }
            }
        }
        audit.log(Action.UPDATE, "resource:" + userId, "인력 정보 수정");
        return resource(userId);
    }

    // ───────────────────────── MM 계획 ─────────────────────────

    @GetMapping("/projects/{projectId}/mm-plan")
    public List<MmPlanRow> mmPlan(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        access.forProject(user, projectId).require(Perm.RESOURCE_READ);
        return mmPlanRows(projectId, ym("from", from), ym("to", to));
    }

    public List<MmPlanRow> mmPlanRows(String projectId, YearMonth from, YearMonth to) {
        Map<String, MmPlanRow> rows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT M.USER_ID, U.NAME, U.GRADE, M.ROLE_ID FROM PJ_MEMBER M JOIN CM_USER U ON U.USER_ID = M.USER_ID
                 WHERE M.PROJECT_ID = ? ORDER BY M.ROLE_ID, U.NAME""", rs -> {
            rows.put(rs.getString(1), new MmPlanRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    new TreeMap<>()));
        }, projectId);
        jdbc.query("""
                SELECT P.USER_ID, U.NAME, U.GRADE, P.YM, P.PLAN_MM FROM RS_MM_PLAN P JOIN CM_USER U ON U.USER_ID = P.USER_ID
                 WHERE P.PROJECT_ID = ? ORDER BY P.YM""", rs -> {
            String ym = rs.getString(4);
            if ((from != null && ym.compareTo(from.toString()) < 0) || (to != null && ym.compareTo(to.toString()) > 0)) {
                return;
            }
            MmPlanRow r = rows.computeIfAbsent(rs.getString(1), k -> {
                try {
                    return new MmPlanRow(k, rs.getString(2), rs.getString(3), null, new TreeMap<>());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            r.months().put(ym, rs.getBigDecimal(5).doubleValue());
        }, projectId);
        return new ArrayList<>(rows.values());
    }

    @PutMapping("/projects/{projectId}/mm-plan")
    @Transactional
    public List<MmPlanRow> saveMmPlan(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody List<MmPlanRow> rows) {
        access.forProject(user, projectId).require(Perm.RESOURCE_EDIT);
        for (MmPlanRow r : rows) {
            if (r.userId() == null) {
                throw PjSupport.validation("userId", "필수 입력입니다.");
            }
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER WHERE USER_ID = ?", Integer.class, r.userId());
            if (n == null || n == 0) {
                throw PjSupport.validation("userId", "사용자를 찾을 수 없습니다: " + r.userId());
            }
            for (Map.Entry<String, Double> e : (r.months() == null ? Map.<String, Double>of() : r.months()).entrySet()) {
                YearMonth ym = ym("months", e.getKey());
                double v = e.getValue() == null ? 0 : e.getValue();
                if (v < 0 || v > 3) {
                    throw PjSupport.validation("months." + e.getKey(), "월 MM 은 0~3 사이여야 합니다.");
                }
                jdbc.update("DELETE FROM RS_MM_PLAN WHERE PROJECT_ID = ? AND USER_ID = ? AND YM = ?", projectId,
                        r.userId(), ym.toString());
                if (v > 0) {
                    jdbc.update("INSERT INTO RS_MM_PLAN (PROJECT_ID, USER_ID, YM, PLAN_MM) VALUES (?, ?, ?, ?)",
                            projectId, r.userId(), ym.toString(), BigDecimal.valueOf(v));
                }
            }
        }
        audit.log(Action.UPDATE, "mm-plan:" + projectId, "투입 계획 저장 " + rows.size() + "명", projectId);
        return mmPlanRows(projectId, null, null);
    }

    // ───────────────────────── 히트맵 ─────────────────────────

    @GetMapping("/resources/heatmap")
    public List<HeatmapRow> heatmap(@AuthenticationPrincipal AuthUser user, @RequestParam String from,
            @RequestParam String to, @RequestParam(defaultValue = "month") String unit,
            @RequestParam(required = false) String projectId) {
        requireAnywhere(user, Perm.RESOURCE_READ);
        if (projectId != null) {
            access.forProject(user, projectId);
        }
        PjSupport.oneOf("unit", unit, "month", "month", "week");
        LocalDate f = startDate("from", from);
        LocalDate t = endDate("to", to);
        if (t.isBefore(f)) {
            throw PjSupport.validation("to", "종료는 시작 이후여야 합니다.");
        }
        Map<String, HeatmapRow> rows = new LinkedHashMap<>();
        String userSql = projectId == null
                ? "SELECT DISTINCT U.USER_ID, U.NAME FROM CM_USER U JOIN PJ_MEMBER M ON M.USER_ID = U.USER_ID"
                        + " WHERE U.ENABLED = TRUE ORDER BY U.NAME"
                : "SELECT U.USER_ID, U.NAME FROM CM_USER U JOIN PJ_MEMBER M ON M.USER_ID = U.USER_ID"
                        + " WHERE M.PROJECT_ID = ? ORDER BY U.NAME";
        jdbc.query(userSql, rs -> {
            rows.put(rs.getString(1), new HeatmapRow(rs.getString(1), rs.getString(2), new TreeMap<>()));
        }, projectId == null ? new Object[0] : new Object[] {projectId});
        if ("month".equals(unit)) {
            List<String> months = new ArrayList<>();
            for (YearMonth m = YearMonth.from(f); !m.isAfter(YearMonth.from(t)); m = m.plusMonths(1)) {
                months.add(m.toString());
            }
            for (HeatmapRow r : rows.values()) {
                months.forEach(m -> r.cells().put(m, 0));
            }
            jdbc.query("SELECT USER_ID, YM, SUM(PLAN_MM) FROM RS_MM_PLAN WHERE YM >= ? AND YM <= ? GROUP BY USER_ID, YM",
                    rs -> {
                        HeatmapRow r = rows.get(rs.getString(1));
                        if (r != null) {
                            r.cells().put(rs.getString(2), (int) Math.round(rs.getDouble(3) * 100));
                        }
                    }, months.get(0), months.get(months.size() - 1));
        } else {
            Map<String, Map<String, Integer>> alloc = weeklyAllocation(rows.keySet(), f, t);
            for (HeatmapRow r : rows.values()) {
                for (LocalDate w = monday(f); !w.isAfter(t); w = w.plusWeeks(1)) {
                    r.cells().put(weekKey(w), alloc.getOrDefault(r.userId(), Map.of()).getOrDefault(weekKey(w), 0));
                }
            }
        }
        return new ArrayList<>(rows.values());
    }

    /** 사용자별 주차 배정률(%) = Σ WBS 배정 allocPct (계획 기간이 그 주와 겹치는 작업) */
    Map<String, Map<String, Integer>> weeklyAllocation(Set<String> userIds, LocalDate from, LocalDate to) {
        Map<String, Map<String, Integer>> out = new HashMap<>();
        jdbc.query("""
                SELECT A.USER_ID, A.ALLOC_PCT, W.PLAN_START, W.PLAN_END FROM RS_ASSIGN A
                  JOIN PJ_WBS W ON W.WBS_ID = A.TARGET_ID
                 WHERE A.TARGET_TYPE = 'WBS' AND W.PLAN_START IS NOT NULL AND W.PLAN_END IS NOT NULL
                   AND W.PLAN_START <= ? AND W.PLAN_END >= ?""", rs -> {
            String u = rs.getString(1);
            if (userIds != null && !userIds.contains(u)) {
                return;
            }
            int pct = rs.getInt(2);
            LocalDate s = rs.getObject(3, LocalDate.class);
            LocalDate e = rs.getObject(4, LocalDate.class);
            for (LocalDate w = monday(s.isBefore(from) ? from : s); !w.isAfter(e) && !w.isAfter(to); w = w.plusWeeks(1)) {
                out.computeIfAbsent(u, k -> new HashMap<>()).merge(weekKey(w), pct, Integer::sum);
            }
        }, to, from);
        return out;
    }

    // ───────────────────────── 배정 ─────────────────────────

    @GetMapping("/projects/{projectId}/work-items/unassigned")
    public List<WorkItem> unassigned(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String type) {
        access.forProject(user, projectId).require(Perm.ASSIGN_READ);
        List<WorkItem> out = new ArrayList<>();
        if (type == null || "WBS".equals(type)) {
            Map<String, String> names = new HashMap<>();
            Map<String, String> parents = new HashMap<>();
            jdbc.query("SELECT WBS_ID, PARENT_ID, WBS_NO, NAME FROM PJ_WBS WHERE PROJECT_ID = ?", rs -> {
                names.put(rs.getString(1), rs.getString(3) + " " + rs.getString(4));
                parents.put(rs.getString(1), rs.getString(2));
            }, projectId);
            jdbc.query("""
                    SELECT W.WBS_ID, W.WBS_NO, W.NAME, W.PLAN_START, W.PLAN_END, W.PARENT_ID FROM PJ_WBS W
                     WHERE W.PROJECT_ID = ? AND W.STATUS <> 'DONE'
                       AND NOT EXISTS (SELECT 1 FROM PJ_WBS C WHERE C.PARENT_ID = W.WBS_ID)
                       AND NOT EXISTS (SELECT 1 FROM RS_ASSIGN A WHERE A.TARGET_TYPE = 'WBS' AND A.TARGET_ID = W.WBS_ID)
                     ORDER BY W.ORD""", rs -> {
                String p = rs.getString(6);
                out.add(new WorkItem("WBS", rs.getString(1), rs.getString(2) + " " + rs.getString(3),
                        p == null ? "" : names.getOrDefault(p, ""), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, LocalDate.class)));
            }, projectId);
        }
        if (type == null || "SCREEN".equals(type)) {
            jdbc.query("""
                    SELECT S.SCREEN_ID, S.SCREEN_CODE, S.NAME, Y.NAME FROM AS_SCREEN S JOIN AS_SYSTEM Y ON Y.SYSTEM_ID = S.SYSTEM_ID
                      LEFT JOIN AS_ANALYSIS A ON A.SCREEN_ID = S.SCREEN_ID
                     WHERE Y.PROJECT_ID = ? AND A.OWNER_ID IS NULL AND COALESCE(A.STATUS, 'AUTO') <> 'DONE'
                     ORDER BY Y.NAME, S.SCREEN_CODE""", rs -> {
                out.add(new WorkItem("SCREEN", rs.getString(1), rs.getString(2) + " " + rs.getString(3),
                        rs.getString(4), null, null));
            }, projectId);
        }
        return out;
    }

    @PostMapping("/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Assignment assign(@AuthenticationPrincipal AuthUser user, @RequestBody AssignRequest req) {
        WorkTargets.Target t = targets.resolve("assignment", req.targetType(), req.targetId());
        access.forProject(user, t.projectId()).require(Perm.ASSIGN);
        if (req.userId() == null) {
            throw PjSupport.validation("userId", "필수 입력입니다.");
        }
        Integer member = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                Integer.class, t.projectId(), req.userId());
        if (member == null || member == 0) {
            throw PjSupport.validation("userId", "프로젝트 멤버만 배정할 수 있습니다.");
        }
        int pct = req.allocPct() == null ? 100 : req.allocPct();
        if (pct < 1 || pct > 100) {
            throw PjSupport.validation("allocPct", "배정률은 1~100 입니다.");
        }
        String id = Ulid.next();
        try {
            jdbc.update("""
                    INSERT INTO RS_ASSIGN (ASSIGN_ID, PROJECT_ID, TARGET_TYPE, TARGET_ID, USER_ID, ALLOC_PCT, CREATED_AT,
                                           CREATED_BY)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, t.projectId(), t.type(), t.id(), req.userId(), pct,
                    Times.now(), user.userId());
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 배정된 사용자입니다.");
        }
        if ("SCREEN".equals(t.type())) {
            int n = jdbc.update("UPDATE AS_ANALYSIS SET OWNER_ID = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE SCREEN_ID = ?"
                    + " AND OWNER_ID IS NULL", req.userId(), Times.now(), user.userId(), t.id());
            if (n == 0) {
                Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM AS_ANALYSIS WHERE SCREEN_ID = ?",
                        Integer.class, t.id());
                if (exists == null || exists == 0) {
                    jdbc.update("INSERT INTO AS_ANALYSIS (SCREEN_ID, STATUS, OWNER_ID, UPDATED_AT, UPDATED_BY)"
                            + " VALUES (?, 'AUTO', ?, ?, ?)", t.id(), req.userId(), Times.now(), user.userId());
                }
            }
        }
        String warning = warning(req.userId(), t);
        notify.notify(req.userId(), Event.ASSIGNED, "작업이 배정되었습니다: " + t.label(),
                warning == null ? null : "주의: " + warning, link(t));
        audit.log(Action.CREATE, "assignment:" + id, t.type() + " " + t.label() + " → " + req.userId(), t.projectId());
        return new Assignment(id, t.type(), t.id(), req.userId(), pct, warning);
    }

    private static String link(WorkTargets.Target t) {
        return switch (t.type()) {
            case "WBS" -> "/projects/" + t.projectId() + "/wbs?focus=" + t.id();
            case "SCREEN" -> "/asis/screens/" + t.id();
            default -> null;
        };
    }

    /** 배정 후 해당 작업 기간 중 주 배정률이 100% 를 넘으면 첫 주를 알려준다 */
    private String warning(String userId, WorkTargets.Target t) {
        if (!"WBS".equals(t.type())) {
            return null;
        }
        var dates = jdbc.query("SELECT PLAN_START, PLAN_END FROM PJ_WBS WHERE WBS_ID = ?",
                (rs, i) -> new LocalDate[] {rs.getObject(1, LocalDate.class), rs.getObject(2, LocalDate.class)},
                t.id()).get(0);
        if (dates[0] == null || dates[1] == null) {
            return null;
        }
        Map<String, Integer> weeks = weeklyAllocation(Set.of(userId), dates[0], dates[1])
                .getOrDefault(userId, Map.of());
        return new TreeMap<>(weeks).entrySet().stream().filter(e -> e.getValue() > 100).findFirst()
                .map(e -> e.getKey() + " 배정률 " + e.getValue() + "%").orElse(null);
    }

    @DeleteMapping("/assignments/{assignmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unassign(@AuthenticationPrincipal AuthUser user, @PathVariable String assignmentId) {
        var row = jdbc.query("SELECT PROJECT_ID, TARGET_TYPE, TARGET_ID, USER_ID FROM RS_ASSIGN WHERE ASSIGN_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)},
                assignmentId).stream().findFirst().orElseThrow(() -> ApiException.notFound("배정"));
        access.forProject(user, row[0]).require(Perm.ASSIGN);
        jdbc.update("DELETE FROM RS_ASSIGN WHERE ASSIGN_ID = ?", assignmentId);
        if ("SCREEN".equals(row[1])) {
            jdbc.update("UPDATE AS_ANALYSIS SET OWNER_ID = NULL WHERE SCREEN_ID = ? AND OWNER_ID = ?", row[2], row[3]);
        }
        audit.log(Action.DELETE, "assignment:" + assignmentId, row[1] + " 배정 해제", row[0]);
    }

    @GetMapping("/projects/{projectId}/assignments")
    public List<Assignment> assignments(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String userId) {
        access.forProject(user, projectId);
        return jdbc.query("SELECT ASSIGN_ID, TARGET_TYPE, TARGET_ID, USER_ID, ALLOC_PCT FROM RS_ASSIGN WHERE PROJECT_ID = ?"
                        + (userId == null ? "" : " AND USER_ID = ?") + " ORDER BY CREATED_AT",
                (rs, i) -> new Assignment(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), null), userId == null ? new Object[] {projectId} : new Object[] {projectId, userId});
    }

    // ───────────────────────── 투입 체크리스트 ─────────────────────────

    @GetMapping("/projects/{projectId}/members/{userId}/checklist")
    @Transactional
    public List<CheckItem> checklist(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @PathVariable String userId) {
        var a = access.forProject(user, projectId);
        if (!user.userId().equals(userId) && !a.has(Perm.PROJECT_MANAGE) && !a.has(Set.of("PL"))) {
            throw ApiException.forbidden();
        }
        requireMember(projectId, userId);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER_CHECK WHERE PROJECT_ID = ? AND USER_ID = ?",
                Integer.class, projectId, userId);
        if (n == null || n == 0) {
            jdbc.query("SELECT NAME, SORT_ORD FROM CM_CODE WHERE GROUP_CODE = 'ONBOARD_ITEM' AND USE_YN = TRUE"
                    + " ORDER BY SORT_ORD", rs -> {
                jdbc.update("INSERT INTO PJ_MEMBER_CHECK (PROJECT_ID, USER_ID, ITEM, ORD) VALUES (?, ?, ?, ?)", projectId,
                        userId, rs.getString(1), rs.getInt(2));
            });
        }
        return checkItems(projectId, userId);
    }

    @PutMapping("/projects/{projectId}/members/{userId}/checklist")
    @Transactional
    public List<CheckItem> saveChecklist(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @PathVariable String userId, @RequestBody List<CheckItem> items) {
        access.forProject(user, projectId).require(Perm.PROJECT_MANAGE);
        requireMember(projectId, userId);
        Map<String, java.time.OffsetDateTime> prevDone = new HashMap<>();
        jdbc.query("SELECT ITEM, DONE_AT FROM PJ_MEMBER_CHECK WHERE PROJECT_ID = ? AND USER_ID = ? AND DONE = TRUE",
                rs -> {
                    prevDone.put(rs.getString(1), Times.read(rs, "DONE_AT"));
                }, projectId, userId);
        jdbc.update("DELETE FROM PJ_MEMBER_CHECK WHERE PROJECT_ID = ? AND USER_ID = ?", projectId, userId);
        int ord = 0;
        Set<String> seen = new HashSet<>();
        for (CheckItem c : items) {
            String item = PjSupport.required("item", c.item(), 200);
            if (!seen.add(item)) {
                continue;
            }
            java.time.OffsetDateTime at = c.done() ? prevDone.getOrDefault(item, Times.now()) : null;
            jdbc.update("INSERT INTO PJ_MEMBER_CHECK (PROJECT_ID, USER_ID, ITEM, ORD, DONE, DONE_AT) VALUES (?, ?, ?, ?, ?, ?)",
                    projectId, userId, item, ord++, c.done(), at);
        }
        audit.log(Action.UPDATE, "checklist:" + userId, "투입 체크리스트 저장", projectId);
        return checkItems(projectId, userId);
    }

    private List<CheckItem> checkItems(String projectId, String userId) {
        return jdbc.query("SELECT ITEM, DONE, DONE_AT FROM PJ_MEMBER_CHECK WHERE PROJECT_ID = ? AND USER_ID = ?"
                + " ORDER BY ORD, ITEM", (rs, i) -> new CheckItem(rs.getString(1), rs.getBoolean(2),
                Times.read(rs, "DONE_AT")), projectId, userId);
    }

    private void requireMember(String projectId, String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                Integer.class, projectId, userId);
        if (n == null || n == 0) {
            throw ApiException.notFound("프로젝트 멤버");
        }
    }

    // ───────────────────────── PARTNER 열람 범위 ─────────────────────────

    @GetMapping("/projects/{projectId}/members/{userId}/scope")
    public Scope scope(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @PathVariable String userId) {
        var a = access.forProject(user, projectId);
        if (!user.userId().equals(userId) && !a.has(Perm.PROJECT_MANAGE)) {
            throw ApiException.forbidden();
        }
        return jdbc.query("SELECT SYSTEM_IDS, MENU_IDS FROM PJ_MEMBER_SCOPE WHERE PROJECT_ID = ? AND USER_ID = ?",
                (rs, i) -> new Scope(PjSupport.csv(rs.getString(1)), PjSupport.csv(rs.getString(2))), projectId, userId)
                .stream().findFirst().orElse(new Scope(List.of(), List.of()));
    }

    @PutMapping("/projects/{projectId}/members/{userId}/scope")
    @Transactional
    public Scope saveScope(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @PathVariable String userId, @RequestBody Scope req) {
        access.forProject(user, projectId).require(Perm.PROJECT_MANAGE);
        requireMember(projectId, userId);
        List<String> systems = req.systemIds() == null ? List.of() : req.systemIds();
        for (String s : systems) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM AS_SYSTEM WHERE SYSTEM_ID = ? AND PROJECT_ID = ?",
                    Integer.class, s, projectId);
            if (n == null || n == 0) {
                throw PjSupport.validation("systemIds", "이 프로젝트의 시스템이 아닙니다: " + s);
            }
        }
        jdbc.update("DELETE FROM PJ_MEMBER_SCOPE WHERE PROJECT_ID = ? AND USER_ID = ?", projectId, userId);
        jdbc.update("INSERT INTO PJ_MEMBER_SCOPE (PROJECT_ID, USER_ID, SYSTEM_IDS, MENU_IDS) VALUES (?, ?, ?, ?)",
                projectId, userId, PjSupport.csv(systems), PjSupport.csv(req.menuIds()));
        audit.log(Action.UPDATE, "member-scope:" + userId, "열람 범위 저장", projectId);
        return scope(user, projectId, userId);
    }

    // ───────────────────────── 날짜 유틸 ─────────────────────────

    static YearMonth ym(String field, String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(v.trim().length() > 7 ? v.trim().substring(0, 7) : v.trim());
        } catch (DateTimeParseException e) {
            throw PjSupport.validation(field, "YYYY-MM 형식이어야 합니다.");
        }
    }

    static LocalDate startDate(String field, String v) {
        String s = v.trim();
        return s.length() == 7 ? ym(field, s).atDay(1) : kr.pmsx.common.DateParams.localDate(field, s);
    }

    static LocalDate endDate(String field, String v) {
        String s = v.trim();
        return s.length() == 7 ? ym(field, s).atEndOfMonth() : kr.pmsx.common.DateParams.localDate(field, s);
    }

    public static LocalDate monday(LocalDate d) {
        return d.with(DayOfWeek.MONDAY);
    }

    public static String weekKey(LocalDate d) {
        return d.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v;
    }
}
