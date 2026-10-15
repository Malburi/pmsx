package kr.pmsx.pj.wbs;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.WorkCalendar;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.pj.PjSupport;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WBS 조회·일괄 저장·부분 수정·롤업·CPM·진척 (docs/12 §2). 권한 확인은 컨트롤러.
 * 상위 항목의 진척·기간·상태는 하위로부터 계산(저장값 무시), 크리티컬 패스는 영업일 기준 CPM.
 */
@Service
public class WbsService {

    public record Dep(String predId, String type, int lagDays) {
    }

    public record WbsItem(String wbsId, String parentId, String wbsNo, String name, int order, LocalDate planStart,
                          LocalDate planEnd, LocalDate actStart, LocalDate actEnd, double weight, double progress,
                          boolean milestone, String status, List<UserRef> assignees, String deliverableId,
                          List<Dep> deps, boolean critical, LocalDate baselineStart, LocalDate baselineEnd) {
    }

    public record PhaseProgress(String wbsId, String name, double planned, double actual) {
    }

    public record CurvePoint(LocalDate date, double planned, Double actual) {
    }

    public record ProjectProgress(LocalDate asOf, double planned, double actual, Double spi,
                                  List<PhaseProgress> byPhase, List<CurvePoint> sCurve) {
    }

    /** 내부 계산용 행 */
    static final class Row {
        String id;
        String parentId;
        String wbsNo;
        String name;
        int ord;
        LocalDate planStart;
        LocalDate planEnd;
        LocalDate actStart;
        LocalDate actEnd;
        double weight;
        double progress;
        boolean milestone;
        String status;
        String deliverableId;
        final List<Row> children = new ArrayList<>();
        final List<Dep> deps = new ArrayList<>();
        final List<UserRef> assignees = new ArrayList<>();
        boolean critical;
        LocalDate baseStart;
        LocalDate baseEnd;

        boolean leaf() {
            return children.isEmpty();
        }
    }

    /** 프로젝트 WBS 전체 (계산 포함) */
    public static final class Tree {
        final List<Row> rows;
        final Map<String, Row> byId;
        final List<Row> roots;

        Tree(List<Row> rows) {
            this.rows = rows;
            this.byId = new LinkedHashMap<>();
            for (Row r : rows) {
                byId.put(r.id, r);
            }
            this.roots = new ArrayList<>();
            for (Row r : rows) {
                Row p = r.parentId == null ? null : byId.get(r.parentId);
                if (p == null) {
                    roots.add(r);
                } else {
                    p.children.add(r);
                }
            }
        }

        public List<Row> leaves() {
            return rows.stream().filter(Row::leaf).toList();
        }
    }

    private final JdbcTemplate jdbc;
    private final WorkCalendar calendar;
    private final AuditService audit;

    public WbsService(JdbcTemplate jdbc, WorkCalendar calendar, AuditService audit) {
        this.jdbc = jdbc;
        this.calendar = calendar;
        this.audit = audit;
    }

    // ───────────────────────── 조회 ─────────────────────────

    public List<WbsItem> list(String projectId) {
        Tree t = tree(projectId, calendar.load());
        List<WbsItem> out = new ArrayList<>();
        for (Row r : ordered(t)) {
            out.add(toItem(r));
        }
        return out;
    }

    public WbsItem one(String wbsId) {
        String projectId = projectOf(wbsId);
        Tree t = tree(projectId, calendar.load());
        return toItem(t.byId.get(wbsId));
    }

    public String projectOf(String wbsId) {
        return jdbc.query("SELECT PROJECT_ID FROM PJ_WBS WHERE WBS_ID = ?", (rs, i) -> rs.getString(1), wbsId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("WBS 항목"));
    }

