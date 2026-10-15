package kr.pmsx.pj.wbs;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import kr.pmsx.common.DateParams;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.pj.wbs.WbsService.Patch;
import kr.pmsx.pj.wbs.WbsService.ProjectProgress;
import kr.pmsx.pj.wbs.WbsService.WbsItem;
import kr.pmsx.pj.wbs.WbsTools.Baseline;
import kr.pmsx.pj.wbs.WbsTools.CompareRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** WBS·간트·베이스라인·진척 (FR-PJ-002~008, docs/12 §2). 편집 PM·PL, 베이스라인 PM, 담당자는 본인 작업 진척만. */
@RestController
@RequestMapping("/api/v1")
public class WbsController {

    public record GenerateRequest(String methodologyId, LocalDate startDate, boolean replace) {
    }

    public record NameRequest(String name) {
    }

    private final WbsService wbs;
    private final WbsTools tools;
    private final ProjectAccess access;

    public WbsController(WbsService wbs, WbsTools tools, ProjectAccess access) {
        this.wbs = wbs;
        this.tools = tools;
        this.access = access;
    }

    @GetMapping("/projects/{projectId}/wbs")
    public List<WbsItem> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return wbs.list(projectId);
    }

    @PutMapping("/projects/{projectId}/wbs")
    public List<WbsItem> saveAll(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody List<WbsItem> items) {
        access.forProject(user, projectId).require(Perm.WBS_EDIT);
        return wbs.saveAll(projectId, items, user.userId());
    }

    @PatchMapping("/wbs/{wbsId}")
    public WbsItem patch(@AuthenticationPrincipal AuthUser user, @PathVariable String wbsId, @RequestBody Patch req) {
        var a = access.forProject(user, wbs.projectOf(wbsId));
        boolean editor = a.has(Perm.WBS_EDIT);
        if (!editor && !wbs.isAssignee(wbsId, user.userId())) {
            throw kr.pmsx.common.ApiException.forbidden();
        }
        return wbs.patch(wbsId, req, editor, user.userId());
    }

    @PostMapping("/projects/{projectId}/wbs/generate")
    public List<WbsItem> generate(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody GenerateRequest req) {
        access.forProject(user, projectId).require(Perm.WBS_EDIT);
        return tools.generate(projectId, req.methodologyId(), req.startDate(), req.replace(), user.userId());
    }

    @PostMapping("/projects/{projectId}/wbs/import-msproject")
    public List<WbsItem> importMsProject(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestPart("file") MultipartFile file, @RequestParam(defaultValue = "false") boolean replace)
            throws IOException {
        access.forProject(user, projectId).require(Perm.WBS_EDIT);
        return tools.importMsProject(projectId, file.getBytes(), replace, user.userId());
    }

    @GetMapping("/projects/{projectId}/baselines")
    public List<Baseline> baselines(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        return tools.baselines(projectId);
    }

    @PostMapping("/projects/{projectId}/baselines")
    @ResponseStatus(HttpStatus.CREATED)
    public Baseline createBaseline(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody NameRequest req) {
        access.forProject(user, projectId).require(Perm.BASELINE);
        return tools.createBaseline(projectId, req.name(), user.userId());
    }

    @GetMapping("/baselines/{baselineId}/compare")
    public List<CompareRow> compare(@AuthenticationPrincipal AuthUser user, @PathVariable String baselineId) {
        access.forProject(user, tools.projectOfBaseline(baselineId));
        return tools.compare(baselineId);
    }

    @GetMapping("/projects/{projectId}/progress")
    public ProjectProgress progress(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String asOf) {
        access.forProject(user, projectId);
        return wbs.progress(projectId, DateParams.localDate("asOf", asOf));
    }
}
