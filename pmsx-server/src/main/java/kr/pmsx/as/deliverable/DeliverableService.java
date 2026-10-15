package kr.pmsx.as.deliverable;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.as.deliverable.DeliverableData.ElementInfo;
import kr.pmsx.as.deliverable.DeliverableData.ProgramRow;
import kr.pmsx.as.deliverable.DeliverableData.ScreenPick;
import kr.pmsx.as.deliverable.DeliverableData.TableInfo;
import kr.pmsx.as.system.SystemViewService;
import kr.pmsx.as.viewer.GraphReader;
import kr.pmsx.as.viewer.ViewerDtos.Capture;
import kr.pmsx.as.viewer.ViewerDtos.CrudMatrix;
import kr.pmsx.as.viewer.ViewerDtos.CrudRow;
import kr.pmsx.as.viewer.ViewerDtos.TableRef;
import kr.pmsx.as.viewer.ViewerService;
import kr.pmsx.cm.excel.ExcelService;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.common.storage.FileStorageService;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 산출물 출력 (FR-AS-025, 11 명세 §5): 화면정의서·프로그램 사양서·테이블 정의서·CRUD 매트릭스·인터페이스 목록. */
@Service
public class DeliverableService {

    public record DeliverableType(String type, String name, List<String> formats) {
    }

    public record Output(String fileName, String contentType, byte[] content) {
    }

    static final List<DeliverableType> TYPES = List.of(
            new DeliverableType("screen-spec", "화면정의서", List.of("xlsx", "pptx")),
            new DeliverableType("program-spec", "프로그램 사양서", List.of("xlsx", "pptx")),
            new DeliverableType("table-spec", "테이블 정의서", List.of("xlsx")),
            new DeliverableType("crud-matrix", "CRUD 매트릭스", List.of("xlsx")),
            new DeliverableType("interface-list", "인터페이스 목록", List.of("xlsx")));
    /** 고객사 템플릿: ExcelTemplate.datasetId == 산출물 type (11 v0.2). 모든 유형이 xlsx 템플릿을 지원한다. */
    static final Set<String> TEMPLATE_TYPES = Set.of("screen-spec", "program-spec", "table-spec", "crud-matrix",
            "interface-list");
    static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PPTX = "application/vnd.openxmlformats-officedocument.presentationml.presentation";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String KO_FONT = "맑은 고딕";

    private final JdbcTemplate jdbc;
    private final DeliverableData data;
    private final ViewerService viewer;
    private final SystemViewService systemView;
    private final ExcelService excel;
    private final FileStorageService files;
    private final AuditService audit;

    public DeliverableService(JdbcTemplate jdbc, DeliverableData data, ViewerService viewer,
            SystemViewService systemView, ExcelService excel, FileStorageService files, AuditService audit) {
        this.jdbc = jdbc;
        this.data = data;
        this.viewer = viewer;
        this.systemView = systemView;
        this.excel = excel;
        this.files = files;
        this.audit = audit;
    }

    public List<DeliverableType> types(AuthUser user, String systemId) {
        data.checkAccess(user, systemId);
        return TYPES;
    }