    /** 롤업·CPM·베이스라인까지 계산된 트리 */
    public Tree tree(String projectId, WorkCalendar.Days cal) {
        List<Row> rows = jdbc.query("""
                SELECT WBS_ID, PARENT_ID, WBS_NO, NAME, ORD, PLAN_START, PLAN_END, ACT_START, ACT_END, WEIGHT, PROGRESS,
                       MILESTONE, STATUS, DELIVERABLE_ID
                  FROM PJ_WBS WHERE PROJECT_ID = ? ORDER BY ORD, WBS_NO""", (rs, i) -> {
            Row r = new Row();
            r.id = rs.getString(1);
            r.parentId = rs.getString(2);
            r.wbsNo = rs.getString(3);
            r.name = rs.getString(4);
            r.ord = rs.getInt(5);
            r.planStart = rs.getObject(6, LocalDate.class);
            r.planEnd = rs.getObject(7, LocalDate.class);
            r.actStart = rs.getObject(8, LocalDate.class);
            r.actEnd = rs.getObject(9, LocalDate.class);
            r.weight = rs.getDouble(10);
            r.progress = rs.getDouble(11);
            r.milestone = rs.getBoolean(12);
            r.status = rs.getString(13);
            r.deliverableId = rs.getString(14);
            return r;
        }, projectId);
        Tree t = new Tree(rows);
        jdbc.query("""
                SELECT D.PRED_ID, D.SUCC_ID, D.DEP_TYPE, D.LAG_DAYS FROM PJ_WBS_DEP D
                  JOIN PJ_WBS W ON W.WBS_ID = D.SUCC_ID WHERE W.PROJECT_ID = ?""", rs -> {
            Row s = t.byId.get(rs.getString(2));
            if (s != null) {
                s.deps.add(new Dep(rs.getString(1), rs.getString(3), rs.getInt(4)));
            }
        }, projectId);
        jdbc.query("""
                SELECT A.TARGET_ID, A.USER_ID, U.NAME FROM RS_ASSIGN A JOIN CM_USER U ON U.USER_ID = A.USER_ID
                 WHERE A.PROJECT_ID = ? AND A.TARGET_TYPE = 'WBS' ORDER BY U.NAME""", rs -> {
            Row r = t.byId.get(rs.getString(1));
            if (r != null) {
                r.assignees.add(new UserRef(rs.getString(2), rs.getString(3)));
            }
        }, projectId);
        String baselineId = jdbc.query("SELECT BASELINE_ID FROM PJ_BASELINE WHERE PROJECT_ID = ?"
                + " ORDER BY CREATED_AT DESC, BASELINE_ID DESC LIMIT 1", (rs, i) -> rs.getString(1), projectId).stream()
                .findFirst().orElse(null);
        if (baselineId != null) {
            jdbc.query("SELECT WBS_ID, PLAN_START, PLAN_END FROM PJ_BASELINE_ITEM WHERE BASELINE_ID = ?", rs -> {
                Row r = t.byId.get(rs.getString(1));
                if (r != null) {
                    r.baseStart = rs.getObject(2, LocalDate.class);
                    r.baseEnd = rs.getObject(3, LocalDate.class);
                }
            }, baselineId);
        }
        for (Row r : t.roots) {
            rollup(r, new HashSet<>());
        }
        critical(t, cal);
        return t;
    }

    /** 트리 순서(깊이 우선, ORD 순) */
    static List<Row> ordered(Tree t) {
        List<Row> out = new ArrayList<>();
        for (Row r : t.roots) {
            walk(r, out, new HashSet<>());
        }
        return out;
    }

    private static void walk(Row r, List<Row> out, Set<String> guard) {
        if (!guard.add(r.id)) {
            return;
        }
        out.add(r);
        r.children.sort(Comparator.comparingInt((Row x) -> x.ord));
        for (Row c : r.children) {
            walk(c, out, guard);
        }
    }

    private static void rollup(Row r, Set<String> guard) {
        if (r.leaf() || !guard.add(r.id)) {
            return;
        }
        double ws = 0;
        double wp = 0;
        LocalDate ps = null;
        LocalDate pe = null;
        LocalDate as = null;
        LocalDate ae = null;
        boolean allEnded = true;
        boolean allDone = true;
        boolean anyStarted = false;
        for (Row c : r.children) {
            rollup(c, guard);
            double w = c.weight <= 0 ? 1 : c.weight;
            ws += w;
            wp += w * c.progress;
            ps = min(ps, c.planStart);
            pe = max(pe, c.planEnd);
            as = min(as, c.actStart);
            if (c.actEnd == null) {
                allEnded = false;
            } else {
                ae = max(ae, c.actEnd);
            }
            allDone &= "DONE".equals(c.status);
            anyStarted |= !"TODO".equals(c.status) || c.progress > 0;
        }
        r.progress = ws == 0 ? 0 : round1(wp / ws);
        r.planStart = ps;
        r.planEnd = pe;
        r.actStart = as;
        r.actEnd = allEnded ? ae : null;
        r.status = allDone ? "DONE" : anyStarted ? "IN_PROGRESS" : "TODO";
    }

