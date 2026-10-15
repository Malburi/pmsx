package kr.pmsx.qa;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import kr.pmsx.qa.QaSupport.Comment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 이슈·리스크·의사결정·회의록·액션아이템 (FR-QA-001~003·010, docs/12 §6). */
@RestController
@RequestMapping("/api/v1")
public class IssueController {

    public record Issue(String issueId, String issueNo, String title, String description, String priority,
                        String status, UserRef owner, LocalDate dueDate, int escalation, String elementId,
                        OffsetDateTime createdAt, OffsetDateTime updatedAt, List<Comment> comments) {
    }

    public record IssueRequest(String title, String description, String priority, String status, String ownerId,
                               LocalDate dueDate, String elementId) {
    }

    public record CommentRequest(String content) {
    }

    public record Risk(String riskId, String riskNo, String title, String description, int probability, int impact,
                       int score, String response, String status, UserRef owner, LocalDate dueDate) {
    }

    /** owner 객체 또는 ownerId 둘 다 받는다 */
    public record RiskRequest(String title, String description, int probability, int impact, String response,
                              String status, String ownerId, UserRef owner, LocalDate dueDate) {
        public String ownerUserId() {
            return ownerId != null && !ownerId.isBlank() ? ownerId : owner == null ? null : owner.userId();
        }
    }

    public record ActionItemRequest(String itemId, String content, String ownerId, UserRef owner, LocalDate dueDate,
                                    boolean done) {
        public String ownerUserId() {
            return ownerId != null && !ownerId.isBlank() ? ownerId : owner == null ? null : owner.userId();
        }
    }

    public record MeetingRequest(String title, OffsetDateTime at, String attendees, String content,
                                 List<ActionItemRequest> actionItems) {
    }

    public record Decision(String decisionId, String title, String content, String rationale, String decidedBy,
                           LocalDate decidedAt) {
    }

    public record ActionItem(String itemId, String content, UserRef owner, LocalDate dueDate, boolean done) {
    }

    public record Meeting(String meetingId, String meetingNo, String title, OffsetDateTime at, String attendees,
                          String content, List<ActionItem> actionItems) {
    }

    public record OpenActionItem(String itemId, String meetingId, String meetingNo, String meetingTitle,
                                 String content, UserRef owner, LocalDate dueDate, boolean done) {
    }

    public record ActionPatch(Boolean done, String ownerId, LocalDate dueDate) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final PjSupport support;
    private final ProjectSeq seq;
    private final QaSupport qa;
    private final NotificationService notify;
    private final AuditService audit;

    public IssueController(JdbcTemplate jdbc, ProjectAccess access, PjSupport support, ProjectSeq seq, QaSupport qa,
            NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.support = support;
        this.seq = seq;
        this.qa = qa;
        this.notify = notify;
        this.audit = audit;
    }

    // ───────────────────────── 이슈 ─────────────────────────

    private static final String ISSUE_COLS = """
            SELECT I.ISSUE_ID, I.ISSUE_NO, I.TITLE, I.DESCRIPTION, I.PRIORITY, I.STATUS, I.OWNER_ID, U.NAME OWNER_NM,
                   I.DUE_DT, I.ESCALATION, I.ELEMENT_ID, I.CREATED_AT, I.UPDATED_AT
              FROM QA_ISSUE I LEFT JOIN CM_USER U ON U.USER_ID = I.OWNER_ID""";

    private static Issue mapIssue(java.sql.ResultSet rs, List<Comment> comments) throws java.sql.SQLException {
        String o = rs.getString("OWNER_ID");
        return new Issue(rs.getString("ISSUE_ID"), rs.getString("ISSUE_NO"), rs.getString("TITLE"),
                rs.getString("DESCRIPTION"), rs.getString("PRIORITY"), rs.getString("STATUS"),
                o == null ? null : new UserRef(o, rs.getString("OWNER_NM")), rs.getObject("DUE_DT", LocalDate.class),
                rs.getInt("ESCALATION"), rs.getString("ELEMENT_ID"), Times.read(rs, "CREATED_AT"),
                Times.read(rs, "UPDATED_AT"), comments);
    }