    public Output generate(AuthUser user, String systemId, String type, String format, String screenIds,
            String status, String templateId) {
        boolean onlyDone = data.checkAccess(user, systemId);
        DeliverableType t = TYPES.stream().filter(x -> x.type().equals(type)).findFirst()
                .orElseThrow(() -> ApiException.notFound("산출물 유형(" + type + ")"));
        String fmt = format == null || format.isBlank() ? "xlsx" : format;
        if (!t.formats().contains(fmt)) {
            throw ApiException.badRequest("VALIDATION", t.name() + "은(는) " + String.join(", ", t.formats())
                    + " 형식만 지원합니다.");
        }
        String systemName = jdbc.queryForObject("SELECT NAME FROM AS_SYSTEM WHERE SYSTEM_ID = ?", String.class,
                systemId);
        String base = systemName + "_" + t.name().replace(" ", "") + "_" + Times.now().format(STAMP);

        if (templateId != null && !templateId.isBlank()) {
            String datasetId = type;
            if (!TEMPLATE_TYPES.contains(type) || !"xlsx".equals(fmt)) {
                throw ApiException.badRequest("VALIDATION", "고객사 템플릿은 엑셀(xlsx) 형식에만 적용할 수 있습니다.");
            }
            Map<String, String> params = new HashMap<>();
            params.put("systemId", systemId);
            if (screenIds != null) {
                params.put("screenIds", screenIds);
            }
            if (status != null) {
                params.put("status", status);
            }
            ExcelService.Exported ex = excel.export(user, datasetId, templateId, params);   // 감사 로그 포함
            return new Output(base + ".xlsx", XLSX, ex.content());
        }

        byte[] content;
        try {
            content = switch (type) {
                case "screen-spec" -> "pptx".equals(fmt)
                        ? screenSpecPptx(systemName, systemId, data.screens(systemId, screenIds, status, onlyDone))
                        : screenSpecXlsx(systemId, data.screens(systemId, screenIds, status, onlyDone));
                case "program-spec" -> "pptx".equals(fmt)
                        ? programSpecPptx(systemName, systemId, data.screens(systemId, screenIds, status, onlyDone))
                        : programSpecXlsx(systemId, data.screens(systemId, screenIds, status, onlyDone));
                case "table-spec" -> tableSpecXlsx(user, systemId);
                case "crud-matrix" -> crudXlsx(systemId, screenIds, status, onlyDone);
                default -> interfaceXlsx(user, systemId);
            };
        } catch (IOException e) {
            throw new IllegalStateException("산출물 생성 실패", e);
        }
        audit.log(Action.DOWNLOAD, "deliverable:" + type, systemName + " " + fmt,
                jdbc.queryForObject("SELECT PROJECT_ID FROM AS_SYSTEM WHERE SYSTEM_ID = ?", String.class, systemId));
        return new Output(base + "." + fmt, "pptx".equals(fmt) ? PPTX : XLSX, content);
    }

    // ───────────────────────── 엑셀 공통 ─────────────────────────

    private static final class Styles {
        final CellStyle head;
        final CellStyle cell;
        final CellStyle title;
        final CellStyle label;

        Styles(Workbook wb) {
            Font bold = wb.createFont();
            bold.setBold(true);
            Font big = wb.createFont();
            big.setBold(true);
            big.setFontHeightInPoints((short) 14);
            head = wb.createCellStyle();
            head.setFont(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            border(head);
            cell = wb.createCellStyle();
            cell.setWrapText(true);
            cell.setVerticalAlignment(VerticalAlignment.TOP);
            border(cell);
            title = wb.createCellStyle();
            title.setFont(big);
            label = wb.createCellStyle();
            label.setFont(bold);
        }

        private static void border(CellStyle s) {
            s.setBorderTop(BorderStyle.THIN);
            s.setBorderBottom(BorderStyle.THIN);
            s.setBorderLeft(BorderStyle.THIN);
            s.setBorderRight(BorderStyle.THIN);
        }
    }

    private static int writeTable(Sheet sh, Styles st, int startRow, List<String> headers, List<List<Object>> rows) {
        Row hr = sh.createRow(startRow);
        for (int c = 0; c < headers.size(); c++) {
            Cell cell = hr.createCell(c);
            cell.setCellValue(headers.get(c));
            cell.setCellStyle(st.head);
        }
        int r = startRow + 1;
        for (List<Object> row : rows) {
            Row xr = sh.createRow(r++);
            for (int c = 0; c < row.size(); c++) {
                Cell cell = xr.createCell(c);
                Object v = row.get(c);
                if (v instanceof Number n) {
                    cell.setCellValue(n.doubleValue());
                } else {
                    cell.setCellValue(v == null ? "" : String.valueOf(v));
                }
                cell.setCellStyle(st.cell);
            }
        }
        return r;
    }

    private static void widths(Sheet sh, int... chars) {
        for (int i = 0; i < chars.length; i++) {
            sh.setColumnWidth(i, chars[i] * 256);
        }
    }

