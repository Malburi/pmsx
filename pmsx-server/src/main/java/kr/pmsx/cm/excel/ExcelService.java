package kr.pmsx.cm.excel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kr.pmsx.cm.code.CodeService;
import kr.pmsx.cm.excel.ExcelDataset.Column;
import kr.pmsx.cm.file.AttachmentService;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** 엑셀 엔진 (FR-CM-006, docs/10 §3): 데이터셋 레지스트리, 내보내기, 가져오기(검증→커밋), 고객사 템플릿 매핑. */
@Service
public class ExcelService {

    public record DatasetDto(String datasetId, String name, boolean importable, List<String> params,
                             List<Column> columns) {
    }

    public record PreviewRow(int rowNo, Map<String, String> values, Map<String, String> errors) {
    }

    public record ImportPreview(String token, int validCount, int errorCount, List<PreviewRow> rows) {
    }

    public record ImportResult(int inserted, int updated, int skipped) {
    }

    public record Mapping(String sheet, int startRow, Map<String, String> columns, Map<String, String> cells) {
    }

    public record TemplateDto(String templateId, String name, String datasetId, String fileId, Mapping mapping) {
    }

    public record TemplateMeta(String name, String datasetId, Mapping mapping) {
    }

    public record Exported(String fileName, byte[] content) {
    }

    private record Pending(String datasetId, String userId, Map<String, String> params,
                           List<Map<String, String>> rows, int skipped, Instant expiresAt) {
    }

    private static final int PREVIEW_LIMIT = 500;
    private static final int MAX_ROWS = 10_000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");

    static {
        // zip bomb 방어: 압축률 하한 (POI 기본 0.01)
        ZipSecureFile.setMinInflateRatio(0.005);
    }

    private final Map<String, ExcelDataset> datasets = new LinkedHashMap<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final CodeService codes;
    private final JdbcTemplate jdbc;
    private final FileStorageService files;
    private final AttachmentService attachments;
    private final ObjectMapper om;
    private final AuditService audit;

    public ExcelService(List<ExcelDataset> all, CodeService codes, JdbcTemplate jdbc, FileStorageService files,
            AttachmentService attachments, ObjectMapper om, AuditService audit) {
        all.forEach(d -> datasets.put(d.id(), d));
        this.codes = codes;
        this.jdbc = jdbc;
        this.files = files;
        this.attachments = attachments;
        this.om = om;
        this.audit = audit;
    }

    public List<DatasetDto> datasets() {
        return datasets.values().stream()
                .map(d -> new DatasetDto(d.id(), d.name(), d.importable(), d.params(), d.columns())).toList();
    }

    private ExcelDataset dataset(String id) {
        ExcelDataset d = datasets.get(id);
        if (d == null) {
            throw ApiException.notFound("엑셀 데이터셋(" + id + ")");
        }
        return d;
    }

    // ───────────────────────── 내보내기 ─────────────────────────

    public Exported export(AuthUser user, String datasetId, String templateId, Map<String, String> params) {
        ExcelDataset d = dataset(datasetId);
        d.checkAccess(user, params, false);
        List<Map<String, Object>> rows = d.rows(user, params);
        byte[] out;
        try {
            out = templateId == null || templateId.isBlank()
                    ? plain(d, rows)
                    : withTemplate(d, template(templateId), rows, common(d, params, user));
        } catch (IOException e) {
            throw new IllegalStateException("엑셀 생성 실패", e);
        }
        audit.log(Action.DOWNLOAD, "excel:" + datasetId, rows.size() + "행" + (templateId == null ? ""
                : ", 템플릿 " + templateId));
        return new Exported(d.name() + "_" + Times.now().format(STAMP) + ".xlsx", out);
    }