    @GetMapping("/projects/{projectId}/issues")
    public PageDto<Issue> issues(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        access.forProject(user, projectId);
        int[] ps = PageDto.normalize(page, size);
        StringBuilder where = new StringBuilder(" WHERE I.PROJECT_ID = ?");
        List<Object> args = new ArrayList<>(List.of(projectId));
        if (status != null && !status.isBlank()) {
            List<String> st = PjSupport.csv(status);
            where.append(" AND I.STATUS IN (").append(String.join(",", st.stream().map(x -> "?").toList())).append(")");
            args.addAll(st);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (LOWER(I.TITLE) LIKE ? OR LOWER(I.ISSUE_NO) LIKE ?)");
            String like = "%" + q.trim().toLowerCase() + "%";
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM QA_ISSUE I" + where, Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(ps[1]);
        pa.add((long) ps[0] * ps[1]);
        return new PageDto<>(jdbc.query(ISSUE_COLS + where + " ORDER BY I.ISSUE_NO DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapIssue(rs, null), pa.toArray()), ps[0], ps[1], total == null ? 0 : total);
    }

    @PostMapping("/projects/{projectId}/issues")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Issue createIssue(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody IssueRequest req) {
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        return insertIssue(projectId, req, null, user);
    }

    public Issue insertIssue(String projectId, IssueRequest req, String riskId, AuthUser user) {
        validateIssue(projectId, req);
        String id = Ulid.next();
        String no = seq.next(projectId, "ISS");
        jdbc.update("""
                INSERT INTO QA_ISSUE (ISSUE_ID, PROJECT_ID, ISSUE_NO, TITLE, DESCRIPTION, PRIORITY, STATUS, OWNER_ID, DUE_DT,
                                      ELEMENT_ID, RISK_ID, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no, req.title().trim(),
                req.description(), PjSupport.oneOf("priority", req.priority(), "M", "H", "M", "L"),
                PjSupport.oneOf("status", req.status(), "OPEN", "OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"),
                req.ownerId(), req.dueDate(), req.elementId(), riskId, Times.now(), user.userId(), Times.now(),
                user.userId());
        if (req.ownerId() != null && !req.ownerId().equals(user.userId())) {
            notify.notify(req.ownerId(), Event.ASSIGNED, "이슈 담당: " + no + " " + req.title(), null,
                    "/quality/issues/" + id);
        }
        audit.log(Action.CREATE, "issue:" + id, "이슈 등록 " + no, projectId);
        return issue(id);
    }

    private void validateIssue(String projectId, IssueRequest req) {
        PjSupport.required("title", req.title(), 300);
        qa.requireMember("ownerId", projectId, req.ownerId());
        if (req.elementId() != null && !projectId.equals(qa.element(req.elementId())[1])) {
            throw PjSupport.validation("elementId", "이 프로젝트의 화면 요소가 아닙니다.");
        }
    }

    public Issue issue(String id) {
        List<Comment> comments = qa.comments("ISSUE", id);
        return jdbc.query(ISSUE_COLS + " WHERE I.ISSUE_ID = ?", (rs, i) -> mapIssue(rs, comments), id).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("이슈"));
    }

    private String[] issueMeta(String id) {
        return jdbc.query("SELECT PROJECT_ID, CREATED_BY, OWNER_ID FROM QA_ISSUE WHERE ISSUE_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("이슈"));
    }

    @GetMapping("/issues/{issueId}")
    public Issue getIssue(@AuthenticationPrincipal AuthUser user, @PathVariable String issueId) {
        access.forProject(user, issueMeta(issueId)[0]);
        return issue(issueId);
    }

    @PutMapping("/issues/{issueId}")
    @Transactional
    public Issue updateIssue(@AuthenticationPrincipal AuthUser user, @PathVariable String issueId,
            @RequestBody IssueRequest req) {
        String[] meta = issueMeta(issueId);
        var a = access.forProject(user, meta[0]);
        a.require(Perm.ISSUE_EDIT);
        QaSupport.requireOwnIfCustomer(a, user, meta[1]);
        validateIssue(meta[0], req);
        Issue cur = issue(issueId);
        String status = PjSupport.oneOf("status", req.status(), cur.status(), "OPEN", "IN_PROGRESS", "RESOLVED",
                "CLOSED");
        jdbc.update("""
                UPDATE QA_ISSUE SET TITLE = ?, DESCRIPTION = ?, PRIORITY = ?, STATUS = ?, OWNER_ID = ?, DUE_DT = ?,
                       ELEMENT_ID = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE ISSUE_ID = ?""", req.title().trim(),
                req.description(), PjSupport.oneOf("priority", req.priority(), cur.priority(), "H", "M", "L"), status,
                req.ownerId(), req.dueDate(), req.elementId(), Times.now(), user.userId(), issueId);
        if (!status.equals(cur.status())) {
            support.hist("ISSUE", issueId, "STATUS", cur.status(), status, null);
        }
        if (req.ownerId() != null && !req.ownerId().equals(meta[2]) && !req.ownerId().equals(user.userId())) {
            notify.notify(req.ownerId(), Event.ASSIGNED, "이슈 담당: " + cur.issueNo() + " " + req.title(), null,
                    "/quality/issues/" + issueId);
        }
        audit.log(Action.UPDATE, "issue:" + issueId, "이슈 수정 " + cur.issueNo(), meta[0]);
        return issue(issueId);
    }

    @DeleteMapping("/issues/{issueId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteIssue(@AuthenticationPrincipal AuthUser user, @PathVariable String issueId) {
        String[] meta = issueMeta(issueId);
        access.forProject(user, meta[0]).require(Set.of("PM"));
        jdbc.update("DELETE FROM QA_COMMENT WHERE TARGET_TYPE = 'ISSUE' AND TARGET_ID = ?", issueId);
        jdbc.update("DELETE FROM DL_TRACE_LINK WHERE (FROM_TYPE = 'ISSUE' AND FROM_ID = ?) OR (TO_TYPE = 'ISSUE' AND TO_ID = ?)",
                issueId, issueId);
        jdbc.update("DELETE FROM QA_ISSUE WHERE ISSUE_ID = ?", issueId);
        audit.log(Action.DELETE, "issue:" + issueId, "이슈 삭제", meta[0]);
    }

    @PostMapping("/issues/{issueId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Comment comment(@AuthenticationPrincipal AuthUser user, @PathVariable String issueId,
            @RequestBody CommentRequest req) {
        String[] meta = issueMeta(issueId);
        access.forProject(user, meta[0]).require(Perm.ISSUE_EDIT);
        Issue i = issue(issueId);
        Comment c = qa.addComment(meta[0], "ISSUE", issueId, req.content(), "/quality/issues/" + issueId,
                i.issueNo() + " " + i.title(), user);
        jdbc.update("UPDATE QA_ISSUE SET UPDATED_AT = ? WHERE ISSUE_ID = ?", Times.now(), issueId);
        return c;
    }

    @PostMapping("/issues/{issueId}/escalate")
    @Transactional
    public Issue escalate(@AuthenticationPrincipal AuthUser user, @PathVariable String issueId) {
        String[] meta = issueMeta(issueId);
        access.forProject(user, meta[0]).require(Perm.ISSUE_EDIT);
        Issue cur = issue(issueId);
        if (cur.escalation() >= 2) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 최고 단계로 에스컬레이션되었습니다.");
        }
        jdbc.update("UPDATE QA_ISSUE SET ESCALATION = ESCALATION + 1, UPDATED_AT = ?, UPDATED_BY = ? WHERE ISSUE_ID = ?",
                Times.now(), user.userId(), issueId);
        support.hist("ISSUE", issueId, "ESCALATE", String.valueOf(cur.escalation()),
                String.valueOf(cur.escalation() + 1), null);
        List<String> to = jdbc.queryForList("SELECT USER_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND ROLE_ID = 'PM'",
                String.class, meta[0]);
        if (cur.escalation() + 1 >= 2) {
            to = new ArrayList<>(to);
            to.addAll(jdbc.queryForList("SELECT USER_ID FROM CM_USER_ROLE WHERE ROLE_ID = 'PMO'", String.class));
        }
        notify.notifyAll(to, Event.ISSUE_ESCALATED, "이슈 에스컬레이션(" + (cur.escalation() + 1) + "단계): "
                + cur.issueNo() + " " + cur.title(), null, "/quality/issues/" + issueId);
        audit.log(Action.UPDATE, "issue:" + issueId, "에스컬레이션 " + (cur.escalation() + 1), meta[0]);
        return issue(issueId);
    }

    // ───────────────────────── 리스크 ─────────────────────────

    private static final String RISK_COLS = """
            SELECT R.RISK_ID, R.RISK_NO, R.TITLE, R.DESCRIPTION, R.PROBABILITY, R.IMPACT, R.RESPONSE, R.STATUS, R.OWNER_ID,
                   U.NAME OWNER_NM, R.DUE_DT
              FROM QA_RISK R LEFT JOIN CM_USER U ON U.USER_ID = R.OWNER_ID""";

    private static Risk mapRisk(java.sql.ResultSet rs) throws java.sql.SQLException {
        String o = rs.getString("OWNER_ID");
        int p = rs.getInt("PROBABILITY");
        int im = rs.getInt("IMPACT");
        return new Risk(rs.getString("RISK_ID"), rs.getString("RISK_NO"), rs.getString("TITLE"),
                rs.getString("DESCRIPTION"), p, im, p * im, rs.getString("RESPONSE"), rs.getString("STATUS"),
                o == null ? null : new UserRef(o, rs.getString("OWNER_NM")), rs.getObject("DUE_DT", LocalDate.class));
    }

    @GetMapping("/projects/{projectId}/risks")
    public List<Risk> risks(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return riskList(projectId);
    }

    public List<Risk> riskList(String projectId) {
        return jdbc.query(RISK_COLS + " WHERE R.PROJECT_ID = ? ORDER BY R.PROBABILITY * R.IMPACT DESC, R.RISK_NO",
                (rs, i) -> mapRisk(rs), projectId);
    }

    private Risk risk(String id) {
        return jdbc.query(RISK_COLS + " WHERE R.RISK_ID = ?", (rs, i) -> mapRisk(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("리스크"));
    }

    private String[] riskMeta(String id) {
        return jdbc.query("SELECT PROJECT_ID, CREATED_BY FROM QA_RISK WHERE RISK_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("리스크"));
    }

    @PostMapping("/projects/{projectId}/risks")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Risk createRisk(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody RiskRequest req) {
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        validateRisk(projectId, req);
        String id = Ulid.next();
        String no = seq.next(projectId, "RSK");
        jdbc.update("""
                INSERT INTO QA_RISK (RISK_ID, PROJECT_ID, RISK_NO, TITLE, DESCRIPTION, PROBABILITY, IMPACT, RESPONSE, STATUS,
                                     OWNER_ID, DUE_DT, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no, req.title().trim(),
                req.description(), req.probability(), req.impact(), req.response(),
                PjSupport.oneOf("status", req.status(), "IDENTIFIED", "IDENTIFIED", "MITIGATING", "OCCURRED", "CLOSED"),
                req.ownerUserId(), req.dueDate(), Times.now(), user.userId(), Times.now(), user.userId());
        audit.log(Action.CREATE, "risk:" + id, "리스크 등록 " + no, projectId);
        return risk(id);
    }

    private void validateRisk(String projectId, RiskRequest req) {
        PjSupport.required("title", req.title(), 300);
        if (req.probability() < 1 || req.probability() > 5) {
            throw PjSupport.validation("probability", "1~5 입니다.");
        }
        if (req.impact() < 1 || req.impact() > 5) {
            throw PjSupport.validation("impact", "1~5 입니다.");
        }
        qa.requireMember("ownerId", projectId, req.ownerUserId());
    }

    @PutMapping("/risks/{riskId}")
    @Transactional
    public Risk updateRisk(@AuthenticationPrincipal AuthUser user, @PathVariable String riskId, @RequestBody RiskRequest req) {
        String[] meta = riskMeta(riskId);
        var a = access.forProject(user, meta[0]);
        a.require(Perm.ISSUE_EDIT);
        QaSupport.requireOwnIfCustomer(a, user, meta[1]);
        validateRisk(meta[0], req);
        Risk cur = risk(riskId);
        jdbc.update("""
                UPDATE QA_RISK SET TITLE = ?, DESCRIPTION = ?, PROBABILITY = ?, IMPACT = ?, RESPONSE = ?, STATUS = ?,
                       OWNER_ID = ?, DUE_DT = ?, UPDATED_AT = ?, UPDATED_BY = ? WHERE RISK_ID = ?""", req.title().trim(),
                req.description(), req.probability(), req.impact(), req.response(),
                PjSupport.oneOf("status", req.status(), cur.status(), "IDENTIFIED", "MITIGATING", "OCCURRED", "CLOSED"),
                req.ownerUserId(), req.dueDate(), Times.now(), user.userId(), riskId);
        audit.log(Action.UPDATE, "risk:" + riskId, "리스크 수정 " + cur.riskNo(), meta[0]);
        return risk(riskId);
    }

    @DeleteMapping("/risks/{riskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRisk(@AuthenticationPrincipal AuthUser user, @PathVariable String riskId) {
        String[] meta = riskMeta(riskId);
        access.forProject(user, meta[0]).require(Set.of("PM"));
        jdbc.update("DELETE FROM QA_RISK WHERE RISK_ID = ?", riskId);
        audit.log(Action.DELETE, "risk:" + riskId, "리스크 삭제", meta[0]);
    }

    @PostMapping("/risks/{riskId}/to-issue")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Issue riskToIssue(@AuthenticationPrincipal AuthUser user, @PathVariable String riskId) {
        String[] meta = riskMeta(riskId);
        access.forProject(user, meta[0]).require(Perm.ISSUE_EDIT);
        Risk r = risk(riskId);
        if ("OCCURRED".equals(r.status()) || "CLOSED".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "이미 발생·종료된 리스크입니다.");
        }
        Issue i = insertIssue(meta[0], new IssueRequest("[리스크 발생] " + r.title(),
                (r.description() == null ? "" : r.description()) + (r.response() == null ? ""
                        : "\n\n대응 계획: " + r.response()), r.score() >= 15 ? "H" : r.score() >= 8 ? "M" : "L", "OPEN",
                r.owner() == null ? null : r.owner().userId(), r.dueDate(), null), riskId, user);
        jdbc.update("UPDATE QA_RISK SET STATUS = 'OCCURRED', UPDATED_AT = ?, UPDATED_BY = ? WHERE RISK_ID = ?",
                Times.now(), user.userId(), riskId);
        support.hist("RISK", riskId, "TO_ISSUE", r.status(), "OCCURRED", i.issueNo());
        return i;
    }

    // ───────────────────────── 의사결정 ─────────────────────────

    @GetMapping("/projects/{projectId}/decisions")
    public List<Decision> decisions(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.query("SELECT DECISION_ID, TITLE, CONTENT, RATIONALE, DECIDED_BY, DECIDED_AT FROM QA_DECISION"
                + " WHERE PROJECT_ID = ? ORDER BY DECIDED_AT DESC NULLS LAST, CREATED_AT DESC", (rs, i) -> new Decision(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                rs.getObject(6, LocalDate.class)), projectId);
    }

    @PostMapping("/projects/{projectId}/decisions")
    @ResponseStatus(HttpStatus.CREATED)
    public Decision createDecision(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody Decision req) {
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO QA_DECISION (DECISION_ID, PROJECT_ID, TITLE, CONTENT, RATIONALE, DECIDED_BY, DECIDED_AT,
                                         CREATED_AT, CREATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, PjSupport.required("title", req.title(), 300),
                req.content(), req.rationale(), PjSupport.optional("decidedBy", req.decidedBy(), 100),
                req.decidedAt() == null ? PjSupport.today() : req.decidedAt(), Times.now(), user.userId());
        audit.log(Action.CREATE, "decision:" + id, "의사결정 등록", projectId);
        return decisions(user, projectId).stream().filter(d -> d.decisionId().equals(id)).findFirst().orElseThrow();
    }

    @PutMapping("/decisions/{id}")
    public Decision updateDecision(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody Decision req) {
        String projectId = projectOf("QA_DECISION", "DECISION_ID", id, "의사결정");
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        jdbc.update("UPDATE QA_DECISION SET TITLE = ?, CONTENT = ?, RATIONALE = ?, DECIDED_BY = ?, DECIDED_AT = ?"
                        + " WHERE DECISION_ID = ?", PjSupport.required("title", req.title(), 300), req.content(),
                req.rationale(), PjSupport.optional("decidedBy", req.decidedBy(), 100), req.decidedAt(), id);
        audit.log(Action.UPDATE, "decision:" + id, "의사결정 수정", projectId);
        return decisions(user, projectId).stream().filter(d -> d.decisionId().equals(id)).findFirst().orElseThrow();
    }

    @DeleteMapping("/decisions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDecision(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        String projectId = projectOf("QA_DECISION", "DECISION_ID", id, "의사결정");
        access.forProject(user, projectId).require(Set.of("PM", "PL"));
        jdbc.update("DELETE FROM QA_DECISION WHERE DECISION_ID = ?", id);
        audit.log(Action.DELETE, "decision:" + id, "의사결정 삭제", projectId);
    }

    private String projectOf(String table, String idCol, String id, String what) {
        return jdbc.query("SELECT PROJECT_ID FROM " + table + " WHERE " + idCol + " = ?", (rs, i) -> rs.getString(1),
                id).stream().findFirst().orElseThrow(() -> ApiException.notFound(what));
    }

    // ───────────────────────── 회의록·액션아이템 ─────────────────────────

    @GetMapping("/projects/{projectId}/meetings")
    public List<Meeting> meetings(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return jdbc.queryForList("SELECT MEETING_ID FROM QA_MEETING WHERE PROJECT_ID = ? ORDER BY MEETING_AT DESC",
                String.class, projectId).stream().map(this::meeting).toList();
    }

    @GetMapping("/meetings/{id}")
    public Meeting getMeeting(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        access.forProject(user, projectOf("QA_MEETING", "MEETING_ID", id, "회의록"));
        return meeting(id);
    }

    private Meeting meeting(String id) {
        List<Object[]> items = jdbc.query("SELECT ITEM_ID, CONTENT, OWNER_ID, DUE_DT, DONE FROM QA_ACTION_ITEM"
                + " WHERE MEETING_ID = ? ORDER BY ORD", (rs, i) -> new Object[] {rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getObject(4, LocalDate.class), rs.getBoolean(5)}, id);
        var names = support.names(items.stream().map(r -> (String) r[2]).toList());
        List<ActionItem> ai = items.stream().map(r -> new ActionItem((String) r[0], (String) r[1],
                PjSupport.ref(names, (String) r[2]), (LocalDate) r[3], (Boolean) r[4])).toList();
        return jdbc.query("SELECT MEETING_ID, MEETING_NO, TITLE, MEETING_AT, ATTENDEES, CONTENT FROM QA_MEETING"
                + " WHERE MEETING_ID = ?", (rs, i) -> new Meeting(rs.getString(1), rs.getString(2), rs.getString(3),
                Times.read(rs, "MEETING_AT"), rs.getString(5), rs.getString(6), ai), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("회의록"));
    }

    @PostMapping("/projects/{projectId}/meetings")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Meeting createMeeting(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody MeetingRequest req) {
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        String id = Ulid.next();
        jdbc.update("""
                INSERT INTO QA_MEETING (MEETING_ID, PROJECT_ID, MEETING_NO, TITLE, MEETING_AT, ATTENDEES, CONTENT, CREATED_AT,
                                        CREATED_BY, UPDATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, seq.next(projectId, "MTG"),
                PjSupport.required("title", req.title(), 300), req.at() == null ? Times.now() : req.at(),
                PjSupport.optional("attendees", req.attendees(), 1000), req.content(), Times.now(), user.userId(),
                Times.now());
        saveItems(projectId, id, req.actionItems(), List.of(), user);
        audit.log(Action.CREATE, "meeting:" + id, "회의록 등록", projectId);
        return meeting(id);
    }

    @PutMapping("/meetings/{id}")
    @Transactional
    public Meeting updateMeeting(@AuthenticationPrincipal AuthUser user, @PathVariable String id,
            @RequestBody MeetingRequest req) {
        String projectId = projectOf("QA_MEETING", "MEETING_ID", id, "회의록");
        access.forProject(user, projectId).require(Perm.ISSUE_EDIT);
        jdbc.update("UPDATE QA_MEETING SET TITLE = ?, MEETING_AT = ?, ATTENDEES = ?, CONTENT = ?, UPDATED_AT = ?"
                        + " WHERE MEETING_ID = ?", PjSupport.required("title", req.title(), 300),
                req.at() == null ? Times.now() : req.at(), PjSupport.optional("attendees", req.attendees(), 1000),
                req.content(), Times.now(), id);
        List<ActionItem> prev = meeting(id).actionItems();
        jdbc.update("DELETE FROM QA_ACTION_ITEM WHERE MEETING_ID = ?", id);
        saveItems(projectId, id, req.actionItems(), prev, user);
        audit.log(Action.UPDATE, "meeting:" + id, "회의록 수정", projectId);
        return meeting(id);
    }

    private void saveItems(String projectId, String meetingId, List<ActionItemRequest> items, List<ActionItem> prev,
            AuthUser user) {
        int ord = 0;
        for (ActionItemRequest it : items == null ? List.<ActionItemRequest>of() : items) {
            String owner = it.ownerUserId();
            qa.requireMember("actionItems.owner", projectId, owner);
            String itemId = it.itemId() == null || it.itemId().isBlank() ? Ulid.next() : it.itemId();
            jdbc.update("""
                    INSERT INTO QA_ACTION_ITEM (ITEM_ID, MEETING_ID, PROJECT_ID, CONTENT, OWNER_ID, DUE_DT, DONE, ORD)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", itemId, meetingId, projectId,
                    PjSupport.required("actionItems.content", it.content(), 1000), owner, it.dueDate(), it.done(), ord++);
            boolean wasOwner = prev.stream().anyMatch(p -> p.itemId().equals(itemId) && p.owner() != null
                    && p.owner().userId().equals(owner));
            if (owner != null && !wasOwner && !owner.equals(user.userId())) {
                notify.notify(owner, Event.ASSIGNED, "액션아이템 배정: " + it.content(), null,
                        "/quality/meetings/" + meetingId);
            }
        }
    }

    @DeleteMapping("/meetings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteMeeting(@AuthenticationPrincipal AuthUser user, @PathVariable String id) {
        String projectId = projectOf("QA_MEETING", "MEETING_ID", id, "회의록");
        access.forProject(user, projectId).require(Set.of("PM", "PL"));
        jdbc.update("DELETE FROM QA_ACTION_ITEM WHERE MEETING_ID = ?", id);
        jdbc.update("DELETE FROM QA_MEETING WHERE MEETING_ID = ?", id);
        audit.log(Action.DELETE, "meeting:" + id, "회의록 삭제", projectId);
    }

    @GetMapping("/projects/{projectId}/action-items")
    public List<OpenActionItem> actionItems(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) Boolean open) {
        access.forProject(user, projectId);
        List<Object[]> rows = jdbc.query("""
                SELECT A.ITEM_ID, A.MEETING_ID, M.MEETING_NO, M.TITLE, A.CONTENT, A.OWNER_ID, A.DUE_DT, A.DONE
                  FROM QA_ACTION_ITEM A JOIN QA_MEETING M ON M.MEETING_ID = A.MEETING_ID
                 WHERE A.PROJECT_ID = ?""" + (Boolean.TRUE.equals(open) ? " AND A.DONE = FALSE" : "")
                        + " ORDER BY A.DUE_DT NULLS LAST, M.MEETING_AT DESC",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getObject(7, LocalDate.class), rs.getBoolean(8)},
                projectId);
        var names = support.names(rows.stream().map(r -> (String) r[5]).toList());
        return rows.stream().map(r -> new OpenActionItem((String) r[0], (String) r[1], (String) r[2], (String) r[3],
                (String) r[4], PjSupport.ref(names, (String) r[5]), (LocalDate) r[6], (Boolean) r[7])).toList();
    }

