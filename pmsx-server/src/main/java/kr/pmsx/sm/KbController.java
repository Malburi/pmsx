package kr.pmsx.sm;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.pj.ProjectSeq;
import kr.pmsx.sm.SrService.Similar;
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

/** KB (FR-SM-011, docs/13 §5): Markdown 문서, SR → KB 초안 전환, 유사 KB·SR 추천. */
@RestController
@RequestMapping("/api/v1")
public class KbController {

    public record KbArticle(String kbId, String kbNo, String title, String content, List<String> tags,
                            String systemId, String screenId, String screenName, String sourceSrId,
                            UserRef author, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /** tags 는 문자열 배열 또는 콤마 구분 문자열 */
    public record KbRequest(String title, String content, JsonNode tags, String systemId, String screenId,
                            String sourceSrId) {
    }

    private static final String COLS = """
            SELECT K.KB_ID, K.KB_NO, K.TITLE, K.CONTENT, K.TAGS, K.SYSTEM_ID, K.SCREEN_ID, S.NAME SCREEN_NM,
                   K.SOURCE_SR_ID, K.CREATED_BY, U.NAME AUTHOR_NM, K.CREATED_AT, K.UPDATED_AT, K.PROJECT_ID
              FROM SM_KB K LEFT JOIN AS_SCREEN S ON S.SCREEN_ID = K.SCREEN_ID LEFT JOIN CM_USER U ON U.USER_ID = K.CREATED_BY""";

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ProjectSeq seq;
    private final SrService srs;
    private final AuditService audit;

    public KbController(JdbcTemplate jdbc, ProjectAccess access, ProjectSeq seq, SrService srs, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.seq = seq;
        this.srs = srs;
        this.audit = audit;
    }

    private static KbArticle map(java.sql.ResultSet rs) throws java.sql.SQLException {
        String a = rs.getString("CREATED_BY");
        return new KbArticle(rs.getString("KB_ID"), rs.getString("KB_NO"), rs.getString("TITLE"),
                rs.getString("CONTENT"), PjSupport.csv(rs.getString("TAGS")), rs.getString("SYSTEM_ID"),
                rs.getString("SCREEN_ID"), rs.getString("SCREEN_NM"), rs.getString("SOURCE_SR_ID"),
                a == null ? null : new UserRef(a, rs.getString("AUTHOR_NM")), Times.read(rs, "CREATED_AT"),
                Times.read(rs, "UPDATED_AT"));
    }

    @GetMapping("/projects/{projectId}/kb")
    public kr.pmsx.common.PageDto<KbArticle> list(@AuthenticationPrincipal AuthUser user,
            @PathVariable String projectId, @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        access.forProject(user, projectId);
        int[] ps = kr.pmsx.common.PageDto.normalize(page, size);
        String where = " WHERE K.PROJECT_ID = ?";
        List<Object> args = new ArrayList<>(List.of(projectId));
        if (q != null && !q.isBlank()) {
            String like = "%" + q.trim().toLowerCase() + "%";
            where += " AND (LOWER(K.TITLE) LIKE ? OR LOWER(K.CONTENT) LIKE ? OR LOWER(K.TAGS) LIKE ? OR LOWER(K.KB_NO) LIKE ?)";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM SM_KB K" + where, Long.class, args.toArray());
        List<Object> pa = new ArrayList<>(args);
        pa.add(ps[1]);
        pa.add((long) ps[0] * ps[1]);
        return new kr.pmsx.common.PageDto<>(jdbc.query(COLS + where + " ORDER BY K.UPDATED_AT DESC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), pa.toArray()), ps[0], ps[1], total == null ? 0 : total);
    }

    @GetMapping("/projects/{projectId}/kb/similar")
    public List<Similar> similar(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam String text) {
        access.forProject(user, projectId);
        return srs.similar(projectId, null, text);
    }

    @PostMapping("/projects/{projectId}/kb")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public KbArticle create(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody KbRequest req) {
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        return insert(projectId, req, user);
    }

    public KbArticle insert(String projectId, KbRequest req, AuthUser user) {
        String title = PjSupport.required("title", req.title(), 300);
        String content = PjSupport.required("content", req.content(), 100_000);
        String[] t = srs.target(projectId, req.systemId(), req.screenId(), null);
        String id = Ulid.next();
        String no = seq.next(projectId, "KB");
        OffsetDateTime now = Times.now();
        jdbc.update("""
                INSERT INTO SM_KB (KB_ID, PROJECT_ID, KB_NO, TITLE, CONTENT, TAGS, SYSTEM_ID, SCREEN_ID, SOURCE_SR_ID,
                                   CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, no, title, content, tags(req.tags()),
                t[0], t[1], SrService.blank(req.sourceSrId()), now, user.userId(), now, user.userId());
        audit.log(Action.CREATE, "kb:" + id, "KB 등록 " + no, projectId);
        return get(id);
    }

    static String tags(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> out.add(x.asText()));
        } else {
            out.addAll(PjSupport.csv(n.asText()));
        }
        String v = PjSupport.csv(out);
        if (v != null && v.length() > 500) {
            throw PjSupport.validation("tags", "태그가 너무 깁니다.");
        }
        return v;
    }

    private KbArticle get(String id) {
        return jdbc.query(COLS + " WHERE K.KB_ID = ?", (rs, i) -> map(rs), id).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("KB"));
    }

    private String projectOf(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_KB WHERE KB_ID = ?", (rs, i) -> rs.getString(1), id).stream()
                .findFirst().orElseThrow(() -> ApiException.notFound("KB"));
    }

    @GetMapping("/kb/{kbId}")
    public KbArticle one(@AuthenticationPrincipal AuthUser user, @PathVariable String kbId) {
        access.forProject(user, projectOf(kbId));
        return get(kbId);
    }

    @PutMapping("/kb/{kbId}")
    @Transactional
    public KbArticle update(@AuthenticationPrincipal AuthUser user, @PathVariable String kbId,
            @RequestBody KbRequest req) {
        String projectId = projectOf(kbId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        String[] t = srs.target(projectId, req.systemId(), req.screenId(), null);
        jdbc.update("""
                UPDATE SM_KB SET TITLE = ?, CONTENT = ?, TAGS = ?, SYSTEM_ID = ?, SCREEN_ID = ?, UPDATED_AT = ?,
                       UPDATED_BY = ? WHERE KB_ID = ?""", PjSupport.required("title", req.title(), 300),
                PjSupport.required("content", req.content(), 100_000), tags(req.tags()), t[0], t[1], Times.now(),
                user.userId(), kbId);
        audit.log(Action.UPDATE, "kb:" + kbId, "KB 수정", projectId);
        return get(kbId);
    }

    @DeleteMapping("/kb/{kbId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String kbId) {
        String projectId = projectOf(kbId);
        access.forProject(user, projectId).require(SmPerm.OPS_EDIT);
        jdbc.update("DELETE FROM SM_KB WHERE KB_ID = ?", kbId);
        audit.log(Action.DELETE, "kb:" + kbId, "KB 삭제", projectId);
    }

    /** SR → KB 초안: 제목·내용(요청 + 공개 처리 코멘트)·유형 태그·대상 화면 */
    @PostMapping("/srs/{srId}/to-kb")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public KbArticle fromSr(@AuthenticationPrincipal AuthUser user, @PathVariable String srId) {
        var r = srs.rowOf(srId);
        access.forProject(user, r.projectId()).require(SmPerm.OPS_EDIT);
        StringBuilder md = new StringBuilder();
        md.append("## 증상 / 요청\n\n").append(r.description() == null ? r.title() : r.description()).append("\n\n");
        md.append("## 처리 내용\n\n");
        List<SrService.SrComment> cs = srs.comments(srId, false);
        if (cs.isEmpty()) {
            md.append("(처리 내용을 작성하세요)\n");
        }
        for (SrService.SrComment c : cs) {
            md.append("- ").append(c.content().replace("\n", "\n  ")).append("\n");
        }
        md.append("\n> 출처: ").append(r.srNo()).append("\n");
        return insert(r.projectId(), new KbRequest(r.title(), md.toString(),
                com.fasterxml.jackson.databind.node.TextNode.valueOf(r.type()), r.systemId(), r.screenId(), srId),
                user);
    }
}