    private void critical(Tree t, WorkCalendar.Days cal) {
        List<Row> leaves = t.leaves().stream().filter(r -> r.planStart != null && r.planEnd != null).toList();
        if (leaves.isEmpty()) {
            return;
        }
        LocalDate epoch = leaves.stream().map(r -> r.planStart).min(LocalDate::compareTo).orElseThrow();
        List<Cpm.Task> tasks = new ArrayList<>();
        for (Row r : leaves) {
            int dur = r.milestone ? 0 : Math.max(1, cal.count(r.planStart, r.planEnd));
            tasks.add(new Cpm.Task(r.id, cal.index(epoch, r.planStart), dur));
        }
        List<Cpm.Dep> deps = new ArrayList<>();
        for (Row r : t.rows) {
            for (Dep d : r.deps) {
                deps.add(new Cpm.Dep(d.predId(), r.id, d.type(), d.lagDays()));
            }
        }
        Cpm.Result res;
        try {
            res = Cpm.compute(tasks, deps);
        } catch (Cpm.CycleException e) {
            return;   // 저장 시점에 막히므로 조회에서는 무시
        }
        for (Row r : leaves) {
            r.critical = res.critical().contains(r.id);
        }
        for (Row r : t.roots) {
            markParents(r, new HashSet<>());
        }
    }

    private static boolean markParents(Row r, Set<String> guard) {
        if (r.leaf() || !guard.add(r.id)) {
            return r.critical;
        }
        boolean any = false;
        for (Row c : r.children) {
            any |= markParents(c, guard);
        }
        r.critical = any;
        return any;
    }

    private static WbsItem toItem(Row r) {
        return new WbsItem(r.id, r.parentId, r.wbsNo, r.name, r.ord, r.planStart, r.planEnd, r.actStart, r.actEnd,
                r.weight, round1(r.progress), r.milestone, r.status, List.copyOf(r.assignees), r.deliverableId,
                List.copyOf(r.deps), r.critical, r.baseStart, r.baseEnd);
    }

    // ───────────────────────── 일괄 저장 ─────────────────────────

