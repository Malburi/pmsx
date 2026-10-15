package kr.pmsx.pj.wbs;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.WorkCalendar;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.wbs.MethodologyController.Methodology;
import kr.pmsx.pj.wbs.MethodologyController.Phase;
import kr.pmsx.pj.wbs.MethodologyController.Task;
import kr.pmsx.pj.wbs.WbsService.WbsItem;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** 방법론으로 WBS 생성, MS Project XML 가져오기, 베이스라인 (docs/12 §2). */
@Service
public class WbsTools {

    public record Baseline(String baselineId, String name, OffsetDateTime createdAt, UserRef createdBy) {
    }

    public record CompareRow(String wbsId, String wbsNo, String name, LocalDate planStart, LocalDate planEnd,
                             LocalDate baseStart, LocalDate baseEnd, Integer startVarDays, Integer endVarDays) {
    }

    private final JdbcTemplate jdbc;
    private final WbsService wbs;
    private final MethodologyController methodologies;
    private final WorkCalendar calendar;
    private final PjSupport support;
    private final AuditService audit;

    public WbsTools(JdbcTemplate jdbc, WbsService wbs, MethodologyController methodologies, WorkCalendar calendar,
            PjSupport support, AuditService audit) {
        this.jdbc = jdbc;
        this.wbs = wbs;
        this.methodologies = methodologies;
        this.calendar = calendar;
        this.support = support;
        this.audit = audit;
    }

    // ───────────────────────── 방법론 → WBS ─────────────────────────

    /** 단계 = 1레벨, 태스크 = 2레벨. 태스크는 순서대로 영업일 기준 연속 배치(FS), 산출물은 대장에 함께 생성. */
    @Transactional
    public List<WbsItem> generate(String projectId, String methodologyId, LocalDate startDate, boolean replace,
            String userId) {
        if (startDate == null) {
            throw PjSupport.validation("startDate", "시작일을 입력해주세요.");
        }
        Methodology m = methodologies.find(methodologyId);
        WorkCalendar.Days cal = calendar.load();
        int baseOrd = prepare(projectId, replace);
        int ord = baseOrd;
        LocalDate cursor = cal.nextWorkday(startDate);
        String prevTask = null;
        int phaseNo = 0;
        for (Phase p : m.phases()) {
            WbsService.Row phase = new WbsService.Row();
            phase.id = Ulid.next();
            phase.name = p.name();
            phase.ord = ord++;
            phase.weight = p.tasks().stream().mapToDouble(Task::weight).sum();
            phase.status = "TODO";
            phase.wbsNo = String.valueOf(++phaseNo);
            wbs.insertRow(projectId, phase, userId);
            int taskNo = 0;
            for (Task tk : p.tasks()) {
                WbsService.Row r = new WbsService.Row();
                r.id = Ulid.next();
                r.parentId = phase.id;
                r.name = tk.name();
                r.ord = ord++;
                r.weight = tk.weight();
                r.status = "TODO";
                r.wbsNo = phase.wbsNo + "." + ++taskNo;
                r.planStart = cursor;
                r.planEnd = cal.addWorkdays(cursor, tk.durationDays());
                cursor = cal.nextWorkday(r.planEnd.plusDays(1));
                String firstDeliverable = null;
                for (String dn : tk.deliverables()) {
                    String did = Ulid.next();
                    jdbc.update("""
                            INSERT INTO DL_DELIVERABLE (DELIVERABLE_ID, PROJECT_ID, PHASE, NAME, DUE_DT, STATUS, WBS_ID,
                                                        CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
                            VALUES (?, ?, ?, ?, ?, 'DRAFT', ?, ?, ?, ?, ?)""", did, projectId, p.name(), dn, r.planEnd,
                            r.id, Times.now(), userId, Times.now(), userId);
                    if (firstDeliverable == null) {
                        firstDeliverable = did;
                    }
                }
                r.deliverableId = firstDeliverable;
                wbs.insertRow(projectId, r, userId);
                if (prevTask != null) {
                    jdbc.update("INSERT INTO PJ_WBS_DEP (PRED_ID, SUCC_ID, DEP_TYPE, LAG_DAYS) VALUES (?, ?, 'FS', 0)",
                            prevTask, r.id);
                }
                prevTask = r.id;
            }
        }
        renumber(projectId);
        jdbc.update("UPDATE PJ_PROJECT SET METHODOLOGY_ID = ? WHERE PROJECT_ID = ?", methodologyId, projectId);
        audit.log(Action.CREATE, "wbs:" + projectId, "방법론으로 WBS 생성: " + m.name(), projectId);
        return wbs.list(projectId);
    }

