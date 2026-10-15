package kr.pmsx.sm;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.pj.ProjectSeq;
import kr.pmsx.sm.SlaEngine.SrSla;
import kr.pmsx.sm.SlaService.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SR 접수·처리 (FR-SM-001~003, docs/13 §1). 내부 화면·고객 포털·메일 접수·인수인계가 함께 쓴다.
 * CUSTOMER 는 본인 또는 같은 고객사(소속사) SR 만 보고, 내부(internal) 코멘트는 볼 수 없다.
 */
@Service
public class SrService {

    public record Requester(String name, String org, String contact, String userId) {
    }

    public record SrComment(String commentId, String content, boolean internal, UserRef author,
                            OffsetDateTime createdAt) {
    }

    public record SrHist(OffsetDateTime at, String user, String from, String to, String comment) {
    }

    public record Similar(String kind, String id, String no, String title, double score) {
    }

    public record Sr(String srId, String srNo, String type, String priority, String title, String description,
                     String channel, Requester requester, String status, UserRef assignee, String systemId,
                     String screenId, String elementId, String screenName, SrSla sla, double effortHours,
                     OffsetDateTime createdAt, OffsetDateTime updatedAt, List<SrComment> comments,
                     List<SrHist> history, List<Similar> similar) {
    }

    public record SrRequest(String type, String priority, String title, String description, String channel,
                            Requester requester, String assigneeId, String systemId, String screenId,
                            String elementId) {
    }

    public record TransitionRequest(String to, String comment, String assigneeId) {
    }

    public record CommentRequest(String content, Boolean internal) {
    }

