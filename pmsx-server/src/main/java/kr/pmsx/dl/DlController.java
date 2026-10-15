package kr.pmsx.dl;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.pmsx.cm.notify.NotificationService;
import kr.pmsx.cm.notify.NotificationService.Event;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.pj.ProjectSeq;
import kr.pmsx.pj.wbs.WbsService;
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

/** 산출물 대장·승인, 요구사항·변경 이력, 감리 체크리스트 (FR-DL-001~003·006, docs/12 §5). */
@RestController
@RequestMapping("/api/v1")
public class DlController {

    public record Deliverable(String deliverableId, String phase, String name, UserRef owner, LocalDate dueDate,
                              String status, boolean customerApproval, String wbsId, int fileCount,
                              OffsetDateTime updatedAt) {
    }

    public record DeliverableRequest(String phase, String name, String ownerId, LocalDate dueDate,
                                     Boolean customerApproval, String wbsId) {
    }

    public record CommentRequest(String comment) {
    }

    public record HistRow(OffsetDateTime at, UserRef user, String action, String comment) {
    }

    public record Requirement(String reqId, String reqNo, String title, String description, String source,
                              String category, String priority, String status, OffsetDateTime updatedAt) {
    }

    public record ReqHist(OffsetDateTime at, UserRef user, String field, String before, String after) {
    }

    public record AuditItem(String itemId, String phase, String item, List<String> deliverableIds, String status,
                            String note) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final PjSupport support;
    private final ProjectSeq seq;
    private final WbsService wbs;
    private final NotificationService notify;
    private final AuditService audit;

    public DlController(JdbcTemplate jdbc, ProjectAccess access, PjSupport support, ProjectSeq seq, WbsService wbs,
            NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.support = support;
        this.seq = seq;
        this.wbs = wbs;
        this.notify = notify;
        this.audit = audit;
    }

    // ───────────────────────── 산출물 ─────────────────────────

    private static final String DELIV_SQL = """
            SELECT D.DELIVERABLE_ID, D.PHASE, D.NAME, D.OWNER_ID, U.NAME OWNER_NM, D.DUE_DT, D.STATUS, D.CUSTOMER_APPROVAL,
                   D.WBS_ID, D.UPDATED_AT,
                   (SELECT COUNT(*) FROM CM_ATTACH A WHERE A.TARGET_TYPE = 'DL_DELIVERABLE'
                       AND A.TARGET_ID = D.DELIVERABLE_ID AND A.DELETED = FALSE) FILE_CNT
              FROM DL_DELIVERABLE D LEFT JOIN CM_USER U ON U.USER_ID = D.OWNER_ID""";

    private Deliverable mapDeliverable(java.sql.ResultSet rs) throws java.sql.SQLException {
        String owner = rs.getString("OWNER_ID");
        return new Deliverable(rs.getString("DELIVERABLE_ID"), rs.getString("PHASE"), rs.getString("NAME"),
                owner == null ? null : new UserRef(owner, rs.getString("OWNER_NM")),
                rs.getObject("DUE_DT", LocalDate.class), rs.getString("STATUS"), rs.getBoolean("CUSTOMER_APPROVAL"),
                rs.getString("WBS_ID"), rs.getInt("FILE_CNT"), Times.read(rs, "UPDATED_AT"));
    }

    @GetMapping("/projects/{projectId}/deliverables")
    public List<Deliverable> deliverables(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String phase, @RequestParam(required = false) String status) {
        access.forProject(user, projectId);
        return deliverableList(projectId, phase, status);
    }

    public List<Deliverable> deliverableList(String projectId, String phase, String status) {
        StringBuilder sql = new StringBuilder(DELIV_SQL).append(" WHERE D.PROJECT_ID = ?");
        List<Object> args = new ArrayList<>(List.of(projectId));
        if (phase != null && !phase.isBlank()) {
            sql.append(" AND D.PHASE = ?");
            args.add(phase);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND D.STATUS = ?");
            args.add(status);
        }
        sql.append(" ORDER BY D.DUE_DT NULLS LAST, D.PHASE, D.NAME");
        return jdbc.query(sql.toString(), (rs, i) -> mapDeliverable(rs), args.toArray());
    }