    private static String sheetName(String raw, Set<String> used) {
        String base = raw.replaceAll("[\\[\\]:*?/\\\\]", "_");
        if (base.isBlank()) {
            base = "Sheet";
        }
        base = base.length() > 28 ? base.substring(0, 28) : base;
        String name = base;
        for (int i = 2; !used.add(name.toLowerCase()); i++) {
            name = base + "~" + i;
        }
        return name;
    }

    private static byte[] bytes(Workbook wb) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            wb.write(bos);
            return bos.toByteArray();
        }
    }

    // ───────────────────────── 화면정의서 ─────────────────────────

    private record ScreenBundle(ScreenPick screen, List<ElementInfo> elements, List<ProgramRow> program,
                                HotspotImage.Result image, List<String> notes) {
    }

    private List<ScreenBundle> bundles(String systemId, List<ScreenPick> screens, boolean withImage) {
        GraphReader g = new GraphReader(jdbc, systemId);
        List<ScreenBundle> out = new ArrayList<>();
        for (ScreenPick s : screens) {
            List<ElementInfo> els = data.elements(s.screenId());
            out.add(new ScreenBundle(s, els, data.program(g, s, els), withImage ? image(s.screenId(), els) : null,
                    notes(s.screenId(), els)));
        }
        return out;
    }

    private HotspotImage.Result image(String screenId, List<ElementInfo> els) {
        Capture c = viewer.latestCapture(screenId);
        if (c == null) {
            return null;
        }
        String fileId = jdbc.queryForObject("SELECT FILE_ID FROM AS_CAPTURE WHERE CAPTURE_ID = ?", String.class,
                c.captureId());
        try {
            return HotspotImage.compose(files.load(fileId).content(), c.scale(), els);
        } catch (IOException | RuntimeException e) {
            return null;     // 이미지 손상 시 표만 출력
        }
    }

    private List<String> notes(String screenId, List<ElementInfo> els) {
        List<String> out = new ArrayList<>();
        jdbc.query("SELECT N.CONTENT, U.NAME FROM AS_NOTE N LEFT JOIN CM_USER U ON U.USER_ID = N.AUTHOR_ID"
                + " WHERE N.TARGET_TYPE = 'SCREEN' AND N.TARGET_ID = ? ORDER BY N.CREATED_AT", rs -> {
                    out.add("[화면] " + rs.getString(1) + " — " + rs.getString(2));
                }, screenId);
        Map<String, ElementInfo> byId = new HashMap<>();
        els.forEach(e -> byId.put(e.elementId(), e));
        jdbc.query("SELECT N.TARGET_ID, N.CONTENT, U.NAME FROM AS_NOTE N JOIN AS_ELEMENT E ON E.ELEMENT_ID = N.TARGET_ID"
                + " LEFT JOIN CM_USER U ON U.USER_ID = N.AUTHOR_ID WHERE N.TARGET_TYPE = 'ELEMENT' AND E.SCREEN_ID = ?"
                + " ORDER BY E.SEQ_NO, N.CREATED_AT", rs -> {
                    ElementInfo e = byId.get(rs.getString(1));
                    String who = e == null ? "요소" : e.seqNo() + ". " + e.label();
                    out.add("[" + who + "] " + rs.getString(2) + " — " + rs.getString(3));
                }, screenId);
        return out;
    }

    private byte[] screenSpecXlsx(String systemId, List<ScreenPick> screens) throws IOException {
        List<ScreenBundle> bundles = bundles(systemId, screens, true);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Set<String> used = new HashSet<>();
            Sheet list = wb.createSheet(sheetName("화면목록", used));
            List<List<Object>> rows = new ArrayList<>();
            int no = 1;
            for (ScreenBundle b : bundles) {
                rows.add(List.of(no++, b.screen().code(), b.screen().name(), String.join(" › ", b.screen().menuPath()),
                        b.screen().status(), b.screen().owner() == null ? "" : b.screen().owner(),
                        b.elements().size()));
            }
            writeTable(list, st, 0, List.of("No", "화면ID", "화면명", "메뉴 경로", "분석 상태", "담당", "요소 수"), rows);
            widths(list, 6, 16, 24, 50, 10, 12, 8);

            CreationHelper helper = wb.getCreationHelper();
            for (ScreenBundle b : bundles) {
                Sheet sh = wb.createSheet(sheetName(b.screen().code(), used));
                widths(sh, 6, 22, 14, 10, 30, 40, 12, 12, 12, 12, 12, 12);
                Row r0 = sh.createRow(0);
                r0.createCell(0).setCellValue("화면정의서 — " + b.screen().name() + " (" + b.screen().code() + ")");
                r0.getCell(0).setCellStyle(st.title);
                Row r1 = sh.createRow(1);
                r1.createCell(0).setCellValue("메뉴: " + String.join(" › ", b.screen().menuPath()) + "   상태: "
                        + b.screen().status() + "   담당: " + (b.screen().owner() == null ? "-" : b.screen().owner()));
                int row = 3;
                if (b.image() != null) {
                    int pic = wb.addPicture(b.image().png(), Workbook.PICTURE_TYPE_PNG);
                    Drawing<?> drawing = sh.createDrawingPatriarch();
                    ClientAnchor anchor = helper.createClientAnchor();
                    anchor.setCol1(0);
                    anchor.setRow1(row);
                    Picture p = drawing.createPicture(anchor, pic);
                    double fit = Math.min(1.0, 1000.0 / b.image().width());
                    p.resize(fit);
                    row += (int) Math.ceil(b.image().height() * fit / 20.0) + 2;
                } else {
                    sh.createRow(row).createCell(0).setCellValue("(캡처 없음)");
                    row += 2;
                }
                sh.createRow(row++).createCell(0).setCellValue("■ 화면 요소");
                List<List<Object>> els = new ArrayList<>();
                for (ElementInfo e : b.elements()) {
                    els.add(List.of(e.seqNo(), e.label(), e.code(), e.type(), e.binding() == null ? "" : e.binding(),
                            String.join("\n", e.events())));
                }
                row = writeTable(sh, st, row, List.of("번호", "라벨", "요소ID", "유형", "바인딩", "이벤트"), els) + 1;
                sh.createRow(row++).createCell(0).setCellValue("■ 이벤트 처리");
                List<List<Object>> evs = new ArrayList<>();
                for (ProgramRow p : b.program()) {
                    evs.add(List.of(p.seqNo() == 0 ? "" : p.seqNo(), p.element(), p.event(),
                            nz(p.handler()), nz(p.apis()), nz(p.tables())));
                }
                row = writeTable(sh, st, row, List.of("번호", "요소", "이벤트", "핸들러", "API", "테이블(CRUD)"), evs) + 1;
                if (!b.notes().isEmpty()) {
                    sh.createRow(row++).createCell(0).setCellValue("■ 업무 규칙 메모");
                    for (String n : b.notes()) {
                        Row nr = sh.createRow(row++);
                        nr.createCell(0).setCellValue(n);
                    }
                }
            }
            return bytes(wb);
        }
    }

    // ───────────────────────── 프로그램 사양서 ─────────────────────────

    private byte[] programSpecXlsx(String systemId, List<ScreenPick> screens) throws IOException {
        List<ScreenBundle> bundles = bundles(systemId, screens, false);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("프로그램사양서");
            List<List<Object>> rows = new ArrayList<>();
            for (ScreenBundle b : bundles) {
                for (ProgramRow p : b.program()) {
                    rows.add(List.of(p.screenCode(), p.screenName(), p.seqNo() == 0 ? "" : p.seqNo(), p.element(),
                            p.event(), nz(p.handler()), nz(p.apis()), nz(p.serverFunctions()), nz(p.sqls()),
                            nz(p.tables())));
                }
            }
            writeTable(sh, st, 0, List.of("화면ID", "화면명", "번호", "요소", "이벤트", "핸들러", "API", "서버 함수", "SQL",
                    "테이블(CRUD)"), rows);
            widths(sh, 14, 18, 6, 22, 10, 22, 28, 34, 34, 28);
            sh.createFreezePane(0, 1);
            return bytes(wb);
        }
    }

    // ───────────────────────── 테이블 정의서 ─────────────────────────

    private byte[] tableSpecXlsx(AuthUser user, String systemId) throws IOException {
        List<TableInfo> tables = data.tables(systemId);
        List<Map<String, Object>> cols = data.tableColumnRows(user, systemId);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Map<String, Integer> colCount = new HashMap<>();
            cols.forEach(c -> colCount.merge(String.valueOf(c.get("table")), 1, Integer::sum));
            Sheet list = wb.createSheet("테이블목록");
            List<List<Object>> rows = new ArrayList<>();
            int no = 1;
            for (TableInfo t : tables) {
                rows.add(List.of(no++, nz(t.schema()), t.name(), nz(t.comment()), nz(t.subjectArea()),
                        colCount.getOrDefault(t.name(), 0)));
            }
            writeTable(list, st, 0, List.of("No", "스키마", "테이블", "설명", "주제영역", "컬럼 수"), rows);
            widths(list, 6, 12, 24, 40, 16, 8);

            Sheet cs = wb.createSheet("컬럼정의");
            List<List<Object>> crow = new ArrayList<>();
            for (Map<String, Object> c : cols) {
                crow.add(List.of(c.get("table"), c.get("no"), c.get("column"), c.get("dataType"), c.get("nullable"),
                        c.get("pk"), c.get("fk"), nz((String) c.get("default")), nz((String) c.get("comment"))));
            }
            writeTable(cs, st, 0, List.of("테이블", "순번", "컬럼", "타입", "NULL", "PK", "FK", "기본값", "설명"), crow);
            widths(cs, 24, 6, 24, 18, 6, 5, 5, 14, 40);
            cs.createFreezePane(0, 1);

            Sheet rs = wb.createSheet("관계");
            List<List<Object>> rrow = new ArrayList<>();
            Map<String, String> nameByKey = new HashMap<>();
            tables.forEach(t -> nameByKey.put(t.key(), t.name()));
            jdbc.query("SELECT CHILD_KEY, PARENT_KEY, COLUMN_PAIRS, KIND, DECISION, EVIDENCE_CNT FROM AS_RELATION"
                    + " WHERE SYSTEM_ID = ? ORDER BY CHILD_KEY, PARENT_KEY", r -> {
                        rrow.add(List.of(nameByKey.getOrDefault(r.getString(1), r.getString(1)),
                                nameByKey.getOrDefault(r.getString(2), r.getString(2)), r.getString(3), r.getString(4),
                                r.getString(5), r.getObject(6) == null ? "" : r.getInt(6)));
                    }, systemId);
            writeTable(rs, st, 0, List.of("자식 테이블", "부모 테이블", "컬럼", "종류", "판정", "근거 수"), rrow);
            widths(rs, 24, 24, 40, 10, 10, 8);
            return bytes(wb);
        }
    }

    // ───────────────────────── CRUD 매트릭스 ─────────────────────────

    private byte[] crudXlsx(String systemId, String screenIds, String status, boolean onlyDone) throws IOException {
        Set<String> ids = new HashSet<>();
        data.screens(systemId, screenIds, status, onlyDone).forEach(s -> ids.add(s.screenId()));
        CrudMatrix m = systemView.crudMatrix(systemId, true, null, ids);
        Map<String, String> codes = new HashMap<>();
        jdbc.query("SELECT SCREEN_ID, SCREEN_CODE FROM AS_SCREEN WHERE SYSTEM_ID = ?", rs -> {
            codes.put(rs.getString(1), rs.getString(2));
        }, systemId);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("CRUD매트릭스");
            List<String> headers = new ArrayList<>(List.of("화면ID", "화면명"));
            m.tables().forEach(t -> headers.add(t.name()));
            List<List<Object>> rows = new ArrayList<>();
            for (CrudRow r : m.rows()) {
                List<Object> row = new ArrayList<>(List.of(nz(codes.get(r.elementId())), r.label()));
                for (TableRef t : m.tables()) {
                    row.add(nz(r.cells().get(t.nodeId())));
                }
                rows.add(row);
            }
            writeTable(sh, st, 0, headers, rows);
            sh.setColumnWidth(0, 16 * 256);
            sh.setColumnWidth(1, 22 * 256);
            for (int i = 2; i < headers.size(); i++) {
                sh.setColumnWidth(i, Math.max(8, Math.min(24, headers.get(i).length() + 2)) * 256);
            }
            sh.createFreezePane(2, 1);
            return bytes(wb);
        }
    }

    // ───────────────────────── 인터페이스 목록 ─────────────────────────

    private byte[] interfaceXlsx(AuthUser user, String systemId) throws IOException {
        List<Map<String, Object>> rows = data.interfaceRows(user, systemId);
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Styles st = new Styles(wb);
            Sheet sh = wb.createSheet("인터페이스목록");
            List<List<Object>> out = new ArrayList<>();
            int no = 1;
            for (Map<String, Object> r : rows) {
                out.add(List.of(no++, r.get("method"), r.get("path"), r.get("serverFunctions"), r.get("screens"),
                        r.get("screenCount")));
            }
            writeTable(sh, st, 0, List.of("No", "메서드", "경로", "서버 함수", "호출 화면", "화면 수"), out);
            widths(sh, 6, 8, 36, 36, 40, 8);
            sh.createFreezePane(0, 1);
            return bytes(wb);
        }
    }

    // ───────────────────────── PPT ─────────────────────────

    private static final double SLIDE_W = 960;
    private static final double SLIDE_H = 540;

    private byte[] screenSpecPptx(String systemName, String systemId, List<ScreenPick> screens) throws IOException {
        List<ScreenBundle> bundles = bundles(systemId, screens, true);
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) SLIDE_W, (int) SLIDE_H));
            titleSlide(ppt, "화면정의서", systemName + " · " + bundles.size() + "개 화면");
            for (ScreenBundle b : bundles) {
                XSLFSlide slide = ppt.createSlide();
                text(slide, b.screen().name() + " (" + b.screen().code() + ")", 24, 16, 912, 34, 20, true);
                text(slide, String.join(" › ", b.screen().menuPath()) + "  ·  " + b.screen().status(), 24, 48, 912, 22,
                        11, false);
                double imgRight = 24;
                if (b.image() != null) {
                    XSLFPictureData pd = ppt.addPicture(b.image().png(), PictureData.PictureType.PNG);
                    XSLFPictureShape pic = slide.createPicture(pd);
                    double maxW = 560;
                    double maxH = 440;
                    double k = Math.min(maxW / b.image().width(), maxH / b.image().height());
                    double w = b.image().width() * k;
                    double h = b.image().height() * k;
                    pic.setAnchor(new Rectangle2D.Double(24, 80, w, h));
                    imgRight = 24 + w + 16;
                }
                List<List<String>> rows = new ArrayList<>();
                for (ElementInfo e : b.elements()) {
                    rows.add(List.of(String.valueOf(e.seqNo()), e.label(), e.type(),
                            e.events().isEmpty() ? "" : e.events().get(0)));
                }
                double tx = b.image() == null ? 24 : imgRight;
                double tw = SLIDE_W - tx - 24;
                List<List<String>> first = rows.subList(0, Math.min(14, rows.size()));
                table(slide, List.of("번호", "라벨", "유형", "이벤트"), first, tx, 80, tw,
                        new double[] {0.1, 0.32, 0.14, 0.44});
                for (int i = 14; i < rows.size(); i += 18) {
                    XSLFSlide more = ppt.createSlide();
                    text(more, b.screen().name() + " — 화면 요소 (계속)", 24, 16, 912, 34, 18, true);
                    table(more, List.of("번호", "라벨", "유형", "이벤트"), rows.subList(i, Math.min(rows.size(), i + 18)),
                            24, 60, 912, new double[] {0.08, 0.3, 0.12, 0.5});
                }
            }
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                ppt.write(bos);
                return bos.toByteArray();
            }
        }
    }

    private byte[] programSpecPptx(String systemName, String systemId, List<ScreenPick> screens) throws IOException {
        List<ScreenBundle> bundles = bundles(systemId, screens, false);
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new Dimension((int) SLIDE_W, (int) SLIDE_H));
            titleSlide(ppt, "프로그램 사양서", systemName + " · " + bundles.size() + "개 화면");
            for (ScreenBundle b : bundles) {
                List<List<String>> rows = new ArrayList<>();
                for (ProgramRow p : b.program()) {
                    rows.add(List.of(p.element() + "\n" + p.event(), nz(p.handler()), nz(p.apis()),
                            nz(p.serverFunctions()), nz(p.sqls()) + (p.tables().isEmpty() ? "" : "\n→ " + p.tables())));
                }
                if (rows.isEmpty()) {
                    rows.add(List.of("(이벤트 없음)", "", "", "", ""));
                }
                for (int i = 0; i < rows.size(); i += 8) {
                    XSLFSlide slide = ppt.createSlide();
                    text(slide, b.screen().name() + " (" + b.screen().code() + ")" + (i > 0 ? " (계속)" : ""), 24, 16,
                            912, 34, 18, true);
                    table(slide, List.of("요소 · 이벤트", "핸들러", "API", "서버 함수", "SQL → 테이블"),
                            rows.subList(i, Math.min(rows.size(), i + 8)), 24, 60, 912,
                            new double[] {0.2, 0.16, 0.18, 0.2, 0.26});
                }
            }
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                ppt.write(bos);
                return bos.toByteArray();
            }
        }
    }

    private static void titleSlide(XMLSlideShow ppt, String title, String sub) {
        XSLFSlide s = ppt.createSlide();
        text(s, title, 60, 200, 840, 60, 36, true);
        text(s, sub + " · " + Times.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), 60, 270, 840, 30, 16,
                false);
    }

    private static void text(XSLFSlide slide, String value, double x, double y, double w, double h, double size,
            boolean bold) {
        XSLFTextBox box = slide.createTextBox();
        box.setAnchor(new Rectangle2D.Double(x, y, w, h));
        XSLFTextParagraph p = box.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(value);
        r.setFontSize(size);
        r.setBold(bold);
        r.setFontFamily(KO_FONT);
    }

    private static void table(XSLFSlide slide, List<String> headers, List<List<String>> rows, double x, double y,
            double w, double[] ratios) {
        XSLFTable t = slide.createTable();
        XSLFTableRow hr = t.addRow();
        for (String h : headers) {
            cell(hr.addCell(), h, true);
        }
        for (List<String> r : rows) {
            XSLFTableRow xr = t.addRow();
            for (String v : r) {
                cell(xr.addCell(), v, false);
            }
        }
        for (int i = 0; i < ratios.length; i++) {
            t.setColumnWidth(i, w * ratios[i]);
        }
        t.setAnchor(new Rectangle2D.Double(x, y, w, 20.0 * (rows.size() + 1)));
    }

    private static void cell(XSLFTableCell c, String v, boolean head) {
        c.clearText();
        XSLFTextParagraph p = c.addNewTextParagraph();
        XSLFTextRun r = p.addNewTextRun();
        r.setText(v == null ? "" : v);
        r.setFontSize(head ? 10.0 : 9.0);
        r.setBold(head);
        r.setFontFamily(KO_FONT);
        c.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.bottom, java.awt.Color.GRAY);
        c.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.top, java.awt.Color.GRAY);
        c.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.left, java.awt.Color.GRAY);
        c.setBorderColor(org.apache.poi.sl.usermodel.TableCell.BorderEdge.right, java.awt.Color.GRAY);
        if (head) {
            c.setFillColor(new java.awt.Color(0xE2, 0xE8, 0xF0));
        }
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    static Map<String, Object> describe(DeliverableType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", t.type());
        m.put("name", t.name());
        m.put("formats", t.formats());
        return m;
    }
}