    /** 허용 전이 (docs/13 §1). 종료 상태(CLOSED·REJECTED)를 제외한 모든 단계에서 REJECTED 가능 */
    static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "RECEIVED", Set.of("CLASSIFIED", "ASSIGNED", "REJECTED"),
            "CLASSIFIED", Set.of("ASSIGNED", "REJECTED"),
            "ASSIGNED", Set.of("IN_PROGRESS", "REJECTED"),
            "IN_PROGRESS", Set.of("ON_HOLD", "RESOLVED", "REJECTED"),
            "ON_HOLD", Set.of("IN_PROGRESS", "REJECTED"),
            "RESOLVED", Set.of("CONFIRMED", "IN_PROGRESS", "REJECTED"),
            "CONFIRMED", Set.of("CLOSED", "REJECTED"),
            "CLOSED", Set.of(),
            "REJECTED", Set.of());

    static final String COLS = """
            SELECT R.SR_ID, R.PROJECT_ID, R.SR_NO, R.TYPE, R.PRIORITY, R.TITLE, R.DESCRIPTION, R.CHANNEL, R.REQ_NAME,
                   R.REQ_ORG, R.REQ_CONTACT, R.REQ_USER_ID, R.STATUS, R.ASSIGNEE_ID, U.NAME ASSIGNEE_NM, R.SYSTEM_ID,
                   R.SCREEN_ID, R.ELEMENT_ID, S.NAME SCREEN_NM, R.POLICY_ID, R.RESPONDED_AT, R.RESOLVED_AT,
                   R.CREATED_AT, R.UPDATED_AT, R.CREATED_BY
              FROM SM_SR R LEFT JOIN CM_USER U ON U.USER_ID = R.ASSIGNEE_ID LEFT JOIN AS_SCREEN S ON S.SCREEN_ID = R.SCREEN_ID""";

    /** 목록용 원시 행 */
    record Row(String srId, String projectId, String srNo, String type, String priority, String title,
               String description, String channel, Requester requester, String status, UserRef assignee,
               String systemId, String screenId, String elementId, String screenName, String policyId,
               OffsetDateTime respondedAt, OffsetDateTime resolvedAt, OffsetDateTime createdAt,
               OffsetDateTime updatedAt, String createdBy) {

        Clock clock() {
            return new Clock(srId, policyId, createdAt, respondedAt, resolvedAt, status);
        }
    }

    static Row row(java.sql.ResultSet rs) throws java.sql.SQLException {
        String a = rs.getString("ASSIGNEE_ID");
        return new Row(rs.getString("SR_ID"), rs.getString("PROJECT_ID"), rs.getString("SR_NO"), rs.getString("TYPE"),
                rs.getString("PRIORITY"), rs.getString("TITLE"), rs.getString("DESCRIPTION"), rs.getString("CHANNEL"),
                new Requester(rs.getString("REQ_NAME"), rs.getString("REQ_ORG"), rs.getString("REQ_CONTACT"),
                        rs.getString("REQ_USER_ID")), rs.getString("STATUS"),
                a == null ? null : new UserRef(a, rs.getString("ASSIGNEE_NM")), rs.getString("SYSTEM_ID"),
                rs.getString("SCREEN_ID"), rs.getString("ELEMENT_ID"), rs.getString("SCREEN_NM"),
                rs.getString("POLICY_ID"), Times.read(rs, "RESPONDED_AT"), Times.read(rs, "RESOLVED_AT"),
                Times.read(rs, "CREATED_AT"), Times.read(rs, "UPDATED_AT"), rs.getString("CREATED_BY"));
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final PjSupport support;
    private final ProjectSeq seq;
    private final SlaService sla;
    private final NotificationService notify;
    private final AuditService audit;

    public SrService(JdbcTemplate jdbc, ProjectAccess access, PjSupport support, ProjectSeq seq, SlaService sla,
            NotificationService notify, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.support = support;
        this.seq = seq;
        this.sla = sla;
        this.notify = notify;
        this.audit = audit;
    }

    public SlaService sla() {
        return sla;
    }

    // ───────────────────────── 조회 ─────────────────────────

    /** CUSTOMER 범위: 본인 또는 같은 소속사 요청자. 소속사가 없으면 본인 건만. */
    public String[] customerScope(AuthUser user) {
        String company = jdbc.query("SELECT COMPANY FROM CM_USER WHERE USER_ID = ?", (rs, i) -> rs.getString(1),
                user.userId()).stream().findFirst().orElse(null);
        return new String[] {user.userId(), company == null || company.isBlank() ? null : company};
    }

    static String customerWhere(String[] scope, List<Object> args) {
        args.add(scope[0]);
        if (scope[1] == null) {
            return " AND R.REQ_USER_ID = ?";
        }
        args.add(scope[1]);
        return " AND (R.REQ_USER_ID = ? OR R.REQ_ORG = ?)";
    }

    public PageDto<Sr> list(String projectId, Map<String, String> f, String[] customer, Integer page, Integer size) {
        int[] ps = PageDto.normalize(page, size);
        StringBuilder where = new StringBuilder(" WHERE R.PROJECT_ID = ?");
        List<Object> args = new ArrayList<>(List.of(projectId));
        for (String[] c : new String[][] {{"status", "R.STATUS"}, {"type", "R.TYPE"}, {"priority", "R.PRIORITY"}}) {
            String v = f.get(c[0]);
            if (v != null && !v.isBlank()) {
                List<String> vs = PjSupport.csv(v);
                where.append(" AND ").append(c[1]).append(" IN (")
                        .append(String.join(",", vs.stream().map(x -> "?").toList())).append(")");
                args.addAll(vs);
            }
        }
        if (notBlank(f.get("assigneeId"))) {
            where.append(" AND R.ASSIGNEE_ID = ?");
            args.add(f.get("assigneeId"));
        }
        if (notBlank(f.get("screenId"))) {
            where.append(" AND R.SCREEN_ID = ?");
            args.add(f.get("screenId"));
        }
        if (notBlank(f.get("q"))) {
            String like = "%" + f.get("q").trim().toLowerCase() + "%";
            where.append(" AND (LOWER(R.TITLE) LIKE ? OR LOWER(R.SR_NO) LIKE ? OR LOWER(R.REQ_NAME) LIKE ?)");
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (customer != null) {
            where.append(customerWhere(customer, args));
        }
        String slaState = f.get("slaState");
        String order = " ORDER BY R.CREATED_AT DESC, R.SR_NO DESC";
        if (notBlank(slaState)) {
            // SLA 상태는 계산값이므로 조건에 맞는 행 전체를 평가한 뒤 페이지를 자른다
            List<Row> rows = jdbc.query(COLS + where + order, (rs, i) -> row(rs), args.toArray());
            Map<String, SrSla> s = sla.evaluator().evaluate(rows.stream().map(Row::clock).toList());
            List<String> states = PjSupport.csv(slaState);
            List<Row> hit = rows.stream().filter(r -> s.get(r.srId()) != null
                    && states.contains(s.get(r.srId()).state())).toList();
            List<Row> pageRows = hit.stream().skip((long) ps[0] * ps[1]).limit(ps[1]).toList();
            return new PageDto<>(assemble(pageRows, s, false, customer != null), ps[0], ps[1], hit.size());
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM SM_SR R" + where, Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(ps[1]);
        pa.add((long) ps[0] * ps[1]);
        List<Row> rows = jdbc.query(COLS + where + order + " LIMIT ? OFFSET ?", (rs, i) -> row(rs), pa.toArray());
        Map<String, SrSla> s = sla.evaluator().evaluate(rows.stream().map(Row::clock).toList());
        return new PageDto<>(assemble(rows, s, false, customer != null), ps[0], ps[1], total == null ? 0 : total);
    }

    /** 프로젝트의 SR 행 전체 (조건 SQL 조각) — 현황·보고서 집계용 */
    public List<Row> rows(String where, Object... args) {
        return jdbc.query(COLS + " WHERE " + where, (rs, i) -> row(rs), args);
    }

    public Map<String, SrSla> evaluate(List<Row> rows) {
        return sla.evaluator().evaluate(rows.stream().map(Row::clock).toList());
    }

    List<Sr> assemble(List<Row> rows, Map<String, SrSla> s, boolean detail, boolean customerView) {
        Map<String, Double> effort = effort(rows.stream().map(Row::srId).toList());
        List<Sr> out = new ArrayList<>();
        for (Row r : rows) {
            out.add(new Sr(r.srId(), r.srNo(), r.type(), r.priority(), r.title(), r.description(), r.channel(),
                    r.requester(), r.status(), r.assignee(), r.systemId(), r.screenId(), r.elementId(), r.screenName(),
                    s.get(r.srId()), customerView ? 0 : effort.getOrDefault(r.srId(), 0.0), r.createdAt(),
                    r.updatedAt(), null, null, null));
        }
        return out;
    }

    /** 승인·제출된 작업일지의 SR 공수 */
    private Map<String, Double> effort(List<String> ids) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i += 500) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + 500));
            if (chunk.isEmpty()) {
                continue;
            }
            String in = String.join(",", chunk.stream().map(x -> "?").toList());
            jdbc.query("""
                    SELECT E.TARGET_ID, SUM(E.HOURS) FROM RS_TIMESHEET_ENTRY E JOIN RS_TIMESHEET T ON T.TIMESHEET_ID = E.TIMESHEET_ID
                     WHERE E.TARGET_TYPE = 'SR' AND T.STATUS IN ('SUBMITTED', 'APPROVED') AND E.TARGET_ID IN (""" + in
                    + ") GROUP BY E.TARGET_ID", rs -> {
                        out.put(rs.getString(1), Math.round(rs.getDouble(2) * 10) / 10.0);
                    }, chunk.toArray());
        }
        return out;
    }

    public Row rowOf(String srId) {
        return jdbc.query(COLS + " WHERE R.SR_ID = ?", (rs, i) -> row(rs), srId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("SR"));
    }

    /** 상세 (코멘트·이력·유사 추천). customerView 면 내부 코멘트·공수·유사 추천 제외 */
    public Sr detail(String srId, boolean customerView) {
        Row r = rowOf(srId);
        Map<String, SrSla> s = evaluate(List.of(r));
        Sr base = assemble(List.of(r), s, true, customerView).get(0);
        List<SrComment> comments = comments(srId, customerView);
        List<SrHist> history = support.hist("SR", srId).stream().map(h -> new SrHist(h.at(),
                h.user() == null ? null : h.user().name(), h.fromStatus(), h.toStatus(), h.comment())).toList();
        List<Similar> similar = customerView ? null : similar(r.projectId(), srId, r.title() + " "
                + (r.description() == null ? "" : r.description()));
        return new Sr(base.srId(), base.srNo(), base.type(), base.priority(), base.title(), base.description(),
                base.channel(), base.requester(), base.status(), base.assignee(), base.systemId(), base.screenId(),
                base.elementId(), base.screenName(), base.sla(), base.effortHours(), base.createdAt(),
                base.updatedAt(), comments, history, similar);
    }

    public List<SrComment> comments(String srId, boolean customerView) {
        List<Object[]> rows = jdbc.query("SELECT COMMENT_ID, CONTENT, INTERNAL, AUTHOR_ID, CREATED_AT FROM SM_SR_COMMENT"
                + " WHERE SR_ID = ?" + (customerView ? " AND INTERNAL = FALSE" : "") + " ORDER BY CREATED_AT, COMMENT_ID",
                (rs, i) -> new Object[] {rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4),
                        Times.read(rs, "CREATED_AT")}, srId);
        Map<String, String> names = support.names(rows.stream().map(x -> (String) x[3]).toList());
        return rows.stream().map(x -> new SrComment((String) x[0], (String) x[1], (Boolean) x[2],
                PjSupport.ref(names, (String) x[3]), (OffsetDateTime) x[4])).toList();
    }

    /** KB + 종료(해결 이후) SR 중 유사 상위 5 */
    public List<Similar> similar(String projectId, String excludeSrId, String text) {
        List<Similarity.Doc> docs = new ArrayList<>();
        jdbc.query("SELECT KB_ID, KB_NO, TITLE, CONTENT, TAGS FROM SM_KB WHERE PROJECT_ID = ?", rs -> {
            docs.add(new Similarity.Doc("KB", rs.getString(1), rs.getString(2), rs.getString(3),
                    rs.getString(4) + " " + (rs.getString(5) == null ? "" : rs.getString(5))));
        }, projectId);
        jdbc.query("SELECT SR_ID, SR_NO, TITLE, DESCRIPTION FROM SM_SR WHERE PROJECT_ID = ?"
                + " AND STATUS IN ('RESOLVED', 'CONFIRMED', 'CLOSED') ORDER BY CREATED_AT DESC LIMIT 2000", rs -> {
            if (!rs.getString(1).equals(excludeSrId)) {
                docs.add(new Similarity.Doc("SR", rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
            }
        }, projectId);
        return Similarity.top(text, docs, 5, 0.1).stream().map(x -> new Similar(x.doc().kind(), x.doc().id(),
                x.doc().no(), x.doc().title(), x.score())).toList();
    }

    // ───────────────────────── 등록·수정 ─────────────────────────

    /** 화면·요소·시스템 정합 (요소 → 화면 → 시스템, 시스템은 이 프로젝트 소속) → [systemId, screenId, elementId] */
    String[] target(String projectId, String systemId, String screenId, String elementId) {
        String sys = blank(systemId);
        String scr = blank(screenId);
        String el = blank(elementId);
        if (el != null) {
            String[] x = jdbc.query("SELECT SCREEN_ID, SYSTEM_ID FROM AS_ELEMENT WHERE ELEMENT_ID = ?",
                    (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, el).stream().findFirst()
                    .orElseThrow(() -> PjSupport.validation("elementId", "요소를 찾을 수 없습니다."));
            scr = x[0];
            sys = x[1];
        }
        if (scr != null) {
            String finalScr = scr;
            sys = jdbc.query("SELECT SYSTEM_ID FROM AS_SCREEN WHERE SCREEN_ID = ?", (rs, i) -> rs.getString(1), scr)
                    .stream().findFirst()
                    .orElseThrow(() -> PjSupport.validation("screenId", "화면을 찾을 수 없습니다: " + finalScr));
        }
        if (sys != null) {
            String p = jdbc.query("SELECT PROJECT_ID FROM AS_SYSTEM WHERE SYSTEM_ID = ?", (rs, i) -> rs.getString(1), sys)
                    .stream().findFirst().orElseThrow(() -> PjSupport.validation("systemId", "시스템을 찾을 수 없습니다."));
            if (!p.equals(projectId)) {
                throw PjSupport.validation(el != null ? "elementId" : scr != null ? "screenId" : "systemId",
                        "이 프로젝트의 시스템이 아닙니다.");
            }
        }
        return new String[] {sys, scr, el};
    }

    /** 등록 (채널·요청자·생성 시각은 호출자가 정함). createdAt 이 null 이면 지금 */
    @Transactional
    public Sr create(String projectId, SrRequest req, String channel, Requester requester, AuthUser user,
            OffsetDateTime createdAt, String messageId) {
        String type = PjSupport.oneOf("type", req.type(), "INQUIRY", SmPerm.SR_TYPES.toArray(String[]::new));
        String priority = PjSupport.oneOf("priority", req.priority(), "P3", SmPerm.PRIORITIES.toArray(String[]::new));
        String title = PjSupport.required("title", req.title(), 300);
        if (requester == null || requester.name() == null || requester.name().isBlank()) {
            throw PjSupport.validation("requester.name", "요청자를 입력해주세요.");
        }
        String[] t = target(projectId, req.systemId(), req.screenId(), req.elementId());
        String assignee = blank(req.assigneeId());
        if (assignee != null) {
            requireMember("assigneeId", projectId, assignee);
        }
        OffsetDateTime at = createdAt == null ? Times.now() : createdAt;
        String id = Ulid.next();
        String no = seq.next(projectId, "SR");
        SlaEngine.Policy p = sla.match(projectId, type, priority);
        String status = assignee == null ? "RECEIVED" : "ASSIGNED";
        jdbc.update("""
                INSERT INTO SM_SR (SR_ID, PROJECT_ID, SR_NO, TYPE, PRIORITY, TITLE, DESCRIPTION, CHANNEL, REQ_NAME,
                                   REQ_ORG, REQ_CONTACT, REQ_USER_ID, STATUS, ASSIGNEE_ID, SYSTEM_ID, SCREEN_ID,
                                   ELEMENT_ID, POLICY_ID, MESSAGE_ID, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no,
                type, priority, title, req.description(), channel, cut(requester.name().trim(), 100),
                cut(blank(requester.org()), 200), cut(blank(requester.contact()), 200), blank(requester.userId()),
                status, assignee, t[0], t[1], t[2], p == null ? null : p.policyId(), messageId, at,
                user == null ? null : user.userId(), at, user == null ? null : user.userId());
        sla.event(id, "START", at);
        support.hist("SR", id, "STATUS", null, status, null);
        if (assignee != null) {
            notify.notify(assignee, Event.ASSIGNED, "SR 담당: " + no + " " + title, null, link(id));
        } else {
            notify.notifyAll(managers(projectId), Event.REVIEW_REQUESTED, "새 SR 접수: " + no + " " + title,
                    requester.name() + " · " + channel, link(id));
        }
        audit.log(Action.CREATE, "sr:" + id, "SR 등록 " + no, projectId);
        return detail(id, false);
    }

    @Transactional
    public Sr update(String srId, SrRequest req, AuthUser user) {
        Row cur = rowOf(srId);
        var a = access.forProject(user, cur.projectId());
        requireProcess(a, user, cur);
        String type = PjSupport.oneOf("type", req.type(), cur.type(), SmPerm.SR_TYPES.toArray(String[]::new));
        String priority = PjSupport.oneOf("priority", req.priority(), cur.priority(),
                SmPerm.PRIORITIES.toArray(String[]::new));
        String title = PjSupport.required("title", req.title() == null ? cur.title() : req.title(), 300);
        String[] t = target(cur.projectId(), req.systemId(), req.screenId(), req.elementId());
        Requester rq = req.requester() == null ? cur.requester() : req.requester();
        if (rq.name() == null || rq.name().isBlank()) {
            throw PjSupport.validation("requester.name", "요청자를 입력해주세요.");
        }
        String policyId = cur.policyId();
        if (!type.equals(cur.type()) || !priority.equals(cur.priority()) || policyId == null) {
            SlaEngine.Policy p = sla.match(cur.projectId(), type, priority);
            policyId = p == null ? null : p.policyId();
        }
        String assignee = req.assigneeId() == null ? (cur.assignee() == null ? null : cur.assignee().userId())
                : blank(req.assigneeId());
        if (assignee != null) {
            requireMember("assigneeId", cur.projectId(), assignee);
        }
        jdbc.update("""
                UPDATE SM_SR SET TYPE = ?, PRIORITY = ?, TITLE = ?, DESCRIPTION = ?, REQ_NAME = ?, REQ_ORG = ?,
                       REQ_CONTACT = ?, ASSIGNEE_ID = ?, SYSTEM_ID = ?, SCREEN_ID = ?, ELEMENT_ID = ?, POLICY_ID = ?,
                       UPDATED_AT = ?, UPDATED_BY = ? WHERE SR_ID = ?""", type, priority, title,
                req.description() == null ? cur.description() : req.description(), cut(rq.name().trim(), 100),
                cut(blank(rq.org()), 200), cut(blank(rq.contact()), 200), assignee, t[0], t[1], t[2], policyId,
                Times.now(), user.userId(), srId);
        if (assignee != null && (cur.assignee() == null || !assignee.equals(cur.assignee().userId()))
                && !assignee.equals(user.userId())) {
            notify.notify(assignee, Event.ASSIGNED, "SR 담당: " + cur.srNo() + " " + title, null, link(srId));
        }
        audit.log(Action.UPDATE, "sr:" + srId, "SR 수정 " + cur.srNo(), cur.projectId());
        return detail(srId, false);
    }

    // ───────────────────────── 전이 ─────────────────────────

    /** PM·PL 은 전체, OPERATOR 는 본인 담당 건(미배정 건을 본인에게 배정하는 것 포함) */
    void requireProcess(ProjectAccess.Access a, AuthUser user, Row r) {
        if (a.has(SmPerm.SR_PROCESS_ALL)) {
            return;
        }
        a.require(SmPerm.SR_PROCESS);
        if (r.assignee() != null && !r.assignee().userId().equals(user.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "본인이 담당한 SR 만 처리할 수 있습니다.");
        }
    }

    @Transactional
    public Sr transition(String srId, TransitionRequest req, AuthUser user) {
        Row r = rowOf(srId);
        var a = access.forProject(user, r.projectId());
        requireProcess(a, user, r);
        if (!a.has(SmPerm.SR_PROCESS_ALL) && "ASSIGNED".equals(req.to()) && req.assigneeId() != null
                && !req.assigneeId().equals(user.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "운영 담당은 본인에게만 배정할 수 있습니다.");
        }
        return apply(r, req.to(), req.comment(), req.assigneeId(), user);
    }

    /** 상태 전이 공통 (권한 확인 후 호출). 고객 포털의 확인·재요청도 여기로 온다. */
    @Transactional
    public Sr apply(Row r, String to, String comment, String assigneeId, AuthUser user) {
        Set<String> allowed = TRANSITIONS.getOrDefault(r.status(), Set.of());
        if (to == null || !allowed.contains(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT",
                    r.status() + " → " + to + " 전이는 허용되지 않습니다.", Map.of("to", "허용: " + String.join(", ",
                    allowed.stream().sorted().toList())));
        }
        OffsetDateTime now = Times.now();
        String assignee = r.assignee() == null ? null : r.assignee().userId();
        if (assigneeId != null && !assigneeId.isBlank()) {
            requireMember("assigneeId", r.projectId(), assigneeId);
            assignee = assigneeId;
        }
        switch (to) {
            case "ASSIGNED" -> {
                if (assignee == null) {
                    throw PjSupport.validation("assigneeId", "담당자를 지정해주세요.");
                }
            }
            case "REJECTED" -> {
                if (comment == null || comment.isBlank()) {
                    throw PjSupport.validation("comment", "반려 사유를 입력해주세요.");
                }
            }
            default -> {
            }
        }
        OffsetDateTime responded = r.respondedAt();
        OffsetDateTime resolved = r.resolvedAt();
        OffsetDateTime closed = null;
        if ("IN_PROGRESS".equals(to) && responded == null) {
            responded = now;
        }
        if ("ON_HOLD".equals(r.status())) {
            sla.event(r.srId(), "RESUME", now);
        }
        switch (to) {
            case "ON_HOLD" -> sla.event(r.srId(), "PAUSE", now);
            case "RESOLVED" -> {
                resolved = now;
                sla.event(r.srId(), "STOP", now);
            }
            case "IN_PROGRESS" -> {
                if ("RESOLVED".equals(r.status())) {
                    resolved = null;
                    sla.event(r.srId(), "RESUME", now);
                }
            }
            case "REJECTED" -> {
                if (!SlaEngine.STOPPED.contains(r.status())) {
                    sla.event(r.srId(), "STOP", now);
                }
                closed = now;
            }
            case "CLOSED" -> closed = now;
            default -> {
            }
        }
        jdbc.update("""
                UPDATE SM_SR SET STATUS = ?, ASSIGNEE_ID = ?, RESPONDED_AT = ?, RESOLVED_AT = ?,
                       CLOSED_AT = COALESCE(?, CLOSED_AT), REJECT_REASON = ?, UPDATED_AT = ?, UPDATED_BY = ?
                 WHERE SR_ID = ?""", to, assignee, responded, resolved, closed,
                "REJECTED".equals(to) ? cut(comment, 1000) : null, now, user.userId(), r.srId());
        support.hist("SR", r.srId(), "STATUS", r.status(), to, blank(comment));
        String oldAssignee = r.assignee() == null ? null : r.assignee().userId();
        if (assignee != null && !assignee.equals(oldAssignee) && !assignee.equals(user.userId())) {
            notify.notify(assignee, Event.ASSIGNED, "SR 담당: " + r.srNo() + " " + r.title(), null, link(r.srId()));
        }
        if ("RESOLVED".equals(to) && r.requester().userId() != null) {
            notify.notify(r.requester().userId(), Event.REVIEW_REQUESTED, "SR 처리 완료 확인 요청: " + r.srNo(),
                    r.title(), "/portal/srs/" + r.srId());
        }
        if ("IN_PROGRESS".equals(to) && "RESOLVED".equals(r.status()) && assignee != null
                && !assignee.equals(user.userId())) {
            notify.notify(assignee, Event.REJECTED, "SR 재요청: " + r.srNo(), comment, link(r.srId()));
        }
        audit.log(Action.UPDATE, "sr:" + r.srId(), "SR " + r.status() + "→" + to + " " + r.srNo(), r.projectId());
        return detail(r.srId(), false);
    }

    // ───────────────────────── 코멘트 ─────────────────────────

    /** 공개 코멘트를 운영 측(CUSTOMER 외)이 처음 남기면 응답 완료 */
    @Transactional
    public SrComment comment(Row r, String content, boolean internal, AuthUser user, boolean customer) {
        String c = PjSupport.required("content", content, 20_000);
        String id = Ulid.next();
        OffsetDateTime now = Times.now();
        boolean in = internal && !customer;
        jdbc.update("INSERT INTO SM_SR_COMMENT (COMMENT_ID, SR_ID, CONTENT, INTERNAL, AUTHOR_ID, CREATED_AT)"
                + " VALUES (?, ?, ?, ?, ?, ?)", id, r.srId(), c, in, user.userId(), now);
        if (!in && !customer && r.respondedAt() == null && !SlaEngine.STOPPED.contains(r.status())) {
            jdbc.update("UPDATE SM_SR SET RESPONDED_AT = ? WHERE SR_ID = ? AND RESPONDED_AT IS NULL", now, r.srId());
        }
        jdbc.update("UPDATE SM_SR SET UPDATED_AT = ? WHERE SR_ID = ?", now, r.srId());
        if (customer) {
            List<String> to = new ArrayList<>();
            if (r.assignee() != null) {
                to.add(r.assignee().userId());
            } else {
                to.addAll(managers(r.projectId()));
            }
            notify.notifyAll(to, Event.MENTION, "고객 코멘트: " + r.srNo(), cut(c, 200), link(r.srId()));
        } else if (!in && r.requester().userId() != null) {
            notify.notify(r.requester().userId(), Event.MENTION, "SR 답변: " + r.srNo(), cut(c, 200),
                    "/portal/srs/" + r.srId());
        }
        return comments(r.srId(), customer).stream().filter(x -> x.commentId().equals(id)).findFirst().orElseThrow();
    }

    // ───────────────────────── 도우미 ─────────────────────────

    public List<String> managers(String projectId) {
        return jdbc.queryForList("SELECT USER_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND ROLE_ID IN ('PM', 'PL')",
                String.class, projectId);
    }

    void requireMember(String field, String projectId, String userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                Integer.class, projectId, userId);
        if (n == null || n == 0) {
            throw PjSupport.validation(field, "프로젝트 멤버만 지정할 수 있습니다.");
        }
    }

    static String link(String srId) {
        return "/sm/srs/" + srId;
    }

    static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
