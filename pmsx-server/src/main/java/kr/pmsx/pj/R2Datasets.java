package kr.pmsx.pj;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import kr.pmsx.cm.excel.ExcelDataset;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.UserRef;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.dl.DlController;
import kr.pmsx.dl.TraceController;
import kr.pmsx.pj.wbs.WbsService;
import kr.pmsx.pj.wbs.WbsService.Dep;
import kr.pmsx.pj.wbs.WbsService.WbsItem;
import kr.pmsx.pj.wbs.WeeklyReportController;
import kr.pmsx.qa.IssueController;
import kr.pmsx.qa.QualityController;
import kr.pmsx.rs.TimesheetController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R2 엑셀 데이터셋 (docs/12): wbs, mm-plan, mm-settlement, requirements, trace-matrix, deliverables, issues, risks,
 * defects, test-cases, weekly-report. 모두 params.projectId(주간보고는 reportId) 로 프로젝트 권한을 확인한다.
 */
@Configuration
public class R2Datasets {

    interface Rows {
        List<Map<String, Object>> rows(AuthUser user, String projectId, Map<String, String> params);
    }

    interface Commit {
        int[] commit(AuthUser user, String projectId, List<Map<String, String>> rows);
    }

    /** 공통 골격: 프로젝트 파라미터 해석 + 역할 확인 */
    record ProjectDataset(String id, String name, List<String> params, List<Column> columns, Set<String> readRoles,
                          Set<String> writeRoles, Rows rowsFn, Commit commitFn,
                          BiFunction<Map<String, String>, String, Map<String, String>> validator,
                          BiFunction<String, Map<String, String>, Map<String, String>> common,
                          java.util.function.Function<Map<String, String>, String> projectResolver,
                          ProjectAccess access) implements ExcelDataset {

        public boolean importable() {
            return commitFn != null;
        }

        private String projectId(Map<String, String> p) {
            return projectResolver != null ? projectResolver.apply(p) : ExcelDataset.required(p, "projectId");
        }

        public void checkAccess(AuthUser user, Map<String, String> p, boolean write) {
            var a = access.forProject(user, projectId(p));
            if (write) {
                if (writeRoles == null) {
                    throw ApiException.forbidden();
                }
                a.require(writeRoles);
            } else if (readRoles != null) {
                a.require(readRoles);
            }
        }

        public List<Map<String, Object>> rows(AuthUser user, Map<String, String> p) {
            return rowsFn.rows(user, projectId(p), p);
        }

        public Map<String, String> validateRow(Map<String, String> values, Map<String, String> p) {
            return validator == null ? Map.of() : validator.apply(values, projectId(p));
        }

        public int[] commit(AuthUser user, List<Map<String, String>> rows, Map<String, String> p) {
            if (commitFn == null) {
                return ExcelDataset.super.commit(user, rows, p);
            }
            return commitFn.commit(user, projectId(p), rows);
        }

        public Map<String, String> commonValues(Map<String, String> p) {
            return common == null ? Map.of() : common.apply(projectId(p), p);
        }
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final ProjectSeq seq;

    public R2Datasets(JdbcTemplate jdbc, ProjectAccess access, ProjectSeq seq) {
        this.jdbc = jdbc;
        this.access = access;
        this.seq = seq;
    }

    private Map<String, String> commonOf(String projectId, Map<String, String> params) {
        return projectCommon(projectId);
    }

    private Map<String, String> projectCommon(String projectId) {
        Map<String, String> m = new HashMap<>();
        jdbc.query("SELECT NAME, CUSTOMER FROM PJ_PROJECT WHERE PROJECT_ID = ?", rs -> {
            m.put("projectName", rs.getString(1));
            m.put("customer", rs.getString(2));
        }, projectId);
        return m;
    }

    private String userByLogin(String login) {
        if (login == null || login.isBlank()) {
            return null;
        }
        return jdbc.query("SELECT USER_ID FROM CM_USER WHERE LOGIN_ID = ?", (rs, i) -> rs.getString(1), login.trim())
                .stream().findFirst().orElse(null);
    }

    private String loginOf(String userId) {
        if (userId == null) {
            return null;
        }
        return jdbc.query("SELECT LOGIN_ID FROM CM_USER WHERE USER_ID = ?", (rs, i) -> rs.getString(1), userId).stream()
                .findFirst().orElse(null);
    }

    private Map<String, String> memberLogin(String field, String login, String projectId) {
        if (login == null || login.isBlank()) {
            return Map.of();
        }
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER U JOIN PJ_MEMBER M ON M.USER_ID = U.USER_ID"
                + " WHERE U.LOGIN_ID = ? AND M.PROJECT_ID = ?", Integer.class, login.trim(), projectId);
        return n == null || n == 0 ? Map.of(field, "프로젝트 멤버 아이디가 아닙니다: " + login) : Map.of();
    }

    private static String v(Map<String, String> r, String k) {
        String x = r.get(k);
        return x == null || x.isBlank() ? null : x.trim();
    }

