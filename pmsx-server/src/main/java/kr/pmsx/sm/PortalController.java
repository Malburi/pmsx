package kr.pmsx.sm;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.sm.SrService.CommentRequest;
import kr.pmsx.sm.SrService.Requester;
import kr.pmsx.sm.SrService.Sr;
import kr.pmsx.sm.SrService.SrComment;
import kr.pmsx.sm.SrService.SrRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객 SR 포털 (SCR-SM-010, docs/13 §2). CUSTOMER 로 참여한 SM 프로젝트만, 본인 또는 같은 고객사 SR 만,
 * 내부 코멘트·공수·유사 추천은 보이지 않는다.
 */
@RestController
@RequestMapping("/api/v1/portal")
public class PortalController {

    public record PortalProject(String projectId, String name, String customer, LocalDate startDate,
                                LocalDate endDate) {
    }

    public record ReasonRequest(String comment) {
    }

    private final JdbcTemplate jdbc;
    private final SrService srs;
    private final SrController srController;

    public PortalController(JdbcTemplate jdbc, SrService srs, SrController srController) {
        this.jdbc = jdbc;
        this.srs = srs;
        this.srController = srController;
    }

    @GetMapping("/projects")
    public List<PortalProject> projects(@AuthenticationPrincipal AuthUser user) {
        return jdbc.query("""
                SELECT P.PROJECT_ID, P.NAME, P.CUSTOMER, P.START_DT, P.END_DT FROM PJ_PROJECT P
                  JOIN PJ_MEMBER M ON M.PROJECT_ID = P.PROJECT_ID
                 WHERE M.USER_ID = ? AND M.ROLE_ID = 'CUSTOMER' AND P.TYPE = 'SM' ORDER BY P.NAME""",
                (rs, i) -> new PortalProject(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class)), user.userId());
    }

    /** CUSTOMER 로 참여한 SM 프로젝트인지 */
    private void requireCustomer(AuthUser user, String projectId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM PJ_MEMBER M JOIN PJ_PROJECT P ON P.PROJECT_ID = M.PROJECT_ID
                 WHERE M.PROJECT_ID = ? AND M.USER_ID = ? AND M.ROLE_ID = 'CUSTOMER' AND P.TYPE = 'SM'""",
                Integer.class, projectId, user.userId());
        if (n == null || n == 0) {
            throw ApiException.forbidden();
        }
    }

    private SrService.Row own(AuthUser user, String srId) {
        SrService.Row r = srs.rowOf(srId);
        requireCustomer(user, r.projectId());
        if (!SrController.inScope(srs.customerScope(user), r)) {
            throw ApiException.notFound("SR");
        }
        return r;
    }

    @GetMapping("/projects/{projectId}/srs")
    public PageDto<Sr> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String status, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        requireCustomer(user, projectId);
        Map<String, String> f = new LinkedHashMap<>();
        // 포털의 '처리 중'은 분류·배정·진행·보류 묶음 (docs/13 v0.3)
        f.put("status", "IN_PROGRESS".equals(status) ? "CLASSIFIED,ASSIGNED,IN_PROGRESS,ON_HOLD" : status);
        f.put("q", q);
        return srs.list(projectId, f, srs.customerScope(user), page, size);
    }

    @PostMapping("/projects/{projectId}/srs")
    @ResponseStatus(HttpStatus.CREATED)
    public Sr create(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody SrRequest req) {
        requireCustomer(user, projectId);
        Requester me = srController.self(user);
        Sr sr = srs.create(projectId, new SrRequest(req.type(), req.priority(), req.title(), req.description(),
                "PORTAL", me, null, req.systemId(), req.screenId(), req.elementId()), "PORTAL", me, user, null, null);
        return srs.detail(sr.srId(), true);
    }

    @GetMapping("/srs/{srId}")
    public Sr get(@AuthenticationPrincipal AuthUser user, @PathVariable String srId) {
        own(user, srId);
        return srs.detail(srId, true);
    }

    @PostMapping("/srs/{srId}/comments")
    @ResponseStatus(HttpStatus.CREATED)
    public SrComment comment(@AuthenticationPrincipal AuthUser user, @PathVariable String srId,
            @RequestBody CommentRequest req) {
        return srs.comment(own(user, srId), req.content(), false, user, true);
    }

    /** 처리 결과 확인 RESOLVED → CONFIRMED */
    @PostMapping("/srs/{srId}/confirm")
    @Transactional
    public Sr confirm(@AuthenticationPrincipal AuthUser user, @PathVariable String srId,
            @RequestBody(required = false) ReasonRequest req) {
        SrService.Row r = own(user, srId);
        requireResolved(r);
        srs.apply(r, "CONFIRMED", req == null ? null : req.comment(), null, user);
        return srs.detail(srId, true);
    }

    /** 재요청 RESOLVED → IN_PROGRESS (사유 필수, 공개 코멘트로도 남김) */
    @PostMapping("/srs/{srId}/reopen")
    @Transactional
    public Sr reopen(@AuthenticationPrincipal AuthUser user, @PathVariable String srId,
            @RequestBody ReasonRequest req) {
        SrService.Row r = own(user, srId);
        requireResolved(r);
        String reason = PjSupport.required("comment", req == null ? null : req.comment(), 2000);
        srs.apply(r, "IN_PROGRESS", reason, null, user);
        srs.comment(srs.rowOf(srId), "[재요청] " + reason, false, user, true);
        return srs.detail(srId, true);
    }

    private static void requireResolved(SrService.Row r) {
        if (!"RESOLVED".equals(r.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "처리 완료된 SR 만 확인·재요청할 수 있습니다.");
        }
    }
}
