package kr.pmsx.sm;

import java.util.LinkedHashMap;

import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.sm.SrService.CommentRequest;
import kr.pmsx.sm.SrService.Requester;
import kr.pmsx.sm.SrService.Sr;
import kr.pmsx.sm.SrService.SrComment;
import kr.pmsx.sm.SrService.SrRequest;
import kr.pmsx.sm.SrService.TransitionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SR 내부 화면 API + 메일 게이트웨이 접수 (docs/13 §1). */
@RestController
@RequestMapping("/api/v1")
public class SrController {

    public record InboundMail(String projectCode, String from, String subject, String body, String messageId) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final SrService srs;
    private final PmsxProperties props;

    public SrController(JdbcTemplate jdbc, ProjectAccess access, SrService srs, PmsxProperties props) {
        this.jdbc = jdbc;
        this.access = access;
        this.srs = srs;
        this.props = props;
    }

    private static boolean customer(ProjectAccess.Access a) {
        return "CUSTOMER".equals(a.projectRole()) && !a.sysAdmin() && !a.pmo();
    }

    @GetMapping("/projects/{projectId}/srs")
    public PageDto<Sr> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String type,
            @RequestParam(required = false) String priority, @RequestParam(required = false) String assigneeId,
            @RequestParam(required = false) String screenId, @RequestParam(required = false) String q,
            @RequestParam(required = false) String slaState, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        var a = access.forProject(user, projectId);
        Map<String, String> f = new LinkedHashMap<>();
        f.put("status", status);
        f.put("type", type);
        f.put("priority", priority);
        f.put("assigneeId", assigneeId);
        f.put("screenId", screenId);
        f.put("q", q);
        f.put("slaState", slaState);
        return srs.list(projectId, f, customer(a) ? srs.customerScope(user) : null, page, size);
    }

    @PostMapping("/projects/{projectId}/srs")
    @ResponseStatus(HttpStatus.CREATED)
    public Sr create(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody SrRequest req) {
        var a = access.forProject(user, projectId);
        a.require(SmPerm.SR_CREATE);
        Requester rq = req.requester();
        String channel = PjSupport.oneOf("channel", req.channel(), "MANUAL", "MANUAL", "EMAIL", "PORTAL");
        if (customer(a) || rq == null || rq.name() == null || rq.name().isBlank()) {
            rq = self(user);
        }
        SrRequest r = customer(a) ? new SrRequest(req.type(), req.priority(), req.title(), req.description(),
                "PORTAL", rq, null, req.systemId(), req.screenId(), req.elementId()) : req;
        return srs.create(projectId, r, customer(a) ? "PORTAL" : channel, rq, user, null, null);
    }

    /** 현재 사용자를 요청자로 */
    Requester self(AuthUser user) {
        return jdbc.query("SELECT NAME, COMPANY, EMAIL FROM CM_USER WHERE USER_ID = ?",
                (rs, i) -> new Requester(rs.getString(1), rs.getString(2), rs.getString(3), user.userId()),
                user.userId()).stream().findFirst().orElse(new Requester(user.name(), null, null, user.userId()));
    }

    /** 조회 권한 (CUSTOMER 는 범위 안의 SR 만) → [access, customer 여부] */
    private Object[] read(AuthUser user, SrService.Row r) {
        var a = access.forProject(user, r.projectId());
        boolean c = customer(a);
        if (c && !inScope(srs.customerScope(user), r)) {
            throw ApiException.notFound("SR");
        }
        return new Object[] {a, c};
    }

    static boolean inScope(String[] scope, SrService.Row r) {
        return scope[0].equals(r.requester().userId())
                || (scope[1] != null && scope[1].equals(r.requester().org()));
    }

    @GetMapping("/srs/{srId}")
    public Sr get(@AuthenticationPrincipal AuthUser user, @PathVariable String srId) {
        var r = srs.rowOf(srId);
        Object[] x = read(user, r);
        return srs.detail(srId, (Boolean) x[1]);
    }

    @PutMapping("/srs/{srId}")
    public Sr update(@AuthenticationPrincipal AuthUser user, @PathVariable String srId, @RequestBody SrRequest req) {
        return srs.update(srId, req, user);
    }

    @PostMapping("/srs/{srId}/transition")
    public Sr transition(@AuthenticationPrincipal AuthUser user, @PathVariable String srId,
            @RequestBody TransitionRequest req) {
        return srs.transition(srId, req, user);
    }

    @PostMapping("/srs/{srId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public SrComment comment(@AuthenticationPrincipal AuthUser user, @PathVariable String srId,
            @RequestBody CommentRequest req) {
        var r = srs.rowOf(srId);
        Object[] x = read(user, r);
        boolean c = (Boolean) x[1];
        if (!c) {
            ((ProjectAccess.Access) x[0]).require(java.util.Set.of("PM", "PL", "OPERATOR", "ANALYST", "DEV", "QA",
                    "PARTNER", "SYS_ADMIN"));
        }
        return srs.comment(r, req.content(), Boolean.TRUE.equals(req.internal()), user, c);
    }

    // ───────────────────────── 메일 게이트웨이 ─────────────────────────

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern
            .compile("([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+)");
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("^\\s*\"?([^\"<]+?)\"?\\s*<");

    /**
     * 메일 접수: projectCode 는 프로젝트 ID 또는 SM 프로젝트 시스템 코드(예: HR). 같은 messageId 는 한 번만 등록한다(200 으로 기존 SR).
     * 발신 주소가 사용자 이메일과 같으면 그 사용자를 요청자로 한다.
     */
    @PostMapping("/sm/inbound-mail")
    public ResponseEntity<Sr> inbound(@RequestHeader(value = "X-PMSX-KEY", required = false) String key,
            @RequestBody InboundMail mail) {
        String expected = props.inboundMailKey();
        if (expected == null || key == null || !java.security.MessageDigest.isEqual(expected.getBytes(),
                key.getBytes())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "메일 접수 키가 올바르지 않습니다.");
        }
        PjSupport.required("subject", mail.subject(), 300);
        PjSupport.required("from", mail.from(), 300);
        if (mail.messageId() != null && !mail.messageId().isBlank()) {
            String existing = jdbc.query("SELECT SR_ID FROM SM_SR WHERE MESSAGE_ID = ?", (rs, i) -> rs.getString(1),
                    mail.messageId().trim()).stream().findFirst().orElse(null);
            if (existing != null) {
                return ResponseEntity.ok(srs.detail(existing, false));
            }
        }
        String code = PjSupport.required("projectCode", mail.projectCode(), 100);
        String[] target = jdbc.query("SELECT PROJECT_ID, NULL FROM PJ_PROJECT WHERE PROJECT_ID = ?",
                (rs, i) -> new String[] {rs.getString(1), null}, code).stream().findFirst().orElseGet(() ->
                jdbc.query("""
                        SELECT Y.PROJECT_ID, Y.SYSTEM_ID FROM AS_SYSTEM Y JOIN PJ_PROJECT P ON P.PROJECT_ID = Y.PROJECT_ID
                         WHERE P.TYPE = 'SM' AND UPPER(Y.CODE) = UPPER(?) ORDER BY P.CREATED_AT DESC""",
                        (rs, i) -> new String[] {rs.getString(1), rs.getString(2)}, code).stream().findFirst()
                        .orElseThrow(() -> PjSupport.validation("projectCode", "프로젝트를 찾을 수 없습니다: " + code)));
        java.util.regex.Matcher em = EMAIL.matcher(mail.from());
        String address = em.find() ? em.group(1) : mail.from().trim();
        java.util.regex.Matcher nm = NAME.matcher(mail.from());
        String displayName = nm.find() ? nm.group(1).trim() : address;
        Requester rq = jdbc.query("SELECT USER_ID, NAME, COMPANY FROM CM_USER WHERE LOWER(EMAIL) = LOWER(?)",
                (rs, i) -> new Requester(rs.getString(2), rs.getString(3), address, rs.getString(1)), address).stream()
                .findFirst().orElse(new Requester(displayName, null, address, null));
        Sr sr = srs.create(target[0], new SrRequest("INQUIRY", "P3", mail.subject(), mail.body(), "EMAIL", rq, null,
                target[1], null, null), "EMAIL", rq, null, null,
                mail.messageId() == null || mail.messageId().isBlank() ? null : SrService.cut(mail.messageId().trim(),
                        300));
        return ResponseEntity.status(HttpStatus.CREATED).body(sr);
    }
}