    private byte[] plain(ExcelDataset d, List<Map<String, Object>> rows) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Sheet sh = wb.createSheet(safeSheetName(d.name()));
            CellStyle head = wb.createCellStyle();
            Font bold = wb.createFont();
            bold.setBold(true);
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setBorderBottom(BorderStyle.THIN);
            List<Column> cols = d.columns();
            Row hr = sh.createRow(0);
            int[] widths = new int[cols.size()];
            for (int c = 0; c < cols.size(); c++) {
                Cell cell = hr.createCell(c);
                cell.setCellValue(cols.get(c).header());
                cell.setCellStyle(head);
                widths[c] = displayWidth(cols.get(c).header());
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sh.createRow(r + 1);
                for (int c = 0; c < cols.size(); c++) {
                    Object v = rows.get(r).get(cols.get(c).field());
                    write(row.createCell(c), v);
                    widths[c] = Math.max(widths[c], displayWidth(text(v)));
                }
            }
            for (int c = 0; c < cols.size(); c++) {
                sh.setColumnWidth(c, Math.min(80, widths[c] + 2) * 256);
            }
            sh.createFreezePane(0, 1);
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private byte[] withTemplate(ExcelDataset d, TemplateDto t, List<Map<String, Object>> rows,
            Map<String, String> common) throws IOException {
        if (!t.datasetId().equals(d.id())) {
            throw ApiException.badRequest("VALIDATION", "템플릿의 데이터셋(" + t.datasetId() + ")이 요청과 다릅니다.");
        }
        byte[] tpl = files.load(t.fileId()).content();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(tpl));
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            Mapping m = t.mapping();
            Sheet sh = m.sheet() == null || m.sheet().isBlank() ? wb.getSheetAt(0) : wb.getSheet(m.sheet());
            if (sh == null) {
                throw ApiException.badRequest("VALIDATION", "템플릿에 시트(" + m.sheet() + ")가 없습니다.");
            }
            if (m.cells() != null) {
                for (var e : m.cells().entrySet()) {
                    CellReference ref = new CellReference(e.getValue());
                    Row row = sh.getRow(ref.getRow()) == null ? sh.createRow(ref.getRow()) : sh.getRow(ref.getRow());
                    Cell cell = row.getCell(ref.getCol()) == null ? row.createCell(ref.getCol()) : row.getCell(ref.getCol());
                    cell.setCellValue(common.getOrDefault(e.getKey(), ""));
                }
            }
            int start = Math.max(1, m.startRow()) - 1;
            Row styleRow = sh.getRow(start);
            Map<Integer, CellStyle> styles = new HashMap<>();
            if (styleRow != null) {
                styleRow.forEach(c -> styles.put(c.getColumnIndex(), c.getCellStyle()));
            }
            Map<String, Integer> colIdx = new LinkedHashMap<>();
            if (m.columns() != null) {
                m.columns().forEach((field, letter) -> colIdx.put(field, CellReference.convertColStringToIndex(letter)));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sh.getRow(start + r) == null ? sh.createRow(start + r) : sh.getRow(start + r);
                for (var e : colIdx.entrySet()) {
                    Cell cell = row.getCell(e.getValue()) == null ? row.createCell(e.getValue()) : row.getCell(e.getValue());
                    if (styles.containsKey(e.getValue())) {
                        cell.setCellStyle(styles.get(e.getValue()));
                    }
                    write(cell, rows.get(r).get(e.getKey()));
                }
            }
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    private Map<String, String> common(ExcelDataset d, Map<String, String> params, AuthUser user) {
        Map<String, String> v = new HashMap<>(d.commonValues(params));
        v.put("exportedAt", Times.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        v.put("exportedBy", user.name());
        String systemId = params.get("systemId");
        if (systemId != null) {
            jdbc.query("SELECT S.NAME, P.NAME FROM AS_SYSTEM S JOIN PJ_PROJECT P ON P.PROJECT_ID = S.PROJECT_ID"
                    + " WHERE S.SYSTEM_ID = ?", rs -> {
                        v.putIfAbsent("systemName", rs.getString(1));
                        v.putIfAbsent("projectName", rs.getString(2));
                    }, systemId);
        }
        String projectId = params.get("projectId");
        if (projectId != null) {
            jdbc.query("SELECT NAME FROM PJ_PROJECT WHERE PROJECT_ID = ?",
                    rs -> {
                        v.putIfAbsent("projectName", rs.getString(1));
                    }, projectId);
        }
        v.putIfAbsent("projectName", "");
        v.putIfAbsent("systemName", "");
        return v;
    }

    // ───────────────────────── 가져오기 ─────────────────────────

    public ImportPreview validate(AuthUser user, String datasetId, MultipartFile file, Map<String, String> params) {
        ExcelDataset d = dataset(datasetId);
        if (!d.importable()) {
            throw ApiException.badRequest("VALIDATION", "가져오기를 지원하지 않는 데이터셋입니다.");
        }
        d.checkAccess(user, params, true);
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("VALIDATION", "업로드된 파일이 비어 있습니다.");
        }
        attachments.checkRules(file.getOriginalFilename() == null ? "import.xlsx" : file.getOriginalFilename(),
                file.getSize());
        List<Map<String, String>> raw;
        try (InputStream in = file.getInputStream()) {
            raw = parse(d, in);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException ae) {
                throw ae;
            }
            throw ApiException.badRequest("VALIDATION", "엑셀 파일을 읽을 수 없습니다. (.xlsx 형식인지 확인해주세요)");
        }
        List<PreviewRow> preview = new ArrayList<>();
        List<Map<String, String>> valid = new ArrayList<>();
        int errors = 0;
        for (int i = 0; i < raw.size(); i++) {
            Map<String, String> values = raw.get(i);
            Map<String, String> err = new LinkedHashMap<>(check(d, values));
            if (err.isEmpty()) {
                err.putAll(d.validateRow(values, params));
            }
            if (err.isEmpty()) {
                valid.add(values);
            } else {
                errors++;
            }
            if (preview.size() < PREVIEW_LIMIT) {
                preview.add(new PreviewRow(i + 2, values, err));
            }
        }
        purgeExpired();
        String token = UUID.randomUUID().toString();
        pending.put(token, new Pending(datasetId, user.userId(), Map.copyOf(params), valid, errors,
                Instant.now().plusSeconds(600)));
        return new ImportPreview(token, valid.size(), errors, preview);
    }

    /** 검증을 통과한 행만 반영한다 (오류 행은 제외). */
    public ImportResult commit(AuthUser user, String datasetId, String token) {
        Pending p = token == null ? null : pending.get(token);
        if (p == null || p.expiresAt().isBefore(Instant.now()) || !p.userId().equals(user.userId())
                || !p.datasetId().equals(datasetId)) {
            throw ApiException.badRequest("VALIDATION", "가져오기 토큰이 없거나 만료되었습니다. 다시 검증해주세요.");
        }
        ExcelDataset d = dataset(datasetId);
        d.checkAccess(user, p.params(), true);
        int[] r = d.commit(user, p.rows(), p.params());
        pending.remove(token);
        audit.log(Action.UPLOAD, "excel:" + datasetId, "가져오기 신규 " + r[0] + " / 갱신 " + r[1]
                + " / 제외 " + p.skipped());
        return new ImportResult(r[0], r[1], p.skipped());
    }

    @Scheduled(fixedDelay = 600_000)
    public void purgeExpired() {
        Instant now = Instant.now();
        pending.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    private List<Map<String, String>> parse(ExcelDataset d, InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sh = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            Row header = sh.getRow(sh.getFirstRowNum());
            if (header == null) {
                throw ApiException.badRequest("VALIDATION", "첫 행에 머리글이 없습니다.");
            }
            Map<Integer, Column> byIndex = new LinkedHashMap<>();
            for (Cell c : header) {
                String h = fmt.formatCellValue(c).trim();
                for (Column col : d.columns()) {
                    if (col.header().equals(h) || col.field().equalsIgnoreCase(h)) {
                        byIndex.put(c.getColumnIndex(), col);
                    }
                }
            }
            for (Column col : d.columns()) {
                if (col.required() && !byIndex.containsValue(col)) {
                    throw ApiException.badRequest("VALIDATION", "필수 열이 없습니다: " + col.header());
                }
            }
            List<Map<String, String>> rows = new ArrayList<>();
            for (int r = header.getRowNum() + 1; r <= sh.getLastRowNum(); r++) {
                Row row = sh.getRow(r);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean any = false;
                for (var e : byIndex.entrySet()) {
                    Cell cell = row.getCell(e.getKey());
                    String v = cellText(cell, e.getValue(), fmt);
                    values.put(e.getValue().field(), v);
                    any |= !v.isEmpty();
                }
                if (!any) {
                    continue;
                }
                rows.add(values);
                if (rows.size() > MAX_ROWS) {
                    throw ApiException.badRequest("VALIDATION", "한 번에 " + MAX_ROWS + "행까지 가져올 수 있습니다.");
                }
            }
            return rows;
        }
    }

    private static String cellText(Cell cell, Column col, DataFormatter fmt) {
        if (cell == null) {
            return "";
        }
        if ("date".equals(col.type()) && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().toString();
        }
        if ("number".equals(col.type()) && cell.getCellType() == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        return fmt.formatCellValue(cell).trim();
    }

    private Map<String, String> check(ExcelDataset d, Map<String, String> values) {
        Map<String, String> err = new LinkedHashMap<>();
        for (Column c : d.columns()) {
            String v = values.getOrDefault(c.field(), "");
            if (v.isEmpty()) {
                if (c.required()) {
                    err.put(c.field(), c.header() + " 은(는) 필수입니다.");
                }
                continue;
            }
            switch (c.type()) {
                case "number" -> {
                    try {
                        new BigDecimal(v.replace(",", ""));
                    } catch (NumberFormatException e) {
                        err.put(c.field(), "숫자가 아닙니다: " + v);
                    }
                }
                case "date" -> {
                    try {
                        LocalDate.parse(v);
                    } catch (DateTimeParseException e) {
                        err.put(c.field(), "날짜 형식(yyyy-MM-dd)이 아닙니다: " + v);
                    }
                }
                case "code" -> {
                    if (c.codeGroup() != null && !codes.codeExists(c.codeGroup(), v)) {
                        err.put(c.field(), "코드(" + c.codeGroup() + ")에 없는 값입니다: " + v);
                    }
                }
                default -> {
                }
            }
        }
        return err;
    }

    // ───────────────────────── 템플릿 ─────────────────────────

    public List<TemplateDto> templates(String datasetId) {
        String sql = "SELECT * FROM CM_EXCEL_TEMPLATE" + (datasetId == null ? "" : " WHERE DATASET_ID = ?")
                + " ORDER BY NAME";
        Object[] args = datasetId == null ? new Object[0] : new Object[] {datasetId};
        return jdbc.query(sql, (rs, i) -> toTemplate(rs.getString("TEMPLATE_ID"), rs.getString("NAME"),
                rs.getString("DATASET_ID"), rs.getString("FILE_ID"), rs.getString("MAPPING_JSON")), args);
    }

    public TemplateDto template(String templateId) {
        return jdbc.query("SELECT * FROM CM_EXCEL_TEMPLATE WHERE TEMPLATE_ID = ?",
                (rs, i) -> toTemplate(rs.getString("TEMPLATE_ID"), rs.getString("NAME"), rs.getString("DATASET_ID"),
                        rs.getString("FILE_ID"), rs.getString("MAPPING_JSON")), templateId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("엑셀 템플릿"));
    }

    @Transactional
    public TemplateDto saveTemplate(AuthUser user, String templateId, String metaJson, MultipartFile file) {
        if (!user.isSysAdmin()) {
            throw ApiException.forbidden();
        }
        TemplateMeta meta;
        try {
            meta = om.readValue(metaJson, TemplateMeta.class);
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("VALIDATION", "meta JSON 형식이 올바르지 않습니다.");
        }
        validateMeta(meta);
        String fileId = null;
        if (file != null && !file.isEmpty()) {
            String name = file.getOriginalFilename() == null ? "template.xlsx" : file.getOriginalFilename();
            if (!name.toLowerCase().endsWith(".xlsx")) {
                throw ApiException.badRequest("FILE_TYPE_NOT_ALLOWED", "템플릿은 .xlsx 파일이어야 합니다.");
            }
            attachments.checkRules(name, file.getSize());
            try {
                byte[] content = file.getBytes();
                try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
                    if (meta.mapping().sheet() != null && !meta.mapping().sheet().isBlank()
                            && wb.getSheet(meta.mapping().sheet()) == null) {
                        throw ApiException.badRequest("VALIDATION", "템플릿에 시트(" + meta.mapping().sheet() + ")가 없습니다.");
                    }
                }
                fileId = files.save(content, name,
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", user.userId());
            } catch (IOException e) {
                throw ApiException.badRequest("VALIDATION", "템플릿 엑셀 파일을 읽을 수 없습니다.");
            }
        }
        String mapping;
        try {
            mapping = om.writeValueAsString(meta.mapping());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (templateId == null) {
            if (fileId == null) {
                throw ApiException.badRequest("VALIDATION", "템플릿 파일(file)이 필요합니다.");
            }
            templateId = Ulid.next();
            jdbc.update("INSERT INTO CM_EXCEL_TEMPLATE (TEMPLATE_ID, NAME, DATASET_ID, FILE_ID, MAPPING_JSON, CREATED_AT,"
                    + " CREATED_BY) VALUES (?, ?, ?, ?, ?, ?, ?)", templateId, meta.name(), meta.datasetId(), fileId,
                    mapping, Times.now(), user.userId());
            audit.log(Action.CREATE, "excel-template:" + templateId, meta.name());
        } else {
            template(templateId);
            jdbc.update("UPDATE CM_EXCEL_TEMPLATE SET NAME = ?, DATASET_ID = ?, MAPPING_JSON = ?, UPDATED_AT = ?,"
                    + " UPDATED_BY = ? WHERE TEMPLATE_ID = ?", meta.name(), meta.datasetId(), mapping, Times.now(),
                    user.userId(), templateId);
            if (fileId != null) {
                jdbc.update("UPDATE CM_EXCEL_TEMPLATE SET FILE_ID = ? WHERE TEMPLATE_ID = ?", fileId, templateId);
            }
            audit.log(Action.UPDATE, "excel-template:" + templateId, meta.name());
        }
        return template(templateId);
    }

    @Transactional
    public void deleteTemplate(AuthUser user, String templateId) {
        if (!user.isSysAdmin()) {
            throw ApiException.forbidden();
        }
        template(templateId);
        jdbc.update("DELETE FROM CM_EXCEL_TEMPLATE WHERE TEMPLATE_ID = ?", templateId);
        audit.log(Action.DELETE, "excel-template:" + templateId, null);
    }

    private void validateMeta(TemplateMeta meta) {
        Map<String, String> err = new LinkedHashMap<>();
        if (meta.name() == null || meta.name().isBlank()) {
            err.put("name", "템플릿 이름을 입력해주세요.");
        }
        if (meta.datasetId() == null || !datasets.containsKey(meta.datasetId())) {
            err.put("datasetId", "데이터셋을 선택해주세요.");
        }
        if (meta.mapping() == null) {
            err.put("mapping", "셀 매핑이 필요합니다.");
        } else {
            if (meta.mapping().startRow() < 1) {
                err.put("mapping.startRow", "시작 행은 1 이상입니다.");
            }
            if (meta.mapping().columns() != null) {
                List<String> fields = datasets.containsKey(meta.datasetId())
                        ? datasets.get(meta.datasetId()).columns().stream().map(Column::field).toList() : List.of();
                meta.mapping().columns().forEach((f, letter) -> {
                    if (!fields.isEmpty() && !fields.contains(f)) {
                        err.put("mapping.columns." + f, "데이터셋에 없는 필드입니다.");
                    }
                    if (letter == null || !letter.matches("^[A-Za-z]{1,3}$")) {
                        err.put("mapping.columns." + f, "열은 'B' 같은 열 문자여야 합니다.");
                    }
                });
            }
            if (meta.mapping().cells() != null) {
                meta.mapping().cells().forEach((k, ref) -> {
                    if (ref == null || !ref.matches("^[A-Za-z]{1,3}[0-9]{1,7}$")) {
                        err.put("mapping.cells." + k, "셀은 'C3' 같은 주소여야 합니다.");
                    }
                });
            }
        }
        if (!err.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.", err);
        }
    }

    private TemplateDto toTemplate(String id, String name, String datasetId, String fileId, String mappingJson) {
        try {
            return new TemplateDto(id, name, datasetId, fileId, om.readValue(mappingJson, Mapping.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ───────────────────────── 유틸 ─────────────────────────

    private static void write(Cell cell, Object v) {
        if (v == null) {
            cell.setBlank();
        } else if (v instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (v instanceof Boolean b) {
            cell.setCellValue(b ? "Y" : "N");
        } else {
            cell.setCellValue(text(v));
        }
    }

    private static String text(Object v) {
        if (v == null) {
            return "";
        }
        if (v instanceof OffsetDateTime t) {
            return t.atZoneSameInstant(Times.ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (v instanceof Boolean b) {
            return b ? "Y" : "N";
        }
        return String.valueOf(v);
    }

    private static int displayWidth(String s) {
        int w = 0;
        for (char c : s.toCharArray()) {
            w += c > 0x2E80 ? 2 : 1;
        }
        return w;
    }

    private static String safeSheetName(String n) {
        String s = n.replaceAll("[\\\\/?*\\[\\]:]", "_");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }
}
