package kr.pmsx.cm.code;

import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import kr.pmsx.cm.code.CodeService.Code;
import kr.pmsx.cm.code.CodeService.CodeGroup;
import kr.pmsx.cm.code.CodeService.Term;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

/** 공통코드·용어 API (docs/10 §1). 조회는 로그인 사용자, 변경은 SYS_ADMIN. */
@RestController
@RequestMapping("/api/v1")
public class CodeController {

    private final CodeService codes;
    private final AuditService audit;

    public CodeController(CodeService codes, AuditService audit) {
        this.codes = codes;
        this.audit = audit;
    }

    @GetMapping("/codes/groups")
    public List<CodeGroup> groups() {
        return codes.groups();
    }

    @PostMapping("/codes/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public CodeGroup createGroup(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody CodeGroup g) {
        admin(user);
        CodeGroup created = codes.createGroup(g, user.userId());
        audit.log(Action.CREATE, "code-group:" + g.groupCode(), null);
        return created;
    }

    @PutMapping("/codes/groups/{groupCode}")
    public CodeGroup updateGroup(@AuthenticationPrincipal AuthUser user, @PathVariable String groupCode,
            @RequestBody CodeGroup g) {
        admin(user);
        if (g.name() == null || g.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                    Map.of("name", "그룹명을 입력해주세요."));
        }
        CodeGroup updated = codes.updateGroup(groupCode, g, user.userId());
        audit.log(Action.UPDATE, "code-group:" + groupCode, null);
        return updated;
    }

    @DeleteMapping("/codes/groups/{groupCode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@AuthenticationPrincipal AuthUser user, @PathVariable String groupCode) {
        admin(user);
        codes.deleteGroup(groupCode);
        audit.log(Action.DELETE, "code-group:" + groupCode, null);
    }

    @GetMapping("/codes/groups/{groupCode}/codes")
    public List<Code> list(@PathVariable String groupCode) {
        return codes.codes(groupCode);
    }

    @PutMapping("/codes/groups/{groupCode}/codes")
    public List<Code> save(@AuthenticationPrincipal AuthUser user, @PathVariable String groupCode,
            @RequestBody List<Code> body) {
        admin(user);
        List<Code> saved = codes.saveCodes(groupCode, body);
        audit.log(Action.UPDATE, "code-group:" + groupCode, "코드 " + saved.size() + "건 저장");
        return saved;
    }

    @GetMapping("/codes")
    public Map<String, List<Code>> active(@RequestParam String groups) {
        return codes.activeCodes(Arrays.stream(groups.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .distinct().toList());
    }

    // ── 용어 ──

    @GetMapping("/terms")
    public PageDto<Term> terms(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return codes.terms(q, page, size);
    }

    @GetMapping("/terms/{termId}")
    public Term term(@PathVariable String termId) {
        return codes.term(termId);
    }

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public Term createTerm(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody Term t) {
        admin(user);
        Term created = codes.createTerm(t, user.userId());
        audit.log(Action.CREATE, "term:" + created.termId(), t.term());
        return created;
    }

    @PutMapping("/terms/{termId}")
    public Term updateTerm(@AuthenticationPrincipal AuthUser user, @PathVariable String termId,
            @Valid @RequestBody Term t) {
        admin(user);
        Term updated = codes.updateTerm(termId, t, user.userId());
        audit.log(Action.UPDATE, "term:" + termId, t.term());
        return updated;
    }

    @DeleteMapping("/terms/{termId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTerm(@AuthenticationPrincipal AuthUser user, @PathVariable String termId) {
        admin(user);
        codes.deleteTerm(termId);
        audit.log(Action.DELETE, "term:" + termId, null);
    }

    private static void admin(AuthUser user) {
        if (!user.isSysAdmin()) {
            throw ApiException.forbidden();
        }
    }
}