    private static LocalDate date(Map<String, String> r, String k) {
        String x = v(r, k);
        return x == null ? null : LocalDate.parse(x);
    }

    private static double num(Map<String, String> r, String k, double def) {
        String x = v(r, k);
        return x == null ? def : new BigDecimal(x.replace(",", "")).doubleValue();
    }

    private static Map<String, String> oneOf(Map<String, String> r, String k, String... allowed) {
        String x = v(r, k);
        if (x == null) {
            return Map.of();
        }
        for (String a : allowed) {
            if (a.equals(x)) {
                return Map.of();
            }
        }
        return Map.of(k, "허용 값: " + String.join(", ", allowed));
    }

    @SafeVarargs
    private static Map<String, String> merge(Map<String, String>... ms) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, String> m : ms) {
            m.forEach(out::putIfAbsent);
        }
        return out;
    }

    // ───────────────────────── WBS ─────────────────────────

    private static final Pattern DEP = Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)(FS|SS|FF|SF)?([+-][0-9]+)?$");

    @Bean
    ExcelDataset wbsDataset(WbsService wbs) {
        return new ProjectDataset("wbs", "WBS", List.of("projectId"), List.of(
                ExcelDataset.Column.required("wbsNo", "WBS번호"), ExcelDataset.Column.required("name", "작업명"),
                ExcelDataset.Column.date("planStart", "계획시작"), ExcelDataset.Column.date("planEnd", "계획종료"),
                ExcelDataset.Column.date("actStart", "실적시작"), ExcelDataset.Column.date("actEnd", "실적종료"),
                ExcelDataset.Column.number("weight", "가중치"), ExcelDataset.Column.number("progress", "진척률"),
                ExcelDataset.Column.of("milestone", "마일스톤"), ExcelDataset.Column.of("status", "상태"),
                ExcelDataset.Column.of("assignees", "담당자(아이디)"), ExcelDataset.Column.of("deps", "선행작업")),
                null, Perm.WBS_EDIT, (user, pid, p) -> {
                    List<WbsItem> items = wbs.list(pid);
                    Map<String, String> noById = items.stream().collect(Collectors.toMap(WbsItem::wbsId, WbsItem::wbsNo));
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (WbsItem it : items) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("wbsNo", it.wbsNo());
                        m.put("name", it.name());
                        m.put("planStart", it.planStart());
                        m.put("planEnd", it.planEnd());
                        m.put("actStart", it.actStart());
                        m.put("actEnd", it.actEnd());
                        m.put("weight", it.weight());
                        m.put("progress", it.progress());
                        m.put("milestone", it.milestone() ? "Y" : "N");
                        m.put("status", it.status());
                        m.put("assignees", it.assignees().stream().map(a -> loginOf(a.userId()))
                                .collect(Collectors.joining(",")));
                        m.put("deps", it.deps().stream().map(d -> noById.get(d.predId()) + d.type()
                                + (d.lagDays() == 0 ? "" : (d.lagDays() > 0 ? "+" : "") + d.lagDays()))
                                .collect(Collectors.joining(";")));
                        out.add(m);
                    }
                    return out;
                }, (user, pid, rows) -> importWbs(wbs, user, pid, rows), (r, pid) -> {
                    Map<String, String> err = new LinkedHashMap<>();
                    if (v(r, "wbsNo") != null && !v(r, "wbsNo").matches("[0-9]+(\\.[0-9]+)*")) {
                        err.put("wbsNo", "1, 1.2, 1.2.3 형식이어야 합니다.");
                    }
                    err.putAll(oneOf(r, "status", "TODO", "IN_PROGRESS", "DONE", "BLOCKED"));
                    err.putAll(oneOf(r, "milestone", "Y", "N"));
                    if (v(r, "deps") != null) {
                        for (String d : v(r, "deps").split("[;,]")) {
                            if (!d.isBlank() && !DEP.matcher(d.trim()).matches()) {
                                err.put("deps", "예: 1.2FS+1;1.3");
                            }
                        }
                    }
                    if (v(r, "assignees") != null) {
                        for (String l : v(r, "assignees").split(",")) {
                            err.putAll(memberLogin("assignees", l, pid));
                        }
                    }
                    return err;
                }, this::commonOf, null, access);
    }

    /** WBS 번호로 기존 항목 갱신·신규 추가 후 전체 일괄 저장 (목록에 없는 기존 항목은 유지) */
    private int[] importWbs(WbsService wbs, AuthUser user, String projectId, List<Map<String, String>> rows) {
        List<WbsItem> cur = wbs.list(projectId);
        Map<String, WbsItem> byNo = new LinkedHashMap<>();
        cur.forEach(it -> byNo.put(it.wbsNo(), it));
        Map<String, String> idByNo = new HashMap<>();
        cur.forEach(it -> idByNo.put(it.wbsNo(), it.wbsId()));
        int inserted = 0;
        int updated = 0;
        for (Map<String, String> r : rows) {
            String no = v(r, "wbsNo");
            if (!idByNo.containsKey(no)) {
                idByNo.put(no, "tmp-" + no);
                inserted++;
            } else {
                updated++;
            }
        }
        Map<String, WbsItem> merged = new LinkedHashMap<>(byNo);
        for (Map<String, String> r : rows) {
            String no = v(r, "wbsNo");
            String parentNo = no.contains(".") ? no.substring(0, no.lastIndexOf('.')) : null;
            if (parentNo != null && !idByNo.containsKey(parentNo)) {
                throw PjSupport.validation("wbsNo", "상위 WBS " + parentNo + " 가 없습니다 (" + no + ").");
            }
            List<Dep> deps = new ArrayList<>();
            if (v(r, "deps") != null) {
                for (String d : v(r, "deps").split("[;,]")) {
                    if (d.isBlank()) {
                        continue;
                    }
                    Matcher m = DEP.matcher(d.trim());
                    if (m.matches()) {
                        String pred = idByNo.get(m.group(1));
                        if (pred == null) {
                            throw PjSupport.validation("deps", "선행 WBS " + m.group(1) + " 가 없습니다.");
                        }
                        deps.add(new Dep(pred, m.group(2) == null ? "FS" : m.group(2),
                                m.group(3) == null ? 0 : Integer.parseInt(m.group(3))));
                    }
                }
            }
            List<UserRef> assignees = null;
            if (r.containsKey("assignees")) {
                assignees = new ArrayList<>();
                for (String l : v(r, "assignees") == null ? new String[0] : v(r, "assignees").split(",")) {
                    String uid = userByLogin(l);
                    if (uid != null) {
                        assignees.add(new UserRef(uid, l));
                    }
                }
            }
            WbsItem old = byNo.get(no);
            merged.put(no, new WbsItem(idByNo.get(no), parentNo == null ? null : idByNo.get(parentNo), no,
                    v(r, "name"), 0, date(r, "planStart"), date(r, "planEnd"), date(r, "actStart"), date(r, "actEnd"),
                    num(r, "weight", old == null ? 1 : old.weight()), num(r, "progress", old == null ? 0 : old.progress()),
                    "Y".equals(v(r, "milestone")), v(r, "status") == null ? (old == null ? "TODO" : old.status())
                    : v(r, "status"), assignees == null ? (old == null ? null : old.assignees()) : assignees,
                    old == null ? null : old.deliverableId(), deps.isEmpty() && old != null && v(r, "deps") == null
                    ? old.deps() : deps, false, null, null));
        }
        List<WbsItem> list = new ArrayList<>(merged.values());
        list.sort(Comparator.comparing(WbsItem::wbsNo, R2Datasets::compareWbsNo));
        wbs.saveAll(projectId, list, user.userId());
        return new int[] {inserted, updated};
    }

    static int compareWbsNo(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = Integer.compare(Integer.parseInt(x[i]), Integer.parseInt(y[i]));
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(x.length, y.length);
    }

    // ───────────────────────── 인력 ─────────────────────────

    @Bean
    ExcelDataset mmPlanDataset(kr.pmsx.rs.ResourceController resources) {
        return new ProjectDataset("mm-plan", "투입계획(MM)", List.of("projectId"), List.of(
                ExcelDataset.Column.required("loginId", "아이디"), ExcelDataset.Column.of("name", "이름"),
                ExcelDataset.Column.required("ym", "년월(YYYY-MM)"), ExcelDataset.Column.number("planMm", "계획MM")),
                Perm.RESOURCE_READ, Perm.RESOURCE_EDIT, (user, pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var r : resources.mmPlanRows(pid, null, null)) {
                        String login = loginOf(r.userId());
                        r.months().forEach((ym, mm) -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("loginId", login);
                            m.put("name", r.name());
                            m.put("ym", ym);
                            m.put("planMm", mm);
                            out.add(m);
                        });
                    }
                    return out;
                }, (user, pid, rows) -> {
                    int ins = 0;
                    int upd = 0;
                    for (Map<String, String> r : rows) {
                        String uid = userByLogin(v(r, "loginId"));
                        String ym = YearMonth.parse(v(r, "ym")).toString();
                        int n = jdbc.update("DELETE FROM RS_MM_PLAN WHERE PROJECT_ID = ? AND USER_ID = ? AND YM = ?", pid,
                                uid, ym);
                        double mm = num(r, "planMm", 0);
                        if (mm > 0) {
                            jdbc.update("INSERT INTO RS_MM_PLAN (PROJECT_ID, USER_ID, YM, PLAN_MM) VALUES (?, ?, ?, ?)",
                                    pid, uid, ym, BigDecimal.valueOf(mm));
                        }
                        if (n > 0) {
                            upd++;
                        } else {
                            ins++;
                        }
                    }
                    return new int[] {ins, upd};
                }, (r, pid) -> {
                    Map<String, String> err = new LinkedHashMap<>(memberLogin("loginId", v(r, "loginId"), pid));
                    try {
                        if (v(r, "ym") != null) {
                            YearMonth.parse(v(r, "ym"));
                        }
                    } catch (RuntimeException e) {
                        err.put("ym", "YYYY-MM 형식이어야 합니다.");
                    }
                    double mm = v(r, "planMm") == null ? 0 : num(r, "planMm", 0);
                    if (mm < 0 || mm > 3) {
                        err.put("planMm", "0~3 사이여야 합니다.");
                    }
                    return err;
                }, this::commonOf, null, access);
    }

    @Bean
    ExcelDataset mmSettlementDataset(TimesheetController timesheets) {
        return new ProjectDataset("mm-settlement", "협력사 MM 정산", List.of("projectId", "month"), List.of(
                ExcelDataset.Column.of("company", "소속사"), ExcelDataset.Column.of("name", "성명"),
                ExcelDataset.Column.of("grade", "등급"), ExcelDataset.Column.number("planMm", "계획MM"),
                ExcelDataset.Column.number("actualMm", "실적MM"), ExcelDataset.Column.number("diff", "차이")),
                Perm.MM_READ, null, (user, pid, p) -> {
                    YearMonth ym = YearMonth.parse(ExcelDataset.required(p, "month"));
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var r : timesheets.summary(pid, ym, ym)) {
                        if (!"PARTNER".equals(r.orgType())) {
                            continue;
                        }
                        double plan = r.plan().getOrDefault(ym.toString(), 0.0);
                        double act = r.actual().getOrDefault(ym.toString(), 0.0);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("company", r.company());
                        m.put("name", r.name());
                        m.put("grade", r.grade());
                        m.put("planMm", plan);
                        m.put("actualMm", act);
                        m.put("diff", Math.round((act - plan) * 100) / 100.0);
                        out.add(m);
                    }
                    return out;
                }, null, null, this::commonOf, null, access);
    }

    // ───────────────────────── 요구사항·추적성·산출물 ─────────────────────────

    @Bean
    ExcelDataset requirementsDataset(DlController dl) {
        return new ProjectDataset("requirements", "요구사항", List.of("projectId"), List.of(
                ExcelDataset.Column.of("reqNo", "요구사항ID"), ExcelDataset.Column.required("title", "요구사항명"),
                ExcelDataset.Column.of("description", "상세내용"), ExcelDataset.Column.of("source", "출처"),
                ExcelDataset.Column.of("category", "분류"), ExcelDataset.Column.of("priority", "우선순위(H/M/L)"),
                ExcelDataset.Column.of("status", "상태")), null, Perm.REQ_EDIT, (user, pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    jdbc.query("SELECT REQ_NO, TITLE, DESCRIPTION, SOURCE, CATEGORY, PRIORITY, STATUS FROM DL_REQUIREMENT"
                            + " WHERE PROJECT_ID = ? ORDER BY REQ_NO", rs -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("reqNo", rs.getString(1));
                        m.put("title", rs.getString(2));
                        m.put("description", rs.getString(3));
                        m.put("source", rs.getString(4));
                        m.put("category", rs.getString(5));
                        m.put("priority", rs.getString(6));
                        m.put("status", rs.getString(7));
                        out.add(m);
                    }, pid);
                    return out;
                }, (user, pid, rows) -> {
                    int ins = 0;
                    int upd = 0;
                    for (Map<String, String> r : rows) {
                        String reqId = v(r, "reqNo") == null ? null : jdbc.query(
                                "SELECT REQ_ID FROM DL_REQUIREMENT WHERE PROJECT_ID = ? AND REQ_NO = ?",
                                (rs, i) -> rs.getString(1), pid, v(r, "reqNo")).stream().findFirst().orElse(null);
                        var req = new DlController.Requirement(reqId, v(r, "reqNo"), v(r, "title"), v(r, "description"),
                                v(r, "source"), v(r, "category"), v(r, "priority"), v(r, "status"), null);
                        if (reqId == null) {
                            dl.insertRequirement(pid, req, user.userId());
                            ins++;
                        } else {
                            dl.updateRequirementRow(reqId, req, user.userId());
                            upd++;
                        }
                    }
                    return new int[] {ins, upd};
                }, (r, pid) -> merge(oneOf(r, "priority", "H", "M", "L"),
                        oneOf(r, "status", "PROPOSED", "ACCEPTED", "IMPLEMENTED", "VERIFIED", "DROPPED")),
                this::commonOf, null, access);
    }

    @Bean
    ExcelDataset traceMatrixDataset(TraceController trace) {
        return new ProjectDataset("trace-matrix", "요구사항 추적표", List.of("projectId"), List.of(
                ExcelDataset.Column.of("reqNo", "요구사항ID"), ExcelDataset.Column.of("title", "요구사항명"),
                ExcelDataset.Column.of("status", "상태"), ExcelDataset.Column.of("screens", "화면"),
                ExcelDataset.Column.of("tests", "테스트케이스(최근결과)"), ExcelDataset.Column.of("defects", "결함"),
                ExcelDataset.Column.of("crs", "변경요청"), ExcelDataset.Column.of("gaps", "누락")), null, null,
                (user, pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var row : trace.matrixRows(pid)) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("reqNo", row.req().reqNo());
                        m.put("title", row.req().title());
                        m.put("status", row.req().status());
                        m.put("screens", row.screens().stream().map(TraceController.Item::label)
                                .collect(Collectors.joining("\n")));
                        m.put("tests", row.tests().stream().map(t -> t.label() + (t.lastResult() == null ? ""
                                : " (" + t.lastResult() + ")")).collect(Collectors.joining("\n")));
                        m.put("defects", row.defects().stream().map(d -> d.label() + " [" + d.status() + "]")
                                .collect(Collectors.joining("\n")));
                        m.put("crs", row.crs().stream().map(TraceController.Item::label).collect(Collectors.joining("\n")));
                        m.put("gaps", String.join(", ", row.gaps()));
                        out.add(m);
                    }
                    return out;
                }, null, null, this::commonOf, null, access);
    }

    @Bean
    ExcelDataset deliverablesDataset(DlController dl) {
        return new ProjectDataset("deliverables", "산출물 대장", List.of("projectId"), List.of(
                ExcelDataset.Column.of("phase", "단계"), ExcelDataset.Column.of("name", "산출물명"),
                ExcelDataset.Column.of("owner", "담당자"), ExcelDataset.Column.date("dueDate", "제출기한"),
                ExcelDataset.Column.of("status", "상태"), ExcelDataset.Column.of("customerApproval", "고객승인"),
                ExcelDataset.Column.number("fileCount", "파일수")), null, null, (user, pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var d : dl.deliverableList(pid, null, null)) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("phase", d.phase());
                        m.put("name", d.name());
                        m.put("owner", d.owner() == null ? null : d.owner().name());
                        m.put("dueDate", d.dueDate());
                        m.put("status", d.status());
                        m.put("customerApproval", d.customerApproval() ? "Y" : "N");
                        m.put("fileCount", d.fileCount());
                        out.add(m);
                    }
                    return out;
                }, null, null, this::commonOf, null, access);
    }

    // ───────────────────────── 품질 ─────────────────────────

    @Bean
    ExcelDataset issuesDataset(IssueController issues) {
        return new ProjectDataset("issues", "이슈 대장", List.of("projectId"), List.of(
                ExcelDataset.Column.of("issueNo", "이슈번호"), ExcelDataset.Column.required("title", "제목"),
                ExcelDataset.Column.of("description", "내용"), ExcelDataset.Column.of("priority", "우선순위(H/M/L)"),
                ExcelDataset.Column.of("status", "상태"), ExcelDataset.Column.of("owner", "담당자(아이디)"),
                ExcelDataset.Column.date("dueDate", "기한")), null, Perm.ISSUE_EDIT, (user, pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT I.ISSUE_NO, I.TITLE, I.DESCRIPTION, I.PRIORITY, I.STATUS, U.LOGIN_ID, I.DUE_DT FROM QA_ISSUE I
                              LEFT JOIN CM_USER U ON U.USER_ID = I.OWNER_ID WHERE I.PROJECT_ID = ? ORDER BY I.ISSUE_NO""", rs -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("issueNo", rs.getString(1));
                        m.put("title", rs.getString(2));
                        m.put("description", rs.getString(3));
                        m.put("priority", rs.getString(4));
                        m.put("status", rs.getString(5));
                        m.put("owner", rs.getString(6));
                        m.put("dueDate", rs.getObject(7, LocalDate.class));
                        out.add(m);
                    }, pid);
                    return out;
                }, (user, pid, rows) -> {
                    int ins = 0;
                    int upd = 0;
                    for (Map<String, String> r : rows) {
                        String id = v(r, "issueNo") == null ? null : jdbc.query(
                                "SELECT ISSUE_ID FROM QA_ISSUE WHERE PROJECT_ID = ? AND ISSUE_NO = ?",
                                (rs, i) -> rs.getString(1), pid, v(r, "issueNo")).stream().findFirst().orElse(null);
                        String owner = userByLogin(v(r, "owner"));
                        if (id == null) {
                            issues.insertIssue(pid, new IssueController.IssueRequest(v(r, "title"), v(r, "description"),
                                    v(r, "priority"), v(r, "status"), owner, date(r, "dueDate"), null), null, user);
                            ins++;
                        } else {
                            jdbc.update("""
                                    UPDATE QA_ISSUE SET TITLE = ?, DESCRIPTION = ?, PRIORITY = COALESCE(?, PRIORITY),
                                           STATUS = COALESCE(?, STATUS), OWNER_ID = ?, DUE_DT = ?, UPDATED_AT = ?, UPDATED_BY = ?
                                     WHERE ISSUE_ID = ?""", v(r, "title"), v(r, "description"), v(r, "priority"),
                                    v(r, "status"), owner, date(r, "dueDate"), kr.pmsx.common.Times.now(), user.userId(),
                                    id);
                            upd++;
                        }
                    }
                    return new int[] {ins, upd};
                }, (r, pid) -> merge(oneOf(r, "priority", "H", "M", "L"),
                        oneOf(r, "status", "OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"),
                        memberLogin("owner", v(r, "owner"), pid)), this::commonOf, null, access);
    }

    @Bean
    ExcelDataset risksDataset(IssueController issues) {
        return new ProjectDataset("risks", "리스크 대장", List.of("projectId"), List.of(
                ExcelDataset.Column.of("riskNo", "리스크번호"), ExcelDataset.Column.required("title", "제목"),
                ExcelDataset.Column.of("description", "내용"), ExcelDataset.Column.number("probability", "발생가능성(1~5)"),
                ExcelDataset.Column.number("impact", "영향도(1~5)"), ExcelDataset.Column.number("score", "점수"),
                ExcelDataset.Column.of("response", "대응계획"), ExcelDataset.Column.of("status", "상태"),
                ExcelDataset.Column.of("owner", "담당자(아이디)"), ExcelDataset.Column.date("dueDate", "기한")),
                null, Perm.ISSUE_EDIT, (user, pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var r : issues.riskList(pid)) {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("riskNo", r.riskNo());
                        m.put("title", r.title());
                        m.put("description", r.description());
                        m.put("probability", r.probability());
                        m.put("impact", r.impact());
                        m.put("score", r.score());
                        m.put("response", r.response());
                        m.put("status", r.status());
                        m.put("owner", r.owner() == null ? null : loginOf(r.owner().userId()));
                        m.put("dueDate", r.dueDate());
                        out.add(m);
                    }
                    return out;
                }, (user, pid, rows) -> {
                    int ins = 0;
                    int upd = 0;
                    for (Map<String, String> r : rows) {
                        String id = v(r, "riskNo") == null ? null : jdbc.query(
                                "SELECT RISK_ID FROM QA_RISK WHERE PROJECT_ID = ? AND RISK_NO = ?",
                                (rs, i) -> rs.getString(1), pid, v(r, "riskNo")).stream().findFirst().orElse(null);
                        int prob = (int) num(r, "probability", 3);
                        int imp = (int) num(r, "impact", 3);
                        String owner = userByLogin(v(r, "owner"));
                        if (id == null) {
                            String no = seq.next(pid, "RSK");
                            jdbc.update("""
                                    INSERT INTO QA_RISK (RISK_ID, PROJECT_ID, RISK_NO, TITLE, DESCRIPTION, PROBABILITY, IMPACT,
                                                         RESPONSE, STATUS, OWNER_ID, DUE_DT, CREATED_AT, CREATED_BY,
                                                         UPDATED_AT, UPDATED_BY)
                                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", kr.pmsx.common.Ulid.next(),
                                    pid, no, v(r, "title"), v(r, "description"), prob, imp, v(r, "response"),
                                    v(r, "status") == null ? "IDENTIFIED" : v(r, "status"), owner, date(r, "dueDate"),
                                    kr.pmsx.common.Times.now(), user.userId(), kr.pmsx.common.Times.now(), user.userId());
                            ins++;
                        } else {
                            jdbc.update("""
                                    UPDATE QA_RISK SET TITLE = ?, DESCRIPTION = ?, PROBABILITY = ?, IMPACT = ?, RESPONSE = ?,
                                           STATUS = COALESCE(?, STATUS), OWNER_ID = ?, DUE_DT = ?, UPDATED_AT = ?, UPDATED_BY = ?
                                     WHERE RISK_ID = ?""", v(r, "title"), v(r, "description"), prob, imp,
                                    v(r, "response"), v(r, "status"), owner, date(r, "dueDate"),
                                    kr.pmsx.common.Times.now(), user.userId(), id);
                            upd++;
                        }
                    }
                    return new int[] {ins, upd};
                }, (r, pid) -> {
                    Map<String, String> err = new LinkedHashMap<>(merge(
                            oneOf(r, "status", "IDENTIFIED", "MITIGATING", "OCCURRED", "CLOSED"),
                            memberLogin("owner", v(r, "owner"), pid)));
                    for (String k : List.of("probability", "impact")) {
                        double x = v(r, k) == null ? 3 : num(r, k, 3);
                        if (x < 1 || x > 5) {
                            err.put(k, "1~5 입니다.");
                        }
                    }
                    return err;
                }, this::commonOf, null, access);
    }

    @Bean
    ExcelDataset defectsDataset(QualityController quality) {
        return new ProjectDataset("defects", "결함 대장", List.of("projectId"), List.of(
                ExcelDataset.Column.of("defectNo", "결함번호"), ExcelDataset.Column.required("title", "제목"),
                ExcelDataset.Column.of("description", "내용"), ExcelDataset.Column.of("severity", "심각도"),
                ExcelDataset.Column.of("priority", "우선순위(H/M/L)"), ExcelDataset.Column.of("status", "상태"),
                ExcelDataset.Column.of("assignee", "담당자(아이디)"), ExcelDataset.Column.of("reporter", "등록자"),
                ExcelDataset.Column.of("screen", "화면")), null, Perm.TEST_EDIT, (user, pid, p) -> {
                    List<Map<String, Object>> out = new ArrayList<>();
                    jdbc.query("""
                            SELECT D.DEFECT_NO, D.TITLE, D.DESCRIPTION, D.SEVERITY, D.PRIORITY, D.STATUS, A.LOGIN_ID, R.NAME,
                                   S.SCREEN_CODE || ' ' || S.NAME
                              FROM QA_DEFECT D LEFT JOIN CM_USER A ON A.USER_ID = D.ASSIGNEE_ID
                              LEFT JOIN CM_USER R ON R.USER_ID = D.REPORTER_ID LEFT JOIN AS_SCREEN S ON S.SCREEN_ID = D.SCREEN_ID
                             WHERE D.PROJECT_ID = ? ORDER BY D.DEFECT_NO""", rs -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("defectNo", rs.getString(1));
                        m.put("title", rs.getString(2));
                        m.put("description", rs.getString(3));
                        m.put("severity", rs.getString(4));
                        m.put("priority", rs.getString(5));
                        m.put("status", rs.getString(6));
                        m.put("assignee", rs.getString(7));
                        m.put("reporter", rs.getString(8));
                        m.put("screen", rs.getString(9));
                        out.add(m);
                    }, pid);
                    return out;
                }, (user, pid, rows) -> {
                    int ins = 0;
                    int upd = 0;
                    for (Map<String, String> r : rows) {
                        String id = v(r, "defectNo") == null ? null : jdbc.query(
                                "SELECT DEFECT_ID FROM QA_DEFECT WHERE PROJECT_ID = ? AND DEFECT_NO = ?",
                                (rs, i) -> rs.getString(1), pid, v(r, "defectNo")).stream().findFirst().orElse(null);
                        String assignee = userByLogin(v(r, "assignee"));
                        if (id == null) {
                            quality.insertDefect(pid, new QualityController.DefectRequest(v(r, "title"),
                                    v(r, "description"), v(r, "severity"), v(r, "priority"), assignee, null, null, null),
                                    user);
                            ins++;
                        } else {
                            // 상태는 전이 규칙을 따르므로 가져오기로 바꾸지 않는다
                            jdbc.update("""
                                    UPDATE QA_DEFECT SET TITLE = ?, DESCRIPTION = ?, SEVERITY = COALESCE(?, SEVERITY),
                                           PRIORITY = COALESCE(?, PRIORITY), UPDATED_AT = ?, UPDATED_BY = ? WHERE DEFECT_ID = ?""",
                                    v(r, "title"), v(r, "description"), v(r, "severity"), v(r, "priority"),
                                    kr.pmsx.common.Times.now(), user.userId(), id);
                            upd++;
                        }
                    }
                    return new int[] {ins, upd};
                }, (r, pid) -> merge(oneOf(r, "severity", "CRITICAL", "MAJOR", "MINOR", "TRIVIAL"),
                        oneOf(r, "priority", "H", "M", "L"), memberLogin("assignee", v(r, "assignee"), pid)),
                this::commonOf, null, access);
    }

    @Bean
    ExcelDataset testCasesDataset(QualityController quality) {
        return new ProjectDataset("test-cases", "테스트 케이스", List.of("projectId"), List.of(
                ExcelDataset.Column.required("scenario", "시나리오"), ExcelDataset.Column.of("level", "구분"),
                ExcelDataset.Column.of("tcNo", "케이스번호"), ExcelDataset.Column.required("title", "케이스명"),
                ExcelDataset.Column.of("precondition", "사전조건"), ExcelDataset.Column.of("steps", "수행절차"),
                ExcelDataset.Column.of("expected", "기대결과"), ExcelDataset.Column.of("reqNos", "요구사항ID"),
                ExcelDataset.Column.of("lastResult", "최근결과")), null, Perm.TEST_EDIT, (user, pid, p) -> {
                    Map<String, String> reqNo = new HashMap<>();
                    jdbc.query("SELECT REQ_ID, REQ_NO FROM DL_REQUIREMENT WHERE PROJECT_ID = ?", rs -> {
                        reqNo.put(rs.getString(1), rs.getString(2));
                    }, pid);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var s : quality.scenarioTree(pid, null)) {
                        for (var c : s.cases()) {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("scenario", s.name());
                            m.put("level", s.level());
                            m.put("tcNo", c.tcNo());
                            m.put("title", c.title());
                            m.put("precondition", c.precondition());
                            m.put("steps", c.steps());
                            m.put("expected", c.expected());
                            m.put("reqNos", c.reqIds().stream().map(x -> reqNo.getOrDefault(x, x))
                                    .collect(Collectors.joining(",")));
                            m.put("lastResult", c.lastResult());
                            out.add(m);
                        }
                    }
                    return out;
                }, (user, pid, rows) -> {
                    int ins = 0;
                    int upd = 0;
                    for (Map<String, String> r : rows) {
                        String level = v(r, "level") == null ? "INTEGRATION" : v(r, "level");
                        String scenarioId = jdbc.query("SELECT SCENARIO_ID FROM QA_TEST_SCENARIO WHERE PROJECT_ID = ?"
                                + " AND NAME = ?", (rs, i) -> rs.getString(1), pid, v(r, "scenario")).stream().findFirst()
                                .orElse(null);
                        if (scenarioId == null) {
                            scenarioId = kr.pmsx.common.Ulid.next();
                            jdbc.update("INSERT INTO QA_TEST_SCENARIO (SCENARIO_ID, PROJECT_ID, LVL, NAME, ORD, CREATED_AT)"
                                    + " VALUES (?, ?, ?, ?, 0, ?)", scenarioId, pid, level, v(r, "scenario"),
                                    kr.pmsx.common.Times.now());
                        }
                        List<String> reqIds = new ArrayList<>();
                        for (String no : PjSupport.csv(v(r, "reqNos"))) {
                            jdbc.query("SELECT REQ_ID FROM DL_REQUIREMENT WHERE PROJECT_ID = ? AND REQ_NO = ?", rs -> {
                                reqIds.add(rs.getString(1));
                            }, pid, no);
                        }
                        String caseId = v(r, "tcNo") == null ? null : jdbc.query(
                                "SELECT CASE_ID FROM QA_TEST_CASE WHERE PROJECT_ID = ? AND TC_NO = ?",
                                (rs, i) -> rs.getString(1), pid, v(r, "tcNo")).stream().findFirst().orElse(null);
                        if (caseId == null) {
                            quality.insertCase(pid, scenarioId, new QualityController.TestCase(null, v(r, "tcNo"),
                                    v(r, "title"), v(r, "precondition"), v(r, "steps"), v(r, "expected"), reqIds, null,
                                    null));
                            ins++;
                        } else {
                            jdbc.update("""
                                    UPDATE QA_TEST_CASE SET SCENARIO_ID = ?, TITLE = ?, PRECONDITION = ?, STEPS = ?, EXPECTED = ?,
                                           REQ_IDS = ? WHERE CASE_ID = ?""", scenarioId, v(r, "title"),
                                    v(r, "precondition"), v(r, "steps"), v(r, "expected"), PjSupport.csv(reqIds), caseId);
                            upd++;
                        }
                    }
                    return new int[] {ins, upd};
                }, (r, pid) -> {
                    Map<String, String> err = new LinkedHashMap<>(oneOf(r, "level", "UNIT", "INTEGRATION", "ACCEPTANCE"));
                    for (String no : PjSupport.csv(v(r, "reqNos"))) {
                        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM DL_REQUIREMENT WHERE PROJECT_ID = ?"
                                + " AND REQ_NO = ?", Integer.class, pid, no);
                        if (n == null || n == 0) {
                            err.put("reqNos", "없는 요구사항ID: " + no);
                        }
                    }
                    return err;
                }, this::commonOf, null, access);
    }

    // ───────────────────────── 주간보고 ─────────────────────────

    @Bean
    ExcelDataset weeklyReportDataset(WeeklyReportController weekly) {
        return new ProjectDataset("weekly-report", "주간보고", List.of("reportId"), List.of(
                ExcelDataset.Column.of("section", "구분"), ExcelDataset.Column.of("no", "번호"),
                ExcelDataset.Column.of("name", "내용"), ExcelDataset.Column.of("owner", "담당"),
                ExcelDataset.Column.of("state", "진척/상태"), ExcelDataset.Column.of("note", "비고")),
                Perm.WEEKLY_READ, null, (user, pid, p) -> {
                    var r = weekly.load(ExcelDataset.required(p, "reportId"));
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (var t : r.thisWeek()) {
                        out.add(line("금주 실적", t.wbsNo(), t.name(), t.assignees(), t.progress() + "%", t.note()));
                    }
                    for (var t : r.nextWeek()) {
                        out.add(line("차주 계획", t.wbsNo(), t.name(), t.assignees(),
                                t.planEnd() == null ? "" : "~" + t.planEnd(), t.note()));
                    }
                    for (var i : r.issues()) {
                        out.add(line("이슈", i.no(), i.title(), i.owner(), i.status(),
                                i.dueDate() == null ? "" : "기한 " + i.dueDate()));
                    }
                    for (var k : r.risks()) {
                        out.add(line("리스크", k.no(), k.title(), "", "점수 " + k.score(), k.response()));
                    }
                    return out;
                }, null, null, (pid, p) -> {
                    var r = weekly.load(ExcelDataset.required(p, "reportId"));
                    Map<String, String> m = new HashMap<>(projectCommon(pid));
                    m.put("weekStart", String.valueOf(r.weekStart()));
                    m.put("summary", r.summary() == null ? "" : r.summary());
                    m.put("planned", r.progress().planned() + "%");
                    m.put("actual", r.progress().actual() + "%");
                    m.put("plannedMm", String.valueOf(r.mm().planned()));
                    m.put("actualMm", String.valueOf(r.mm().actual()));
                    return m;
                }, p -> weekly.projectOf(ExcelDataset.required(p, "reportId")), access);
    }

    private static Map<String, Object> line(String section, String no, String name, String owner, String state,
            String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section", section);
        m.put("no", no);
        m.put("name", name);
        m.put("owner", owner);
        m.put("state", state);
        m.put("note", note);
        return m;
    }
}
