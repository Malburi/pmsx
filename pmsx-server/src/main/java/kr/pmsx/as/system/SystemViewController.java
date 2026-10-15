package kr.pmsx.as.system;

import java.util.List;
import kr.pmsx.as.system.SystemViewService.CallTreeNode;
import kr.pmsx.as.system.SystemViewService.Navigation;
import kr.pmsx.as.system.SystemViewService.RunDiff;
import kr.pmsx.as.system.SystemViewService.Usage;
import kr.pmsx.as.viewer.ViewerDtos.CrudMatrix;
import kr.pmsx.common.security.AuthUser;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 시스템 단위 분석 뷰 (11 명세 §4). */
@RestController
@RequestMapping("/api/v1")
public class SystemViewController {

    private final SystemViewService service;

    public SystemViewController(SystemViewService service) {
        this.service = service;
    }

    @GetMapping("/systems/{systemId}/crud")
    public CrudMatrix crud(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestParam(required = false) String by, @RequestParam(required = false) String menuId) {
        return service.crud(user, systemId, by, menuId);
    }

    @GetMapping("/systems/{systemId}/navigation")
    public Navigation navigation(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId) {
        return service.navigation(user, systemId);
    }

    @GetMapping("/nodes/{nodeId}/call-tree")
    public CallTreeNode callTree(@AuthenticationPrincipal AuthUser user, @PathVariable String nodeId,
            @RequestParam(required = false) String direction, @RequestParam(required = false) Integer depth) {
        return service.callTree(user, nodeId, direction, depth);
    }

    @GetMapping("/nodes/{nodeId}/usage")
    public List<Usage> usage(@AuthenticationPrincipal AuthUser user, @PathVariable String nodeId) {
        return service.usage(user, nodeId);
    }

    @GetMapping("/runs/{runId}/diff")
    public RunDiff diff(@AuthenticationPrincipal AuthUser user, @PathVariable String runId) {
        return service.diff(user, runId);
    }
}
