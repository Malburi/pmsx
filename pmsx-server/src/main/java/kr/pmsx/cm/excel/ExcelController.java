package kr.pmsx.cm.excel;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.cm.excel.ExcelService.DatasetDto;
import kr.pmsx.cm.excel.ExcelService.ImportPreview;
import kr.pmsx.cm.excel.ExcelService.ImportResult;
import kr.pmsx.cm.excel.ExcelService.TemplateDto;
import kr.pmsx.common.security.AuthUser;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 엑셀 엔진 API (docs/10 §3) */
@RestController
@RequestMapping("/api/v1/excel")
public class ExcelController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public record CommitRequest(String token) {
    }

    private final ExcelService excel;

    public ExcelController(ExcelService excel) {
        this.excel = excel;
    }

    @GetMapping("/datasets")
    public List<DatasetDto> datasets() {
        return excel.datasets();
    }

    @GetMapping("/export/{datasetId}")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthUser user, @PathVariable String datasetId,
            @RequestParam Map<String, String> query) {
        Map<String, String> params = new HashMap<>(query);
        String templateId = params.remove("templateId");
        ExcelService.Exported out = excel.export(user, datasetId, templateId, params);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(out.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(out.content());
    }

    @PostMapping(path = "/import/{datasetId}/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview validate(@AuthenticationPrincipal AuthUser user, @PathVariable String datasetId,
            @RequestPart("file") MultipartFile file, @RequestParam Map<String, String> query) {
        return excel.validate(user, datasetId, file, new HashMap<>(query));
    }

    @PostMapping("/import/{datasetId}/commit")
    public ImportResult commit(@AuthenticationPrincipal AuthUser user, @PathVariable String datasetId,
            @RequestBody CommitRequest req) {
        return excel.commit(user, datasetId, req.token());
    }

    @GetMapping("/templates")
    public List<TemplateDto> templates(@RequestParam(required = false) String datasetId) {
        return excel.templates(datasetId);
    }

    @GetMapping("/templates/{templateId}")
    public TemplateDto template(@PathVariable String templateId) {
        return excel.template(templateId);
    }

    @PostMapping(path = "/templates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public TemplateDto create(@AuthenticationPrincipal AuthUser user, @RequestPart("meta") String meta,
            @RequestPart("file") MultipartFile file) {
        return excel.saveTemplate(user, null, meta, file);
    }

    @PutMapping(path = "/templates/{templateId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TemplateDto update(@AuthenticationPrincipal AuthUser user, @PathVariable String templateId,
            @RequestPart("meta") String meta, @RequestPart(name = "file", required = false) MultipartFile file) {
        return excel.saveTemplate(user, templateId, meta, file);
    }

    @DeleteMapping("/templates/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String templateId) {
        excel.deleteTemplate(user, templateId);
    }
}
