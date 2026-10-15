package kr.pmsx.cm.file;

import java.nio.charset.StandardCharsets;
import java.util.List;
import kr.pmsx.cm.file.AttachmentService.FileMeta;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 첨부파일 API (docs/10 §2) */
@RestController
public class AttachmentController {

    private final AttachmentService attachments;

    public AttachmentController(AttachmentService attachments) {
        this.attachments = attachments;
    }

    @PostMapping(path = "/api/v1/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileMeta upload(@AuthenticationPrincipal AuthUser user, @RequestPart("file") MultipartFile file,
            @RequestParam String targetType, @RequestParam String targetId) {
        return attachments.upload(user, file, targetType, targetId);
    }

    @GetMapping("/api/v1/attachments")
    public List<FileMeta> list(@AuthenticationPrincipal AuthUser user, @RequestParam String targetType,
            @RequestParam String targetId) {
        return attachments.list(user, targetType, targetId);
    }

    @DeleteMapping("/api/v1/attachments/{attachId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String attachId) {
        attachments.delete(user, attachId);
    }

    @GetMapping("/api/v1/files/{fileId}")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthUser user, @PathVariable String fileId) {
        FileStorageService.StoredFile f = attachments.download(user, fileId, false);
        String name = attachments.info(fileId).originalName();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(name == null ? fileId : name, StandardCharsets.UTF_8).build().toString())
                .body(f.content());
    }

    @GetMapping("/api/v1/files/{fileId}/preview")
    public ResponseEntity<byte[]> preview(@AuthenticationPrincipal AuthUser user, @PathVariable String fileId) {
        FileStorageService.StoredFile f = attachments.download(user, fileId, true);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.mime()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("Content-Security-Policy", "sandbox")
                .header("X-Content-Type-Options", "nosniff")
                .body(f.content());
    }
}
