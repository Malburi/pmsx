package kr.pmsx.as.viewer;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import kr.pmsx.as.viewer.ViewerDtos.Analysis;
import kr.pmsx.as.viewer.ViewerDtos.CrudMatrix;
import kr.pmsx.as.viewer.ViewerDtos.MenuNode;
import kr.pmsx.as.viewer.ViewerDtos.NodeDetail;
import kr.pmsx.as.viewer.ViewerDtos.ScreenDetail;
import kr.pmsx.as.viewer.ViewerDtos.SearchHit;
import kr.pmsx.as.viewer.ViewerDtos.Workflow;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class ViewerController {

    private final ViewerService viewer;
    private final WorkflowService workflow;
    private final AuditService audit;

    public ViewerController(ViewerService viewer, WorkflowService workflow, AuditService audit) {
        this.viewer = viewer;
        this.workflow = workflow;
        this.audit = audit;
    }

    @GetMapping("/systems/{systemId}/menu-tree")
    public List<MenuNode> menuTree(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId) {
        return viewer.menuTree(user, systemId);
    }

    @GetMapping("/screens/{screenId}")
    public ScreenDetail screen(@AuthenticationPrincipal AuthUser user, @PathVariable String screenId) {
        return viewer.screen(user, screenId);
    }

    @GetMapping("/captures/{captureId}/image")
    public ResponseEntity<byte[]> captureImage(@AuthenticationPrincipal AuthUser user,
            @PathVariable String captureId, @RequestParam(name = "download", required = false) String download) {
        FileStorageService.StoredFile f = viewer.captureImage(user, captureId);
        boolean asDownload = "1".equals(download) || "true".equalsIgnoreCase(download);
        var builder = ResponseEntity.ok();
        if (asDownload) {
            // 화면 표시용 조회는 기록하지 않고, 원본 내려받기만 DOWNLOAD 로 기록 (로그 과다 방지)
            audit.log(AuditService.Action.DOWNLOAD, "capture:" + captureId, null);
            builder.header("Content-Disposition", "attachment; filename=\"capture-" + captureId
                    + (f.mime().contains("png") ? ".png" : ".jpg") + "\"");
        }
        return builder
                .contentType(MediaType.parseMediaType(f.mime()))
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                .body(f.content());
    }

    @GetMapping("/elements/{elementId}/workflow")
    public Workflow workflow(@AuthenticationPrincipal AuthUser user, @PathVariable String elementId) {
        return workflow.workflow(user, elementId);
    }

    @GetMapping("/nodes/{nodeId}")
    public NodeDetail node(@AuthenticationPrincipal AuthUser user, @PathVariable String nodeId) {
        NodeDetail n = workflow.node(user, nodeId);
        if (n.snippet() != null && !n.snippet().isEmpty()) {
            audit.log(AuditService.Action.VIEW_SOURCE, "node:" + n.key(), n.file());
        }
        return n;
    }

    @GetMapping("/screens/{screenId}/crud")
    public CrudMatrix crud(@AuthenticationPrincipal AuthUser user, @PathVariable String screenId) {
        return workflow.crud(user, screenId);
    }

    @GetMapping("/systems/{systemId}/search")
    public List<SearchHit> search(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestParam(name = "q", required = false) String q) {
        return viewer.search(user, systemId, q);
    }

    @PatchMapping("/screens/{screenId}/analysis")
    public Analysis patchAnalysis(@AuthenticationPrincipal AuthUser user, @PathVariable String screenId,
            @RequestBody Map<String, Object> body) {
        return viewer.patchAnalysis(user, screenId, body);
    }
}