    private Deliverable deliverable(String id) {
        return jdbc.query(DELIV_SQL + " WHERE D.DELIVERABLE_ID = ?", (rs, i) -> mapDeliverable(rs), id).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("산출물"));
    }

    private String projectOfDeliverable(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM DL_DELIVERABLE WHERE DELIVERABLE_ID = ?", (rs, i) -> rs.getString(1),
                id).stream().findFirst().orElseThrow(() -> ApiException.notFound("산출물"));
    }

    @PostMapping("/projects/{projectId}/deliverables")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Deliverable createDeliverable(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody DeliverableRequest req) {
        access.forProject(user, projectId).require(Perm.DELIVERABLE_EDIT);
        validateDeliverable(projectId, req);
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO DL_DELIVERABLE (DELIVERABLE_ID, PROJECT_ID, PHASE, NAME, OWNER_ID, DUE_DT, STATUS, CUSTOMER_APPROVAL,
                                            WBS_ID, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?, ?)""", id, projectId, req.phase().trim(),
                req.name().trim(), req.ownerId(), req.dueDate(), Boolean.TRUE.equals(req.customerApproval()),
                req.wbsId(), Times.now(), user.userId(), Times.now(), user.userId());
        if (req.wbsId() != null) {
            jdbc.update("UPDATE PJ_WBS SET DELIVERABLE_ID = COALESCE(DELIVERABLE_ID, ?) WHERE WBS_ID = ?", id,
                    req.wbsId());
        }
        support.hist("DL_DELIVERABLE", id, "CREATE", null, "DRAFT", null);
        audit.log(Action.CREATE, "deliverable:" + id, "산출물 등록 " + req.name(), projectId);
        return deliverable(id);
    }

    @PutMapping("/deliverables/{id}")
    @Transactional
    public Deliverable updateDeliverable(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody DeliverableRequest req) {
        String projectId = projectOfDeliverable(id);
        access.forProject(user, projectId).require(Perm.DELIVERABLE_EDIT);
        validateDeliverable(projectId, req);
        jdbc.update("""
                UPDATE DL_DELIVERABLE SET PHASE = ?, NAME = ?, OWNER_ID = ?, DUE_DT = ?, CUSTOMER_APPROVAL = ?, WBS_ID = ?,
                       UPDATED_AT = ?, UPDATED_BY = ? WHERE DELIVERABLE_ID = ?""", req.phase().trim(), req.name().trim(),
                req.ownerId(), req.dueDate(), Boolean.TRUE.equals(req.customerApproval()), req.wbsId(), Times.now(),
                user.userId(), id);
        support.hist("DL_DELIVERABLE", id, "UPDATE", null, null, null);
        audit.log(Action.UPDATE, "deliverable:" + id, "산출물 수정", projectId);
        return deliverable(id);
    }

    @DeleteMapping("/deliverables/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteDeliverable(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        String projectId = projectOfDeliverable(id);
        access.forProject(user, projectId).require(Set.of("PM", "PL"));
        jdbc.update("UPDATE PJ_WBS SET DELIVERABLE_ID = NULL WHERE DELIVERABLE_ID = ?", id);
        jdbc.update("DELETE FROM DL_TRACE_LINK WHERE (FROM_TYPE = 'DELIVERABLE' AND FROM_ID = ?)"
                + " OR (TO_TYPE = 'DELIVERABLE' AND TO_ID = ?)", id, id);
        jdbc.update("DELETE FROM DL_DELIVERABLE WHERE DELIVERABLE_ID = ?", id);
        audit.log(Action.DELETE, "deliverable:" + id, "산출물 삭제", projectId);
    }

    private void validateDeliverable(String projectId, DeliverableRequest req) {
        PjSupport.required("phase", req.phase(), 100);
        PjSupport.required("name", req.name(), 300);
        support.requireUser("ownerId", req.ownerId());
        if (req.wbsId() != null) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_WBS WHERE WBS_ID = ? AND PROJECT_ID = ?",
                    Integer.class, req.wbsId(), projectId);
            if (n == null || n == 0) {
                throw PjSupport.validation("wbsId", "이 프로젝트의 WBS 항목이 아닙니다.");
            }
        }
    }

    @PostMapping("/deliverables/{id}/request-review")
    @Transactional
    public Deliverable requestReview(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody(required = false) CommentRequest req) {
        String projectId = projectOfDeliverable(id);
        access.forProject(user, projectId).require(Perm.DELIVERABLE_EDIT);
        Deliverable d = transition(id, Set.of("DRAFT", "REJECTED"), "REVIEW", "REQUEST_REVIEW", comment(req), user);
        List<String> approvers = jdbc.queryForList("SELECT USER_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND ROLE_ID IN ('PM'"
                + (d.customerApproval() ? ", 'CUSTOMER'" : "") + ")", String.class, projectId);
        notify.notifyAll(approvers, Event.REVIEW_REQUESTED, "산출물 검토 요청: " + d.name(), comment(req),
                "/deliverables?focus=" + id);
        return d;
    }

    @PostMapping("/deliverables/{id}/approve")
    @Transactional
    public Deliverable approve(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody(required = false) CommentRequest req) {
        String projectId = projectOfDeliverable(id);
        var a = access.forProject(user, projectId);
        Deliverable cur = deliverable(id);
        // 고객 승인 단계가 설정된 산출물은 PM 또는 고객, 아니면 PM (docs/06 각주 4)
        a.require(cur.customerApproval() ? Perm.CR_APPROVE : Set.of("PM"));
        Deliverable d = transition(id, Set.of("REVIEW"), "APPROVED", "APPROVE", comment(req), user);
        if (d.wbsId() != null) {
            wbs.markDone(d.wbsId(), user.userId());
        }
        if (d.owner() != null) {
            notify.notify(d.owner().userId(), Event.APPROVED, "산출물이 승인되었습니다: " + d.name(), comment(req),
                    "/deliverables?focus=" + id);
        }
        return d;
    }

    @PostMapping("/deliverables/{id}/reject")
    @Transactional
    public Deliverable reject(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody(required = false) CommentRequest req) {
        String projectId = projectOfDeliverable(id);
        var a = access.forProject(user, projectId);
        a.require(deliverable(id).customerApproval() ? Perm.CR_APPROVE : Set.of("PM"));
        if (comment(req) == null) {
            throw PjSupport.validation("comment", "반려 사유를 입력해주세요.");
        }
        Deliverable d = transition(id, Set.of("REVIEW"), "REJECTED", "REJECT", comment(req), user);
        if (d.owner() != null) {
            notify.notify(d.owner().userId(), Event.REJECTED, "산출물이 반려되었습니다: " + d.name(), comment(req),
                    "/deliverables?focus=" + id);
        }
        return d;
    }

    private Deliverable transition(String id, Set<String> from, String to, String action, String comment,
            AuthUser user) {
        Deliverable d = deliverable(id);
        if (!from.contains(d.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "현재 상태(" + d.status() + ")에서는 처리할 수 없습니다.");
        }
        jdbc.update("UPDATE DL_DELIVERABLE SET STATUS = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE DELIVERABLE_ID = ?", to,
                Times.now(), user.userId(), id);
        support.hist("DL_DELIVERABLE", id, action, d.status(), to, comment);
        audit.log(Action.UPDATE, "deliverable:" + id, "산출물 " + action, projectOfDeliverable(id));
        return deliverable(id);
    }

    @GetMapping("/deliverables/{id}/history")
    public List<HistRow> deliverableHistory(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        access.forProject(user, projectOfDeliverable(id));
        return support.hist("DL_DELIVERABLE", id).stream()
                .map(h -> new HistRow(h.at(), h.user(), h.action(), h.comment())).toList();
    }

    private static String comment(CommentRequest req) {
        return req == null || req.comment() == null || req.comment().isBlank() ? null : req.comment().trim();
    }

    // ───────────────────────── 요구사항 ─────────────────────────

    private static final String REQ_COLS = "SELECT REQ_ID, REQ_NO, TITLE, DESCRIPTION, SOURCE, CATEGORY, PRIORITY,"
            + " STATUS, UPDATED_AT FROM DL_REQUIREMENT";

    private static Requirement mapReq(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Requirement(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getString(6), rs.getString(7), rs.getString(8), Times.read(rs, "UPDATED_AT"));
    }

    @GetMapping("/projects/{projectId}/requirements")
    public PageDto<Requirement> requirements(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String q, @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        access.forProject(user, projectId);
        int[] ps = PageDto.normalize(page, size);
        StringBuilder where = new StringBuilder(" WHERE PROJECT_ID = ?");
        List<Object> args = new ArrayList<>(List.of(projectId));
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(TITLE) LIKE ? OR LOWER(REQ_NO) LIKE ? OR LOWER(CATEGORY) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.addAll(List.of(like, like, like));
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND STATUS = ?");
            args.add(status);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM DL_REQUIREMENT" + where, Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(ps[1]);
        pa.add((long) ps[0] * ps[1]);
        return new PageDto<>(jdbc.query(REQ_COLS + where + " ORDER BY REQ_NO LIMIT ? OFFSET ?",
                (rs, i) -> mapReq(rs), pa.toArray()), ps[0], ps[1], total == null ? 0 : total);
    }

    @PostMapping("/projects/{projectId}/requirements")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Requirement createRequirement(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody Requirement req) {
        access.forProject(user, projectId).require(Perm.REQ_EDIT);
        return insertRequirement(projectId, req, user.userId());
    }

    public Requirement insertRequirement(String projectId, Requirement req, String userId) {
        String no = req.reqNo() == null || req.reqNo().isBlank() ? seq.next(projectId, "REQ")
                : PjSupport.required("reqNo", req.reqNo(), 30);
        String id = Ulid.next();
        try {
            jdbc.update("""
                    INSERT INTO DL_REQUIREMENT (REQ_ID, PROJECT_ID, REQ_NO, TITLE, DESCRIPTION, SOURCE, CATEGORY, PRIORITY,
                                                STATUS, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no,
                    PjSupport.required("title", req.title(), 300), req.description(),
                    PjSupport.optional("source", req.source(), 200), PjSupport.optional("category", req.category(), 100),
                    PjSupport.oneOf("priority", req.priority(), "M", "H", "M", "L"),
                    PjSupport.oneOf("status", req.status(), "PROPOSED", "PROPOSED", "ACCEPTED", "IMPLEMENTED",
                            "VERIFIED", "DROPPED"), Times.now(), userId, Times.now(), userId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 있는 요구사항 ID 입니다: " + no);
        }
        seq.bumpTo(projectId, "REQ", no);
        audit.log(Action.CREATE, "requirement:" + id, "요구사항 등록 " + no, projectId);
        return requirement(id);
    }

    private Requirement requirement(String id) {
        return jdbc.query(REQ_COLS + " WHERE REQ_ID = ?", (rs, i) -> mapReq(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("요구사항"));
    }

    private String projectOfReq(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM DL_REQUIREMENT WHERE REQ_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("요구사항"));
    }

    @GetMapping("/requirements/{reqId}")
    public Requirement getRequirement(@AuthenticationPrincipal AuthUser user, @PathVariable String reqId) {
        access.forProject(user, projectOfReq(reqId));
        return requirement(reqId);
    }

    @PutMapping("/requirements/{reqId}")
    @Transactional
    public Requirement updateRequirement(@AuthenticationPrincipal AuthUser user, @PathVariable String reqId,
            @RequestBody Requirement req) {
        String projectId = projectOfReq(reqId);
        access.forProject(user, projectId).require(Perm.REQ_EDIT);
        return updateRequirementRow(reqId, req, user.userId());
    }

    public Requirement updateRequirementRow(String reqId, Requirement req, String userId) {
        Requirement cur = requirement(reqId);
        Requirement next = new Requirement(reqId, cur.reqNo(), PjSupport.required("title", req.title(), 300),
                req.description(), PjSupport.optional("source", req.source(), 200),
                PjSupport.optional("category", req.category(), 100),
                PjSupport.oneOf("priority", req.priority(), cur.priority(), "H", "M", "L"),
                PjSupport.oneOf("status", req.status(), cur.status(), "PROPOSED", "ACCEPTED", "IMPLEMENTED", "VERIFIED",
                        "DROPPED"), null);
        Map<String, String[]> changes = new LinkedHashMap<>();
        diff(changes, "title", cur.title(), next.title());
        diff(changes, "description", cur.description(), next.description());
        diff(changes, "source", cur.source(), next.source());
        diff(changes, "category", cur.category(), next.category());
        diff(changes, "priority", cur.priority(), next.priority());
        diff(changes, "status", cur.status(), next.status());
        if (changes.isEmpty()) {
            return cur;
        }
        jdbc.update("""
                UPDATE DL_REQUIREMENT SET TITLE = ?, DESCRIPTION = ?, SOURCE = ?, CATEGORY = ?, PRIORITY = ?, STATUS = ?,
                       UPDATED_AT = ?, UPDATED_BY = ? WHERE REQ_ID = ?""", next.title(), next.description(),
                next.source(), next.category(), next.priority(), next.status(), Times.now(), userId, reqId);
        changes.forEach((f, v) -> jdbc.update("""
                INSERT INTO DL_REQUIREMENT_HIST (HIST_ID, REQ_ID, HIST_AT, USER_ID, FIELD_NM, BEFORE_VAL, AFTER_VAL)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", Ulid.next(), reqId, Times.now(), userId, f, v[0], v[1]));
        audit.log(Action.UPDATE, "requirement:" + reqId, "요구사항 수정 " + String.join(",", changes.keySet()),
                projectOfReq(reqId));
        return requirement(reqId);
    }

    private static void diff(Map<String, String[]> out, String field, String a, String b) {
        if (!Objects.equals(a, b)) {
            out.put(field, new String[] {a, b});
        }
    }

    @DeleteMapping("/requirements/{reqId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteRequirement(@AuthenticationPrincipal AuthUser user, @PathVariable String reqId) {
        String projectId = projectOfReq(reqId);
        access.forProject(user, projectId).require(Set.of("PM", "PL"));
        jdbc.update("DELETE FROM DL_TRACE_LINK WHERE (FROM_TYPE = 'REQ' AND FROM_ID = ?) OR (TO_TYPE = 'REQ' AND TO_ID = ?)",
                reqId, reqId);
        jdbc.update("DELETE FROM DL_REQUIREMENT_HIST WHERE REQ_ID = ?", reqId);
        jdbc.update("DELETE FROM DL_REQUIREMENT WHERE REQ_ID = ?", reqId);
        audit.log(Action.DELETE, "requirement:" + reqId, "요구사항 삭제", projectId);
    }

    @GetMapping("/requirements/{reqId}/history")
    public List<ReqHist> requirementHistory(@AuthenticationPrincipal AuthUser user, @PathVariable String reqId) {
        access.forProject(user, projectOfReq(reqId));
        List<Object[]> rows = jdbc.query("SELECT HIST_AT, USER_ID, FIELD_NM, BEFORE_VAL, AFTER_VAL FROM DL_REQUIREMENT_HIST"
                + " WHERE REQ_ID = ? ORDER BY HIST_AT, HIST_ID", (rs, i) -> new Object[] {Times.read(rs, "HIST_AT"),
                rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)}, reqId);
        Map<String, String> names = support.names(rows.stream().map(r -> (String) r[1]).toList());
        return rows.stream().map(r -> new ReqHist((OffsetDateTime) r[0], PjSupport.ref(names, (String) r[1]),
                (String) r[2], (String) r[3], (String) r[4])).toList();
    }

    // ───────────────────────── 감리 체크리스트 ─────────────────────────

    @GetMapping("/projects/{projectId}/audit-checklist")
    @Transactional
    public List<AuditItem> auditChecklist(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM DL_AUDIT_CHECK WHERE PROJECT_ID = ?", Integer.class,
                projectId);
        if (n == null || n == 0) {
            jdbc.query("SELECT NAME, ATTR1, SORT_ORD FROM CM_CODE WHERE GROUP_CODE = 'AUDIT_ITEM' AND USE_YN = TRUE"
                    + " ORDER BY SORT_ORD", rs -> {
                jdbc.update("INSERT INTO DL_AUDIT_CHECK (ITEM_ID, PROJECT_ID, PHASE, ITEM, STATUS, ORD)"
                        + " VALUES (?, ?, ?, ?, 'NOT_READY', ?)", Ulid.next(), projectId,
                        rs.getString(2) == null ? "공통" : rs.getString(2), rs.getString(1), rs.getInt(3));
            });
        }
        return auditItems(projectId);
    }

    private List<AuditItem> auditItems(String projectId) {
        return jdbc.query("SELECT ITEM_ID, PHASE, ITEM, DELIVERABLE_IDS, STATUS, NOTE FROM DL_AUDIT_CHECK"
                + " WHERE PROJECT_ID = ? ORDER BY ORD, ITEM", (rs, i) -> new AuditItem(rs.getString(1), rs.getString(2),
                rs.getString(3), PjSupport.csv(rs.getString(4)), rs.getString(5), rs.getString(6)), projectId);
    }

    @PutMapping("/projects/{projectId}/audit-checklist")
    @Transactional
    public List<AuditItem> saveAuditChecklist(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody List<AuditItem> items) {
        access.forProject(user, projectId).require(Perm.DELIVERABLE_EDIT);
        jdbc.update("DELETE FROM DL_AUDIT_CHECK WHERE PROJECT_ID = ?", projectId);
        int ord = 0;
        for (AuditItem it : items) {
            for (String d : it.deliverableIds() == null ? List.<String>of() : it.deliverableIds()) {
                Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM DL_DELIVERABLE WHERE DELIVERABLE_ID = ?"
                        + " AND PROJECT_ID = ?", Integer.class, d, projectId);
                if (n == null || n == 0) {
                    throw PjSupport.validation("deliverableIds", "이 프로젝트의 산출물이 아닙니다: " + d);
                }
            }
            jdbc.update("""
                    INSERT INTO DL_AUDIT_CHECK (ITEM_ID, PROJECT_ID, PHASE, ITEM, DELIVERABLE_IDS, STATUS, NOTE, ORD)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", it.itemId() == null || it.itemId().isBlank() ? Ulid.next()
                    : it.itemId(), projectId, PjSupport.required("phase", it.phase(), 100),
                    PjSupport.required("item", it.item(), 500), PjSupport.csv(it.deliverableIds()),
                    PjSupport.oneOf("status", it.status(), "NOT_READY", "READY", "NOT_READY", "NA"),
                    PjSupport.optional("note", it.note(), 1000), ord++);
        }
        audit.log(Action.UPDATE, "audit-checklist:" + projectId, "감리 체크리스트 저장 " + items.size() + "건", projectId);
        return auditItems(projectId);
    }
}
