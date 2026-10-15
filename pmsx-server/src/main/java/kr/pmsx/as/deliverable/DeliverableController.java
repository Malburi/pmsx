package kr.pmsx.as.deliverable;

import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.pmsx.as.deliverable.DeliverableService.DeliverableType;
import kr.pmsx.as.deliverable.DeliverableService.Output;
import kr.pmsx.common.security.AuthUser;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 산출물 출력 (11 명세 §5). */
@RestController
@RequestMapping("/api/v1/systems/{systemId}/deliverables")
public class DeliverableController {

    private final DeliverableService service;

    public DeliverableController(DeliverableService service) {
        this.service = service;
    }

    @GetMapping
    public List<DeliverableType> types(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId) {
        return service.types(user, systemId);
    }

    @GetMapping("/{type}")
    public ResponseEntity<byte[]> generate(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @PathVariable String type, @RequestParam(required = false) String format,
            @RequestParam(required = false) String screenIds, @RequestParam(required = false) String status,
            @RequestParam(required = false) String templateId) {
        Output out = service.generate(user, systemId, type, format, screenIds, status, templateId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(out.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(out.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(out.content());
    }
}