    /** replace 면 기존 WBS 삭제(작성 중 산출물은 함께 삭제, 그 외는 연결 해제), 아니면 기존 뒤에 붙일 ORD 반환 */
    private int prepare(String projectId, boolean replace) {
        if (replace) {
            jdbc.update("DELETE FROM PJ_WBS_DEP WHERE SUCC_ID IN (SELECT WBS_ID FROM PJ_WBS WHERE PROJECT_ID = ?)"
                    + " OR PRED_ID IN (SELECT WBS_ID FROM PJ_WBS WHERE PROJECT_ID = ?)", projectId, projectId);
            jdbc.update("DELETE FROM RS_ASSIGN WHERE PROJECT_ID = ? AND TARGET_TYPE = 'WBS'", projectId);
            jdbc.update("DELETE FROM DL_DELIVERABLE WHERE PROJECT_ID = ? AND STATUS = 'DRAFT' AND WBS_ID IS NOT NULL"
                    + " AND NOT EXISTS (SELECT 1 FROM CM_ATTACH A WHERE A.TARGET_TYPE = 'DL_DELIVERABLE'"
                    + " AND A.TARGET_ID = DL_DELIVERABLE.DELIVERABLE_ID AND A.DELETED = FALSE)", projectId);
            jdbc.update("UPDATE DL_DELIVERABLE SET WBS_ID = NULL WHERE PROJECT_ID = ?", projectId);
            jdbc.update("DELETE FROM PJ_WBS WHERE PROJECT_ID = ?", projectId);
            return 0;
        }
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(ORD), -1) FROM PJ_WBS WHERE PROJECT_ID = ?",
                Integer.class, projectId);
        return max == null ? 0 : max + 1;
    }

    private void renumber(String projectId) {
        WbsService.Tree t = wbs.tree(projectId, calendar.load());
        WbsService.numberTree(t);
        for (WbsService.Row r : t.rows) {
            jdbc.update("UPDATE PJ_WBS SET WBS_NO = ? WHERE WBS_ID = ?", r.wbsNo, r.id);
        }
    }

    // ───────────────────────── MS Project XML ─────────────────────────

    @Transactional
    public List<WbsItem> importMsProject(String projectId, byte[] xml, boolean replace, String userId) {
        Document doc = parse(xml);
        NodeList taskNodes = doc.getElementsByTagNameNS("*", "Task");
        if (taskNodes.getLength() == 0) {
            taskNodes = doc.getElementsByTagName("Task");
        }
        record T(String uid, String name, int level, LocalDate start, LocalDate finish, boolean milestone,
                 double pct, List<String[]> preds) {
        }
        List<T> tasks = new ArrayList<>();
        for (int i = 0; i < taskNodes.getLength(); i++) {
            Element e = (Element) taskNodes.item(i);
            if (!"Tasks".equals(localName(e.getParentNode()))) {
                continue;
            }
            String uid = text(e, "UID");
            String name = text(e, "Name");
            if (uid == null || "0".equals(uid) || name == null || name.isBlank()) {
                continue;   // UID 0 = 프로젝트 요약 작업
            }
            int level = parseInt(text(e, "OutlineLevel"), 1);
            List<String[]> preds = new ArrayList<>();
            for (Element pl : children(e, "PredecessorLink")) {
                String type = switch (parseInt(text(pl, "Type"), 1)) {
                    case 0 -> "FF";
                    case 2 -> "SF";
                    case 3 -> "SS";
                    default -> "FS";
                };
                int lagTenthMinutes = parseInt(text(pl, "LinkLag"), 0);
                int lagDays = Math.round(lagTenthMinutes / 4800f);   // 1일 = 8시간 = 4800 (0.1분 단위)
                preds.add(new String[] {text(pl, "PredecessorUID"), type, String.valueOf(lagDays)});
            }
            tasks.add(new T(uid, name.trim(), Math.max(1, level), date(text(e, "Start")), date(text(e, "Finish")),
                    "1".equals(text(e, "Milestone")), parseDouble(text(e, "PercentComplete")), preds));
        }
        if (tasks.isEmpty()) {
            throw PjSupport.validation("file", "MS Project XML 에서 작업(Task)을 찾지 못했습니다.");
        }
        int ord = prepare(projectId, replace);
        Map<String, String> idByUid = new HashMap<>();
        List<String> stack = new ArrayList<>();   // 레벨별 마지막 ID
        for (T t : tasks) {
            WbsService.Row r = new WbsService.Row();
            r.id = Ulid.next();
            idByUid.put(t.uid(), r.id);
            while (stack.size() >= t.level()) {
                stack.remove(stack.size() - 1);
            }
            r.parentId = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            stack.add(r.id);
            r.name = t.name().length() > 300 ? t.name().substring(0, 300) : t.name();
            r.ord = ord++;
            r.planStart = t.start();
            r.planEnd = t.finish() != null && t.start() != null && t.finish().isBefore(t.start()) ? t.start()
                    : t.finish();
            r.weight = 1;
            r.progress = WbsService.clampProgress(t.pct());
            r.milestone = t.milestone();
            r.status = r.progress >= 100 ? "DONE" : r.progress > 0 ? "IN_PROGRESS" : "TODO";
            r.wbsNo = "0";
            wbs.insertRow(projectId, r, userId);
        }
        List<Cpm.Dep> deps = new ArrayList<>();
        for (T t : tasks) {
            for (String[] p : t.preds()) {
                String pred = idByUid.get(p[0]);
                String succ = idByUid.get(t.uid());
                if (pred != null && succ != null && !pred.equals(succ)) {
                    deps.add(new Cpm.Dep(pred, succ, p[1], Integer.parseInt(p[2])));
                }
            }
        }
        try {
            Cpm.checkCycle(idByUid.values(), deps);
        } catch (Cpm.CycleException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "WBS_CYCLE", e.getMessage());
        }
        for (Cpm.Dep d : deps) {
            jdbc.update("INSERT INTO PJ_WBS_DEP (PRED_ID, SUCC_ID, DEP_TYPE, LAG_DAYS) VALUES (?, ?, ?, ?)", d.pred(),
                    d.succ(), d.type(), d.lag());
        }
        renumber(projectId);
        audit.log(Action.UPLOAD, "wbs:" + projectId, "MS Project 가져오기 " + tasks.size() + "건", projectId);
        return wbs.list(projectId);
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw PjSupport.validation("file", "XML 을 읽을 수 없습니다: " + e.getMessage());
        }
    }

    private static String localName(Node n) {
        if (n == null) {
            return null;
        }
        return n.getLocalName() != null ? n.getLocalName() : n.getNodeName();
    }

    private static List<Element> children(Element e, String name) {
        List<Element> out = new ArrayList<>();
        for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (c instanceof Element el && name.equals(localName(el))) {
                out.add(el);
            }
        }
        return out;
    }

    private static String text(Element e, String name) {
        List<Element> c = children(e, name);
        return c.isEmpty() ? null : c.get(0).getTextContent().trim();
    }

    private static LocalDate date(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(v).toLocalDate();
        } catch (RuntimeException e) {
            try {
                return LocalDate.parse(v.substring(0, 10));
            } catch (RuntimeException e2) {
                return null;
            }
        }
    }

    private static int parseInt(String v, int def) {
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String v) {
        try {
            return v == null ? 0 : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ───────────────────────── 베이스라인 ─────────────────────────

    public List<Baseline> baselines(String projectId) {
        List<String[]> rows = jdbc.query("SELECT BASELINE_ID, NAME, CREATED_BY FROM PJ_BASELINE WHERE PROJECT_ID = ?"
                + " ORDER BY CREATED_AT DESC, BASELINE_ID DESC", (rs, i) -> new String[] {rs.getString(1),
                rs.getString(2), rs.getString(3)}, projectId);
        Map<String, String> names = support.names(rows.stream().map(r -> r[2]).toList());
        Map<String, OffsetDateTime> at = new HashMap<>();
        jdbc.query("SELECT BASELINE_ID, CREATED_AT FROM PJ_BASELINE WHERE PROJECT_ID = ?",
                rs -> {
                    at.put(rs.getString(1), Times.read(rs, "CREATED_AT"));
                }, projectId);
        return rows.stream().map(r -> new Baseline(r[0], r[1], at.get(r[0]), PjSupport.ref(names, r[2]))).toList();
    }

    @Transactional
    public Baseline createBaseline(String projectId, String name, String userId) {
        String n = PjSupport.required("name", name, 200);
        String id = Ulid.next();
        jdbc.update("INSERT INTO PJ_BASELINE (BASELINE_ID, PROJECT_ID, NAME, CREATED_AT, CREATED_BY) VALUES (?, ?, ?, ?, ?)",
                id, projectId, n, Times.now(), userId);
        for (WbsItem it : wbs.list(projectId)) {
            jdbc.update("INSERT INTO PJ_BASELINE_ITEM (BASELINE_ID, WBS_ID, WBS_NO, NAME, PLAN_START, PLAN_END)"
                    + " VALUES (?, ?, ?, ?, ?, ?)", id, it.wbsId(), it.wbsNo(), it.name(), it.planStart(), it.planEnd());
        }
        audit.log(Action.CREATE, "baseline:" + id, "베이스라인 저장 " + n, projectId);
        return baselines(projectId).stream().filter(b -> b.baselineId().equals(id)).findFirst().orElseThrow();
    }

    public String projectOfBaseline(String baselineId) {
        return jdbc.query("SELECT PROJECT_ID FROM PJ_BASELINE WHERE BASELINE_ID = ?", (rs, i) -> rs.getString(1),
                baselineId).stream().findFirst().orElseThrow(() -> ApiException.notFound("베이스라인"));
    }

    /** 현재 WBS 와 베이스라인 비교. 편차는 영업일 기준(+ 지연, - 단축). 베이스라인에만 있는 항목도 포함. */
    public List<CompareRow> compare(String baselineId) {
        String projectId = projectOfBaseline(baselineId);
        WorkCalendar.Days cal = calendar.load();
        Map<String, LocalDate[]> base = new HashMap<>();
        Map<String, String[]> baseMeta = new HashMap<>();
        List<String> baseOrder = new ArrayList<>();
        jdbc.query("SELECT WBS_ID, WBS_NO, NAME, PLAN_START, PLAN_END FROM PJ_BASELINE_ITEM WHERE BASELINE_ID = ?",
                rs -> {
                    base.put(rs.getString(1), new LocalDate[] {rs.getObject(4, LocalDate.class),
                            rs.getObject(5, LocalDate.class)});
                    baseMeta.put(rs.getString(1), new String[] {rs.getString(2), rs.getString(3)});
                    baseOrder.add(rs.getString(1));
                }, baselineId);
        List<CompareRow> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (WbsItem it : wbs.list(projectId)) {
            LocalDate[] b = base.get(it.wbsId());
            seen.add(it.wbsId());
            out.add(new CompareRow(it.wbsId(), it.wbsNo(), it.name(), it.planStart(), it.planEnd(),
                    b == null ? null : b[0], b == null ? null : b[1], var(cal, b == null ? null : b[0], it.planStart()),
                    var(cal, b == null ? null : b[1], it.planEnd())));
        }
        for (String id : baseOrder) {
            if (!seen.contains(id)) {
                LocalDate[] b = base.get(id);
                out.add(new CompareRow(id, baseMeta.get(id)[0], baseMeta.get(id)[1] + " (삭제됨)", null, null, b[0],
                        b[1], null, null));
            }
        }
        return out;
    }

    private static Integer var(WorkCalendar.Days cal, LocalDate base, LocalDate cur) {
        if (base == null || cur == null) {
            return null;
        }
        return cal.index(base, cur);
    }
}
