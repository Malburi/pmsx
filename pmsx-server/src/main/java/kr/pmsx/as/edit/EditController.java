package kr.pmsx.as.edit;

import kr.pmsx.as.edit.EditService.EdgeCreate;
import kr.pmsx.as.edit.EditService.EdgeInfo;
import kr.pmsx.as.edit.EditService.EdgePatch;
import kr.pmsx.as.edit.EditService.ElementRequest;
import kr.pmsx.as.edit.EditService.NodeSummary;
import kr.pmsx.as.viewer.ViewerDtos.Capture;
import kr.pmsx.as.viewer.ViewerDtos.ElementSummary;
import kr.pmsx.common.PageDto;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** 핫스팟 편집기·연결 보정 (11 명세 §1). */
@RestController
@RequestMapping("/api/v1")
public class EditController {

    private final EditService edit;

    public EditController(EditService edit) {
        this.edit = edit;
    }

    @PostMapping("/screens/{screenId}/elements")
    @ResponseStatus(HttpStatus.CREATED)
    public ElementSummary createElement(@AuthenticationPrincipal AuthUser user, @PathVariable String screenId,
            @RequestBody ElementRequest req) {
        return edit.createElement(user, screenId, req);
    }

    @PutMapping("/elements/{elementId}")
    public ElementSummary updateElement(@AuthenticationPrincipal AuthUser user, @PathVariable String elementId,
            @RequestBody ElementRequest req) {
        return edit.updateElement(user, elementId, req);
    }

    @DeleteMapping("/elements/{elementId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteElement(@AuthenticationPrincipal AuthUser user, @PathVariable String elementId) {
        edit.deleteElement(user, elementId);
    }

    @PostMapping("/elements/{elementId}/restore")
    public ElementSummary restore(@AuthenticationPrincipal AuthUser user, @PathVariable String elementId) {
        return edit.restoreElement(user, elementId);
    }

    @PostMapping("/screens/{screenId}/captures")
    @ResponseStatus(HttpStatus.CREATED)
    public Capture uploadCapture(@AuthenticationPrincipal AuthUser user, @PathVariable String screenId,
            @RequestPart("file") MultipartFile file, @RequestParam(required = false) Integer width,
            @RequestParam(required = false) Integer height) {
        return edit.uploadCapture(user, screenId, file, width, height);
    }

    @GetMapping("/systems/{systemId}/nodes")
    public PageDto<NodeSummary> nodes(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestParam(required = false) String q, @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return edit.nodes(user, systemId, q, type, page, size);
    }

    @PostMapping("/edges")
    @ResponseStatus(HttpStatus.CREATED)
    public EdgeInfo createEdge(@AuthenticationPrincipal AuthUser user, @RequestBody EdgeCreate req) {
        return edit.createEdge(user, req);
    }

    @PatchMapping("/edges/{edgeId}")
    public EdgeInfo patchEdge(@AuthenticationPrincipal AuthUser user, @PathVariable String edgeId,
            @RequestBody EdgePatch req) {
        return edit.patchEdge(user, edgeId, req);
    }

    @DeleteMapping("/edges/{edgeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEdge(@AuthenticationPrincipal AuthUser user, @PathVariable String edgeId) {
        edit.deleteEdge(user, edgeId);
    }
}