    /** 목록 순서 = 표시 순서. wbsId 가 DB 에 없으면(임시 ID 포함) 신규로 본다. WBS 번호는 트리 순서로 다시 매긴다. */
    @Transactional
    public List<WbsItem> saveAll(String projectId, List<WbsItem> items, String userId) {
        if (items == null) {
            throw PjSupport.validation("items", "목록이 필요합니다.");
        }
        Set<String> existing = new HashSet<>(jdbc.queryForList("SELECT WBS_ID FROM PJ_WBS WHERE PROJECT_ID = ?",
                String.class, projectId));
        Map<String, String> idMap = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++) {
            WbsItem it = items.get(i);
            String key = it.wbsId() == null || it.wbsId().isBlank() ? "#new-" + i : it.wbsId();
            if (idMap.containsKey(key)) {
                throw PjSupport.validation("items[" + i + "].wbsId", "중복된 ID 입니다.");
            }
            idMap.put(key, existing.contains(key) ? key : Ulid.next());
        }
        List<String> keys = new ArrayList<>(idMap.keySet());
        // 행 구성 + 검증
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            WbsItem it = items.get(i);
            Row r = new Row();
            r.id = idMap.get(keys.get(i));
            r.parentId = it.parentId() == null || it.parentId().isBlank() ? null : idMap.get(it.parentId());
            if (it.parentId() != null && !it.parentId().isBlank() && r.parentId == null) {
                throw PjSupport.validation("items[" + i + "].parentId", "상위 항목이 목록에 없습니다.");
            }
            r.name = PjSupport.required("items[" + i + "].name", it.name(), 300);
            r.ord = i;
            r.planStart = it.planStart();
            r.planEnd = it.planEnd();
            if (r.planStart != null && r.planEnd != null && r.planEnd.isBefore(r.planStart)) {
                throw PjSupport.validation("items[" + i + "].planEnd", "종료일은 시작일 이후여야 합니다.");
            }
            r.actStart = it.actStart();
            r.actEnd = it.actEnd();
            r.weight = it.weight() <= 0 ? 1 : it.weight();
            r.progress = clampProgress(it.progress());
            r.milestone = it.milestone();
            r.status = PjSupport.oneOf("items[" + i + "].status", it.status(), "TODO", "TODO", "IN_PROGRESS", "DONE",
                    "BLOCKED");
            r.deliverableId = it.deliverableId();
            for (Dep d : it.deps() == null ? List.<Dep>of() : it.deps()) {
                String pred = d.predId() == null ? null : idMap.get(d.predId());
                if (pred == null) {
                    throw PjSupport.validation("items[" + i + "].deps", "선행 작업이 목록에 없습니다: " + d.predId());
                }
                if (pred.equals(r.id)) {
                    throw PjSupport.validation("items[" + i + "].deps", "자기 자신을 선행 작업으로 지정할 수 없습니다.");
                }
                r.deps.add(new Dep(pred, PjSupport.oneOf("items[" + i + "].deps.type", d.type(), "FS", "FS", "SS",
                        "FF", "SF"), d.lagDays()));
            }
            if (it.assignees() != null) {
                r.assignees.addAll(it.assignees());
            }
            rows.add(r);
        }
        Tree t = new Tree(rows);
        checkParentCycle(t);
        List<Cpm.Dep> deps = new ArrayList<>();
        for (Row r : rows) {
            for (Dep d : r.deps) {
                deps.add(new Cpm.Dep(d.predId(), r.id, d.type(), d.lagDays()));
            }
        }
        try {
            Cpm.checkCycle(t.byId.keySet(), deps);
        } catch (Cpm.CycleException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WBS_CYCLE", e.getMessage());
        }
        numberTree(t);

        // 반영
        Set<String> keep = t.byId.keySet();
        List<String> removed = existing.stream().filter(x -> !keep.contains(x)).toList();
        jdbc.update("DELETE FROM PJ_WBS_DEP WHERE SUCC_ID IN (SELECT WBS_ID FROM PJ_WBS WHERE PROJECT_ID = ?)"
                + " OR PRED_ID IN (SELECT WBS_ID FROM PJ_WBS WHERE PROJECT_ID = ?)", projectId, projectId);
        for (String id : removed) {
            jdbc.update("DELETE FROM RS_ASSIGN WHERE TARGET_TYPE = 'WBS' AND TARGET_ID = ?", id);
            jdbc.update("UPDATE DL_DELIVERABLE SET WBS_ID = NULL WHERE WBS_ID = ?", id);
            jdbc.update("DELETE FROM PJ_WBS WHERE WBS_ID = ?", id);
        }
        var now = Times.now();
        for (Row r : rows) {
            if (existing.contains(r.id)) {
                jdbc.update("""
                        UPDATE PJ_WBS SET PARENT_ID = ?, WBS_NO = ?, NAME = ?, ORD = ?, PLAN_START = ?, PLAN_END = ?,
                               ACT_START = ?, ACT_END = ?, WEIGHT = ?, PROGRESS = ?, MILESTONE = ?, STATUS = ?,
                               DELIVERABLE_ID = ?, UPDATED_AT = ?, UPDATED_BY = ?
                         WHERE WBS_ID = ?""", r.parentId, r.wbsNo, r.name, r.ord, r.planStart, r.planEnd, r.actStart,
                        r.actEnd, bd(r.weight), bd1(r.progress), r.milestone, r.status, r.deliverableId, now, userId,
                        r.id);
            } else {
                insertRow(projectId, r, userId);
            }
            for (Dep d : r.deps) {
                jdbc.update("INSERT INTO PJ_WBS_DEP (PRED_ID, SUCC_ID, DEP_TYPE, LAG_DAYS) VALUES (?, ?, ?, ?)",
                        d.predId(), r.id, d.type(), d.lagDays());
            }
        }
        // 담당자: 목록에 assignees 가 있으면 그대로 맞춘다
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).assignees() != null) {
                syncAssignees(projectId, rows.get(i).id, items.get(i).assignees().stream().map(UserRef::userId)
                        .toList(), userId);
            }
        }
        audit.log(Action.UPDATE, "wbs:" + projectId, "WBS 일괄 저장 " + rows.size() + "건 (삭제 " + removed.size() + ")",
                projectId);
        return list(projectId);
    }

    void insertRow(String projectId, Row r, String userId) {
        jdbc.update("""
                INSERT INTO PJ_WBS (WBS_ID, PROJECT_ID, PARENT_ID, WBS_NO, NAME, ORD, PLAN_START, PLAN_END, ACT_START,
                                    ACT_END, WEIGHT, PROGRESS, MILESTONE, STATUS, DELIVERABLE_ID, CREATED_AT,
                                    UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", r.id, projectId, r.parentId, r.wbsNo,
                r.name, r.ord, r.planStart, r.planEnd, r.actStart, r.actEnd, bd(r.weight), bd1(r.progress),
                r.milestone, r.status == null ? "TODO" : r.status, r.deliverableId, Times.now(), Times.now(), userId);
    }

    private static void checkParentCycle(Tree t) {
        for (Row r : t.rows) {
            Set<String> seen = new HashSet<>();
            Row x = r;
            while (x != null && x.parentId != null) {
                if (!seen.add(x.id)) {
                    throw new ApiException(HttpStatus.BAD_REQUEST, "WBS_CYCLE", "상위 항목 관계에 순환이 있습니다.");
                }
                x = t.byId.get(x.parentId);
            }
        }
    }

    /** 1, 1.1, 1.2, 2 ... */
    static void numberTree(Tree t) {
        List<Row> roots = new ArrayList<>(t.roots);
        roots.sort(Comparator.comparingInt((Row r) -> r.ord));
        int i = 1;
        for (Row r : roots) {
            number(r, String.valueOf(i++), new HashSet<>());
        }
    }

    private static void number(Row r, String no, Set<String> guard) {
        if (!guard.add(r.id)) {
            return;
        }
        r.wbsNo = no;
        r.children.sort(Comparator.comparingInt((Row x) -> x.ord));
        int i = 1;
        for (Row c : r.children) {
            number(c, no + "." + i++, guard);
        }
    }

    public void syncAssignees(String projectId, String wbsId, List<String> userIds, String by) {
        Set<String> want = new HashSet<>(userIds.stream().filter(x -> x != null && !x.isBlank()).toList());
        Set<String> have = new HashSet<>(jdbc.queryForList(
                "SELECT USER_ID FROM RS_ASSIGN WHERE TARGET_TYPE = 'WBS' AND TARGET_ID = ?", String.class, wbsId));
        for (String u : have) {
            if (!want.contains(u)) {
                jdbc.update("DELETE FROM RS_ASSIGN WHERE TARGET_TYPE = 'WBS' AND TARGET_ID = ? AND USER_ID = ?", wbsId,
                        u);
            }
        }
        for (String u : want) {
            if (!have.contains(u)) {
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER WHERE USER_ID = ?", Integer.class, u);
                if (n == null || n == 0) {
                    throw PjSupport.validation("assigneeIds", "사용자를 찾을 수 없습니다: " + u);
                }
                jdbc.update("""
                        INSERT INTO RS_ASSIGN (ASSIGN_ID, PROJECT_ID, TARGET_TYPE, TARGET_ID, USER_ID, ALLOC_PCT, CREATED_AT,
                                               CREATED_BY)
                        VALUES (?, ?, 'WBS', ?, ?, 100, ?, ?)""", Ulid.next(), projectId, wbsId, u, Times.now(), by);
            }
        }
    }

    // ───────────────────────── 부분 수정 (간트 드래그·칸반) ─────────────────────────

    public record Patch(LocalDate planStart, LocalDate planEnd, LocalDate actStart, LocalDate actEnd, Double progress,
                        String status, List<String> assigneeIds) {
    }

    @Transactional
    public WbsItem patch(String wbsId, Patch p, boolean scheduleAllowed, String userId) {
        String projectId = projectOf(wbsId);
        Tree t = tree(projectId, calendar.load());
        Row r = t.byId.get(wbsId);
        boolean leaf = r.leaf();
        if (!scheduleAllowed && (p.planStart() != null || p.planEnd() != null || p.assigneeIds() != null)) {
            throw ApiException.forbidden();
        }
        LocalDate ps = p.planStart() != null ? p.planStart() : r.planStart;
        LocalDate pe = p.planEnd() != null ? p.planEnd() : r.planEnd;
        if (ps != null && pe != null && pe.isBefore(ps)) {
            throw PjSupport.validation("planEnd", "종료일은 시작일 이후여야 합니다.");
        }
        if (!leaf && (p.progress() != null || p.status() != null)) {
            throw PjSupport.validation("progress", "상위 항목의 진척·상태는 하위 항목에서 계산됩니다.");
        }
        double progress = p.progress() != null ? clampProgress(p.progress()) : r.progress;
        String status = p.status() != null ? PjSupport.oneOf("status", p.status(), null, "TODO", "IN_PROGRESS", "DONE",
                "BLOCKED") : r.status;
        LocalDate as = p.actStart() != null ? p.actStart() : r.actStart;
        LocalDate ae = p.actEnd() != null ? p.actEnd() : r.actEnd;
        LocalDate today = PjSupport.today();
        if (leaf) {
            if (p.status() != null && "DONE".equals(status) && p.progress() == null) {
                progress = 100;
            }
            if (p.progress() != null && progress >= 100 && p.status() == null) {
                status = "DONE";
            }
            if (progress > 0 && "TODO".equals(status)) {
                status = "IN_PROGRESS";
            }
            if (!"TODO".equals(status) && as == null) {
                as = today;
            }
            if ("DONE".equals(status) && ae == null) {
                ae = today;
            }
            if (!"DONE".equals(status) && p.actEnd() == null) {
                ae = null;
            }
        }
        if (leaf) {
            jdbc.update("""
                    UPDATE PJ_WBS SET PLAN_START = ?, PLAN_END = ?, ACT_START = ?, ACT_END = ?, PROGRESS = ?, STATUS = ?,
                           UPDATED_AT = ?, UPDATED_BY = ? WHERE WBS_ID = ?""", ps, pe, as, ae, bd1(progress), status,
                    Times.now(), userId, wbsId);
        } else if (p.planStart() != null || p.planEnd() != null) {
            // 상위 막대 이동: 하위 전체를 같은 일수만큼 이동
            long shift = r.planStart != null && p.planStart() != null
                    ? java.time.temporal.ChronoUnit.DAYS.between(r.planStart, p.planStart()) : 0;
            if (shift != 0) {
                shiftSubtree(r, shift, userId);
            }
        }
        if (p.assigneeIds() != null) {
            syncAssignees(projectId, wbsId, p.assigneeIds(), userId);
        }
        audit.log(Action.UPDATE, "wbs-item:" + wbsId, "WBS 수정 " + r.wbsNo, projectId);
        return one(wbsId);
    }

    private void shiftSubtree(Row r, long days, String userId) {
        if (r.leaf()) {
            jdbc.update("UPDATE PJ_WBS SET PLAN_START = ?, PLAN_END = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE WBS_ID = ?",
                    r.planStart == null ? null : r.planStart.plusDays(days),
                    r.planEnd == null ? null : r.planEnd.plusDays(days), Times.now(), userId, r.id);
            return;
        }
        for (Row c : r.children) {
            shiftSubtree(c, days, userId);
        }
    }

    public boolean isAssignee(String wbsId, String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM RS_ASSIGN WHERE TARGET_TYPE = 'WBS' AND TARGET_ID = ?"
                + " AND USER_ID = ?", Integer.class, wbsId, userId);
        return n != null && n > 0;
    }

    /** 산출물 승인 등 외부에서 완료 처리 */
    public void markDone(String wbsId, String userId) {
        LocalDate today = PjSupport.today();
        jdbc.update("""
                UPDATE PJ_WBS SET PROGRESS = 100, STATUS = 'DONE', ACT_START = COALESCE(ACT_START, ?),
                       ACT_END = COALESCE(ACT_END, ?), UPDATED_AT = ?, UPDATED_BY = ?
                 WHERE WBS_ID = ? AND NOT EXISTS (SELECT 1 FROM PJ_WBS C WHERE C.PARENT_ID = PJ_WBS.WBS_ID)""",
                today, today, Times.now(), userId, wbsId);
    }

    // ───────────────────────── 진척 ─────────────────────────

    public ProjectProgress progress(String projectId, LocalDate asOf) {
        WorkCalendar.Days cal = calendar.load();
        Tree t = tree(projectId, cal);
        LocalDate d = asOf == null ? PjSupport.today() : asOf;
        double planned = planned(t.leaves(), d, cal);
        double actual = actual(t.leaves());
        Double spi = planned <= 0 ? null : round2(actual / planned);
        List<PhaseProgress> byPhase = new ArrayList<>();
        List<Row> roots = new ArrayList<>(t.roots);
        roots.sort(Comparator.comparingInt((Row r) -> r.ord));
        for (Row r : roots) {
            List<Row> leaves = new ArrayList<>();
            collectLeaves(r, leaves, new HashSet<>());
            byPhase.add(new PhaseProgress(r.id, r.name, planned(leaves, d, cal), actual(leaves)));
        }
        return new ProjectProgress(d, planned, actual, spi, byPhase, curve(t, d, cal));
    }

    static void collectLeaves(Row r, List<Row> out, Set<String> guard) {
        if (!guard.add(r.id)) {
            return;
        }
        if (r.leaf()) {
            out.add(r);
            return;
        }
        for (Row c : r.children) {
            collectLeaves(c, out, guard);
        }
    }

    /** 계획 진척(%) = Σ 가중치 × 계획 기간 경과율(영업일) / Σ 가중치 (일정 있는 말단만) */
    public static double planned(List<Row> leaves, LocalDate d, WorkCalendar.Days cal) {
        double ws = 0;
        double v = 0;
        for (Row r : leaves) {
            if (r.planStart == null || r.planEnd == null) {
                continue;
            }
            double w = r.weight <= 0 ? 1 : r.weight;
            ws += w;
            v += w * plannedFraction(r, d, cal);
        }
        return ws == 0 ? 0 : round1(v / ws * 100);
    }

    static double plannedFraction(Row r, LocalDate d, WorkCalendar.Days cal) {
        if (d.isBefore(r.planStart)) {
            return 0;
        }
        if (!d.isBefore(r.planEnd)) {
            return 1;
        }
        int total = Math.max(1, cal.count(r.planStart, r.planEnd));
        return Math.min(1, cal.count(r.planStart, d) / (double) total);
    }

    /** 실적 진척(%) = Σ 가중치 × 진척률 / Σ 가중치 (말단 전체) */
    public static double actual(List<Row> leaves) {
        double ws = 0;
        double v = 0;
        for (Row r : leaves) {
            double w = r.weight <= 0 ? 1 : r.weight;
            ws += w;
            v += w * r.progress;
        }
        return ws == 0 ? 0 : round1(v / ws);
    }

    private List<CurvePoint> curve(Tree t, LocalDate asOf, WorkCalendar.Days cal) {
        List<Row> leaves = t.leaves();
        LocalDate start = leaves.stream().map(r -> r.planStart).filter(x -> x != null).min(LocalDate::compareTo)
                .orElse(null);
        LocalDate end = leaves.stream().map(r -> r.planEnd).filter(x -> x != null).max(LocalDate::compareTo)
                .orElse(null);
        if (start == null || end == null) {
            return List.of();
        }
        List<CurvePoint> out = new ArrayList<>();
        LocalDate x = start.with(java.time.DayOfWeek.FRIDAY);
        if (x.isBefore(start)) {
            x = x.plusWeeks(1);
        }
        int guard = 0;
        while (!x.isAfter(end.plusDays(6)) && guard++ < 400) {
            Double act = x.isAfter(asOf) ? null : actualAt(leaves, x, asOf, cal);
            out.add(new CurvePoint(x, planned(leaves, x, cal), act));
            x = x.plusWeeks(1);
        }
        return out;
    }

    /** 과거 시점 실적 추정: 완료 작업은 실적 종료일부터 100%, 진행 작업은 실적 시작일~기준일 사이 선형 증가 */
    private static double actualAt(List<Row> leaves, LocalDate d, LocalDate asOf, WorkCalendar.Days cal) {
        double ws = 0;
        double v = 0;
        for (Row r : leaves) {
            double w = r.weight <= 0 ? 1 : r.weight;
            ws += w;
            if (r.progress <= 0) {
                continue;
            }
            if (r.actEnd != null && !d.isBefore(r.actEnd)) {
                v += w * r.progress;
            } else if (r.actStart != null && !d.isBefore(r.actStart)) {
                LocalDate until = r.actEnd != null ? r.actEnd : asOf;
                int total = Math.max(1, cal.count(r.actStart, until));
                v += w * r.progress * Math.min(1, cal.count(r.actStart, d) / (double) total);
            }
        }
        return ws == 0 ? 0 : round1(v / ws);
    }

    // ───────────────────────── 유틸 ─────────────────────────

    static double clampProgress(double p) {
        if (Double.isNaN(p) || p < 0) {
            return 0;
        }
        return Math.min(100, round1(p));
    }

    static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }

    static BigDecimal bd1(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP);
    }

    static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a == null ? b : b == null ? a : a.isAfter(b) ? a : b;
    }
}
