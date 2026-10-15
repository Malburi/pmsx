package kr.pmsx.as.ingest;

import java.util.List;
import kr.pmsx.as.ingest.RunDtos.RunSummary;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class RunController {

    private final IngestService ingest;

    public RunController(IngestService ingest) {
        this.ingest = ingest;
    }

    @PostMapping(path = "/api/v1/systems/{systemId}/runs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public RunSummary upload(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId,
            @RequestPart("file") MultipartFile file) {
        return ingest.ingest(user, systemId, file);
    }

    @GetMapping("/api/v1/systems/{systemId}/runs")
    public List<RunSummary> list(@AuthenticationPrincipal AuthUser user, @PathVariable String systemId) {
        return ingest.list(user, systemId);
    }

    @GetMapping("/api/v1/runs/{runId}/report")
    public ResponseEntity<byte[]> report(@AuthenticationPrincipal AuthUser user, @PathVariable String runId) {
        FileStorageService.StoredFile f = ingest.report(user, runId);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header("Content-Security-Policy", "sandbox allow-scripts")   // 패키지 출처 HTML 격리
                .body(f.content());
    }
}