    @PatchMapping("/action-items/{itemId}")
    @Transactional
    public OpenActionItem patchActionItem(@AuthenticationPrincipal AuthUser user, @PathVariable String itemId,
            @RequestBody ActionPatch req) {
        var row = jdbc.query("SELECT PROJECT_ID, OWNER_ID FROM QA_ACTION_ITEM WHERE ITEM_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, itemId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("액션아이템"));
        var a = access.forProject(user, row[0]);
        if (!a.has(Perm.ISSUE_EDIT) && !user.userId().equals(row[1])) {
            throw ApiException.forbidden();
        }
        if (req.ownerId() != null) {
            qa.requireMember("ownerId", row[0], req.ownerId());
        }
        jdbc.update("UPDATE QA_ACTION_ITEM SET DONE = COALESCE(?, DONE), OWNER_ID = COALESCE(?, OWNER_ID),"
                + " DUE_DT = COALESCE(?, DUE_DT) WHERE ITEM_ID = ?", req.done(), req.ownerId(), req.dueDate(), itemId);
        if (req.ownerId() != null && !req.ownerId().equals(row[1]) && !req.ownerId().equals(user.userId())) {
            notify.notify(req.ownerId(), Event.ASSIGNED, "액션아이템 배정", null, "/quality/meetings");
        }
        return actionItems(user, row[0], false).stream().filter(x -> x.itemId().equals(itemId)).findFirst()
                .orElseThrow();
    }
}
