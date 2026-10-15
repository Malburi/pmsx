package kr.pmsx.sm;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SI → SM 인수인계 (FR-SM-013, docs/13 §7). 시스템 As-Is 자산 전체를 복사해 SI 이력을 보존한다.
 * <p>
 * 복사본 ID = 인수인계마다 새로 뽑은 10자 접두어 + 원본 ULID 의 뒤 16자. 그래서 행마다 매핑 표 없이
 * {@code INSERT … SELECT} 한 문장으로 테이블을 통째로 옮기고(연결선 100만 건도 메모리 사용 없음),
 * 다른 테이블의 참조 컬럼(SCREEN_ID, RUN_ID, TABLE_ID …)도 같은 규칙으로 바꾼다. 수집 키는 그대로 유지된다.
 * 캡처·첨부 파일은 같은 파일(CM_FILE)을 함께 참조한다(파일 삭제 기능이 없으므로 참조 카운트 불필요).
 */
@RestController
@RequestMapping("/api/v1")
public class HandoverController {

    public record NewProject(String name, String customer, LocalDate startDate, LocalDate endDate, String pmUserId) {
    }

    /** target: { newProject: {...} } 또는 { projectId } */
    public record Target(NewProject newProject, String projectId) {
    }

    public record Include(boolean notes, boolean traceLinks, boolean deliverables, boolean openDefectsAsSr) {
    }

    public record HandoverRequest(Target target, List<String> systemIds, List<String> memberUserIds,
                                  Include include) {
    }

    public record SystemPreview(String systemId, String name, int screens, int elements, int edges) {
    }

    public record Preview(List<SystemPreview> systems, int members, int traceLinks, int notes, int deliverables,
                          int openDefects) {
    }

    public record Result(String smProjectId, Map<String, Integer> copied) {
    }

    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final SlaService sla;
    private final SrService srs;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public HandoverController(JdbcTemplate jdbc, ProjectAccess access, SlaService sla, SrService srs,
            AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.access = access;
        this.sla = sla;
        this.srs = srs;
        this.audit = audit;
        this.events = events;
    }

    // ───────────────────────── 미리보기 ─────────────────────────

    @PostMapping("/projects/{siProjectId}/handover/preview")
    public Preview preview(@AuthenticationPrincipal AuthUser user, @PathVariable String siProjectId,
            @RequestBody HandoverRequest req) {
        access.forProject(user, siProjectId).require(SmPerm.HANDOVER);
        List<String> systems = systems(siProjectId, req);
        Include inc = req.include() == null ? new Include(true, true, true, false) : req.include();
        List<SystemPreview> sp = new ArrayList<>();
        for (String s : systems) {
            sp.add(new SystemPreview(s, jdbc.queryForObject("SELECT NAME FROM AS_SYSTEM WHERE SYSTEM_ID = ?",
                    String.class, s), count("SELECT COUNT(*) FROM AS_SCREEN WHERE SYSTEM_ID = ?", s),
                    count("SELECT COUNT(*) FROM AS_ELEMENT WHERE SYSTEM_ID = ?", s),
                    count("SELECT COUNT(*) FROM AS_EDGE WHERE SYSTEM_ID = ?", s)));
        }
        String in = in(systems);
        Object[] sysArgs = systems.toArray();
        int notes = systems.isEmpty() ? 0 : count("SELECT COUNT(*) FROM AS_NOTE WHERE SYSTEM_ID IN (" + in + ")", sysArgs);
        int dl = count("SELECT COUNT(*) FROM DL_DELIVERABLE WHERE PROJECT_ID = ?", siProjectId);
        int links = systems.isEmpty() ? 0 : count("SELECT COUNT(*) FROM DL_TRACE_LINK L WHERE L.PROJECT_ID = ? AND "
                + endpoint("L.FROM_TYPE", "L.FROM_ID", in, inc.deliverables()) + " AND "
                + endpoint("L.TO_TYPE", "L.TO_ID", in, inc.deliverables()), linkArgs(siProjectId, systems,
                inc.deliverables()));
        int defects = count("SELECT COUNT(*) FROM QA_DEFECT WHERE PROJECT_ID = ? AND STATUS NOT IN ('CLOSED', 'VERIFIED')",
                siProjectId);
        int members = req.memberUserIds() == null ? 0 : (int) req.memberUserIds().stream().distinct().count();
        return new Preview(sp, members, links, notes, dl, defects);
    }

    private List<String> systems(String siProjectId, HandoverRequest req) {
        List<String> ids = req.systemIds() == null ? List.of() : req.systemIds().stream().distinct().toList();
        if (ids.isEmpty()) {
            throw PjSupport.validation("systemIds", "인수인계할 시스템을 하나 이상 선택해주세요.");
        }
        for (String s : ids) {
            String p = jdbc.query("SELECT PROJECT_ID FROM AS_SYSTEM WHERE SYSTEM_ID = ?", (rs, i) -> rs.getString(1), s)
                    .stream().findFirst().orElseThrow(() -> PjSupport.validation("systemIds", "시스템을 찾을 수 없습니다: " + s));
            if (!p.equals(siProjectId)) {
                throw PjSupport.validation("systemIds", "이 프로젝트의 시스템이 아닙니다: " + s);
            }
        }
        return ids;
    }

    /** 추적 링크 한쪽 끝이 복사 대상(As-Is 자산 또는 복사한 산출물)인지 */
    private static String endpoint(String typeCol, String idCol, String sysIn, boolean deliverables) {
        String s = "((" + typeCol + " = 'SCREEN' AND " + idCol + " IN (SELECT SCREEN_ID FROM AS_SCREEN WHERE SYSTEM_ID IN ("
                + sysIn + ")))"
                + " OR (" + typeCol + " = 'ELEMENT' AND " + idCol + " IN (SELECT ELEMENT_ID FROM AS_ELEMENT WHERE SYSTEM_ID IN ("
                + sysIn + ")))"
                + " OR (" + typeCol + " = 'NODE' AND " + idCol + " IN (SELECT NODE_ID FROM AS_NODE WHERE SYSTEM_ID IN ("
                + sysIn + ")))";
        if (deliverables) {
            s += " OR (" + typeCol + " = 'DELIVERABLE' AND " + idCol
                    + " IN (SELECT DELIVERABLE_ID FROM DL_DELIVERABLE WHERE PROJECT_ID = ?))";
        }
        return s + ")";
    }

    private static Object[] linkArgs(String siProjectId, List<String> systems, boolean deliverables) {
        List<Object> a = new ArrayList<>(List.of(siProjectId));
        for (int side = 0; side < 2; side++) {
            for (int k = 0; k < 3; k++) {
                a.addAll(systems);
            }
            if (deliverables) {
                a.add(siProjectId);
            }
        }
        return a.toArray();
    }

    // ───────────────────────── 실행 ─────────────────────────

    @PostMapping("/projects/{siProjectId}/handover")
    @Transactional
    public Result execute(@AuthenticationPrincipal AuthUser user, @PathVariable String siProjectId,
            @RequestBody HandoverRequest req) {
        access.forProject(user, siProjectId).require(SmPerm.HANDOVER);
        return run(user, siProjectId, req);
    }

    /** 권한 확인 없이 실행 (데모 시드·테스트용) */
    @Transactional
    public Result run(AuthUser user, String siProjectId, HandoverRequest req) {
        List<String> systems = systems(siProjectId, req);
        Include inc = req.include() == null ? new Include(true, true, true, false) : req.include();
        if (req.target() == null || (req.target().newProject() == null && blank(req.target().projectId()))) {
            throw PjSupport.validation("target", "새 SM 프로젝트 또는 기존 프로젝트를 지정해주세요.");
        }
        OffsetDateTime now = Times.now();
        String smId;
        String pmUserId = null;
        if (req.target().newProject() != null) {
            NewProject np = req.target().newProject();
            String name = PjSupport.required("target.newProject.name", np.name(), 200);
            if (np.startDate() == null || np.endDate() == null || np.endDate().isBefore(np.startDate())) {
                throw PjSupport.validation("target.newProject.endDate", "기간을 확인해주세요.");
            }
            pmUserId = SrService.blank(np.pmUserId());
            if (pmUserId == null) {
                throw PjSupport.validation("target.newProject.pmUserId", "PM 을 지정해주세요.");
            }
            Integer u = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER WHERE USER_ID = ?", Integer.class, pmUserId);
            if (u == null || u == 0) {
                throw PjSupport.validation("target.newProject.pmUserId", "사용자를 찾을 수 없습니다.");
            }
            smId = Ulid.next();
            jdbc.update("""
                    INSERT INTO PJ_PROJECT (PROJECT_ID, TYPE, NAME, CUSTOMER, START_DT, END_DT, ORIGIN_PROJECT_ID,
                                            CREATED_AT, CREATED_BY)
                    VALUES (?, 'SM', ?, ?, ?, ?, ?, ?, ?)""", smId, name,
                    PjSupport.optional("target.newProject.customer", np.customer(), 200), np.startDate(), np.endDate(),
                    siProjectId, now, user.userId());
            jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, 'PM', ?)", smId,
                    pmUserId, np.startDate());
            events.publishEvent(new kr.pmsx.pj.ProjectSavedEvent(smId, "SM"));
        } else {
            smId = req.target().projectId();
            access.forProject(user, smId).require(SmPerm.HANDOVER);
            String type = jdbc.queryForObject("SELECT TYPE FROM PJ_PROJECT WHERE PROJECT_ID = ?", String.class, smId);
            if (!"SM".equals(type)) {
                throw PjSupport.validation("target.projectId", "SM 프로젝트로만 인수인계할 수 있습니다.");
            }
            jdbc.update("UPDATE PJ_PROJECT SET ORIGIN_PROJECT_ID = COALESCE(ORIGIN_PROJECT_ID, ?) WHERE PROJECT_ID = ?",
                    siProjectId, smId);
        }
        if (smId.equals(siProjectId)) {
            throw PjSupport.validation("target.projectId", "같은 프로젝트로는 인수인계할 수 없습니다.");
        }
        sla.ensureDefaults(smId);
        for (String s : systems) {
            String code = jdbc.queryForObject("SELECT CODE FROM AS_SYSTEM WHERE SYSTEM_ID = ?", String.class, s);
            Integer dup = jdbc.queryForObject("SELECT COUNT(*) FROM AS_SYSTEM WHERE PROJECT_ID = ? AND CODE = ?",
                    Integer.class, smId, code);
            if (dup != null && dup > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "대상 프로젝트에 같은 시스템 코드가 있습니다: " + code);
            }
        }

        String p = prefix();
        String in = in(systems);
        Object[] sys = systems.toArray();
        String scrIn = "SELECT SCREEN_ID FROM AS_SCREEN WHERE SYSTEM_ID IN (" + in + ")";
        String tblIn = "SELECT TABLE_ID FROM AS_TABLE WHERE SYSTEM_ID IN (" + in + ")";
        Map<String, Integer> copied = new LinkedHashMap<>();
        Map<String, Expr> base = Map.of("system_id", remap("SYSTEM_ID", p), "last_run_id", remap("LAST_RUN_ID", p));

        copied.put("systems", copy("AS_SYSTEM", "SYSTEM_ID IN (" + in + ")", sys, Map.of(
                "system_id", remap("SYSTEM_ID", p), "project_id", value(smId), "created_at", value(now),
                "created_by", value(user.userId()))));
        copied.put("runs", copy("AS_COLLECT_RUN", "SYSTEM_ID IN (" + in + ")", sys, Map.of(
                "run_id", remap("RUN_ID", p), "system_id", remap("SYSTEM_ID", p))));
        copied.put("menus", copy("AS_MENU", "SYSTEM_ID IN (" + in + ")", sys, with(base, "menu_id", remap("MENU_ID", p))));
        copied.put("screens", copy("AS_SCREEN", "SYSTEM_ID IN (" + in + ")", sys,
                with(base, "screen_id", remap("SCREEN_ID", p))));
        copied.put("captures", copy("AS_CAPTURE", "SCREEN_ID IN (" + scrIn + ")", sys, Map.of(
                "capture_id", remap("CAPTURE_ID", p), "screen_id", remap("SCREEN_ID", p), "run_id", remap("RUN_ID", p))));
        copied.put("elements", copy("AS_ELEMENT", "SYSTEM_ID IN (" + in + ")", sys, with(base,
                "element_id", remap("ELEMENT_ID", p), "screen_id", remap("SCREEN_ID", p))));
        copied.put("nodes", copy("AS_NODE", "SYSTEM_ID IN (" + in + ")", sys, with(base, "node_id", remap("NODE_ID", p))));
        copied.put("tables", copy("AS_TABLE", "SYSTEM_ID IN (" + in + ")", sys, Map.of(
                "table_id", remap("TABLE_ID", p), "system_id", remap("SYSTEM_ID", p))));
        copy("AS_COLUMN", "TABLE_ID IN (" + tblIn + ")", sys, Map.of("table_id", remap("TABLE_ID", p)));
        copied.put("relations", copy("AS_RELATION", "SYSTEM_ID IN (" + in + ")", sys,
                with(base, "rel_id", remap("REL_ID", p))));
        copied.put("edges", copy("AS_EDGE", "SYSTEM_ID IN (" + in + ")", sys, with(base, "edge_id", remap("EDGE_ID", p))));
        copy("AS_ANALYSIS", "SCREEN_ID IN (" + scrIn + ")", sys, Map.of("screen_id", remap("SCREEN_ID", p)));
        copy("AS_ANALYSIS_HIST", "SCREEN_ID IN (" + scrIn + ")", sys, Map.of(
                "hist_id", remap("HIST_ID", p), "screen_id", remap("SCREEN_ID", p)));
        // 화면 첨부
        int att = copyAttachments("AS_SCREEN", scrIn, sys, p);
        if (inc.notes()) {
            copied.put("notes", copy("AS_NOTE", "SYSTEM_ID IN (" + in + ")", sys, Map.of(
                    "note_id", remap("NOTE_ID", p), "system_id", remap("SYSTEM_ID", p),
                    "target_id", remap("TARGET_ID", p))));
        } else {
            copied.put("notes", 0);
        }
        if (inc.deliverables()) {
            copied.put("deliverables", copy("DL_DELIVERABLE", "PROJECT_ID = ?", new Object[] {siProjectId}, Map.of(
                    "deliverable_id", remap("DELIVERABLE_ID", p), "project_id", value(smId), "wbs_id", nullValue())));
            att += copyAttachments("DL_DELIVERABLE", "SELECT DELIVERABLE_ID FROM DL_DELIVERABLE WHERE PROJECT_ID = ?",
                    new Object[] {siProjectId}, p);
        } else {
            copied.put("deliverables", 0);
        }
        copied.put("attachments", att);
        if (inc.traceLinks()) {
            String where = "PROJECT_ID = ? AND " + endpoint("FROM_TYPE", "FROM_ID", in, inc.deliverables()) + " AND "
                    + endpoint("TO_TYPE", "TO_ID", in, inc.deliverables());
            copied.put("traceLinks", copy("DL_TRACE_LINK", where, linkArgs(siProjectId, systems, inc.deliverables()),
                    Map.of("link_id", remap("LINK_ID", p), "project_id", value(smId), "from_id", remap("FROM_ID", p),
                            "to_id", remap("TO_ID", p), "created_at", value(now), "created_by", value(user.userId()))));
        } else {
            copied.put("traceLinks", 0);
        }
        copied.put("members", members(siProjectId, smId, req.memberUserIds(), pmUserId));
        copied.put("srs", inc.openDefectsAsSr() ? defectsToSr(siProjectId, smId, p, systems, user) : 0);
        audit.log(Action.CREATE, "handover:" + smId, "SI→SM 인수인계 " + copied, smId);
        return new Result(smId, copied);
    }

    private int members(String siProjectId, String smId, List<String> userIds, String pmUserId) {
        if (userIds == null) {
            return 0;
        }
        int n = 0;
        for (String u : userIds.stream().distinct().toList()) {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                    Integer.class, smId, u);
            if (exists != null && exists > 0) {
                continue;
            }
            String role = jdbc.query("SELECT ROLE_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                    (rs, i) -> rs.getString(1), siProjectId, u).stream().findFirst().orElse(null);
            if (role == null) {
                Integer user = jdbc.queryForObject("SELECT COUNT(*) FROM CM_USER WHERE USER_ID = ?", Integer.class, u);
                if (user == null || user == 0) {
                    throw PjSupport.validation("memberUserIds", "사용자를 찾을 수 없습니다: " + u);
                }
                role = "OPERATOR";
            }
            role = switch (role) {
                case "ANALYST", "DEV", "QA" -> "OPERATOR";
                case "PM" -> pmUserId != null && !pmUserId.equals(u) ? "PL" : "PM";
                default -> role;
            };
            jdbc.update("INSERT INTO PJ_MEMBER (PROJECT_ID, USER_ID, ROLE_ID, IN_DT) VALUES (?, ?, ?, ?)", smId, u, role,
                    LocalDate.now(Times.ZONE));
            n++;
        }
        return n;
    }

    /** 미해결 결함(종료·검증 제외) → SR(RECEIVED). 화면·요소는 복사본 ID 로 */
    private int defectsToSr(String siProjectId, String smId, String p, List<String> systems, AuthUser user) {
        String customer = jdbc.queryForObject("SELECT CUSTOMER FROM PJ_PROJECT WHERE PROJECT_ID = ?", String.class,
                siProjectId);
        record D(String id, String no, String title, String desc, String severity, String screenId, String elementId) {
        }
        List<D> defects = jdbc.query("""
                SELECT DEFECT_ID, DEFECT_NO, TITLE, DESCRIPTION, SEVERITY, SCREEN_ID, ELEMENT_ID FROM QA_DEFECT
                 WHERE PROJECT_ID = ? AND STATUS NOT IN ('CLOSED', 'VERIFIED') ORDER BY DEFECT_NO""",
                (rs, i) -> new D(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7)), siProjectId);
        Set<String> copiedScreens = new java.util.HashSet<>(jdbc.queryForList(
                "SELECT SCREEN_ID FROM AS_SCREEN WHERE SYSTEM_ID IN (" + in(systems) + ")", String.class,
                systems.toArray()));
        int n = 0;
        for (D d : defects) {
            boolean mapped = d.screenId() != null && copiedScreens.contains(d.screenId());
            String scr = mapped ? mapId(p, d.screenId()) : null;
            String el = mapped && d.elementId() != null ? mapId(p, d.elementId()) : null;
            String priority = switch (d.severity()) {
                case "CRITICAL" -> "P1";
                case "MAJOR" -> "P2";
                case "MINOR" -> "P3";
                default -> "P4";
            };
            var sr = srs.create(smId, new SrService.SrRequest("IMPROVE", priority, cut("[이관 결함] " + d.no() + " "
                    + d.title(), 300), d.desc(), "MANUAL", null, null, null, scr, el), "MANUAL",
                    // 요청 기관은 비워 둔다: 고객 포털(같은 소속사 SR 공개)에 내부 결함이 노출되지 않게
                    new SrService.Requester("SI 인수인계(" + (customer == null ? "-" : customer) + ")", null, null, null),
                    user, null, null);
            jdbc.update("UPDATE SM_SR SET ORIGIN_DEFECT_ID = ? WHERE SR_ID = ?", d.id(), sr.srId());
            n++;
        }
        return n;
    }

    private int copyAttachments(String targetType, String targetIn, Object[] args, String p) {
        String where = "TARGET_TYPE = '" + targetType + "' AND TARGET_ID IN (" + targetIn + ")";
        String attIn = "SELECT ATTACH_ID FROM CM_ATTACH WHERE " + where;
        // 첨부 먼저(버전이 첨부를 참조). 새 첨부의 TARGET_ID 는 복사본 ID 라 아래 버전 조건에 다시 걸리지 않는다
        int n = copy("CM_ATTACH", where, args, Map.of("attach_id", remap("ATTACH_ID", p),
                "target_id", remap("TARGET_ID", p)));
        copy("CM_ATTACH_VERSION", "ATTACH_ID IN (" + attIn + ")", args, Map.of("attach_id", remap("ATTACH_ID", p)));
        return n;
    }

    // ───────────────────────── 복사 도우미 ─────────────────────────

    /** SELECT 목록의 한 컬럼 식과 바인딩 값 */
    record Expr(String sql, List<Object> args) {
    }

    /** 원본 ID 를 새 접두어로 (NULL 유지, 26자 ULID 가 아니면 그대로) */
    static Expr remap(String col, String prefix) {
        return new Expr("CASE WHEN " + col + " IS NULL OR LENGTH(" + col + ") <> 26 THEN " + col
                + " ELSE CONCAT(CAST(? AS VARCHAR(10)), SUBSTRING(" + col + ", 11)) END", List.of(prefix));
    }

    static String mapId(String prefix, String id) {
        return id == null || id.length() != 26 ? id : prefix + id.substring(10);
    }

    static Expr value(String v) {
        return new Expr("CAST(? AS VARCHAR(200))", List.of(v));
    }

    static Expr value(OffsetDateTime v) {
        return new Expr("CAST(? AS TIMESTAMP WITH TIME ZONE)", List.of(v));
    }

    static Expr nullValue() {
        return new Expr("NULL", List.of());
    }

    private static Map<String, Expr> with(Map<String, Expr> base, Object... kv) {
        Map<String, Expr> m = new LinkedHashMap<>(base);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Expr) kv[i + 1]);
        }
        return m;
    }

    /** INSERT INTO table (모든 컬럼) SELECT (치환식 또는 원 컬럼) FROM table WHERE … — 컬럼 목록은 메타데이터로 */
    private int copy(String table, String where, Object[] whereArgs, Map<String, Expr> overrides) {
        List<String> cols = jdbc.query("SELECT * FROM " + table + " WHERE 1 = 0", rs -> {
            List<String> out = new ArrayList<>();
            var md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                out.add(md.getColumnName(i).toUpperCase(java.util.Locale.ROOT));   // 따옴표 없는 대문자 = 모든 DB 공통
            }
            return out;
        });
        StringBuilder select = new StringBuilder();
        List<Object> args = new ArrayList<>();
        for (String c : cols) {
            if (select.length() > 0) {
                select.append(", ");
            }
            Expr e = overrides.get(c.toLowerCase(java.util.Locale.ROOT));   // 치환 키는 소문자로 적는다
            if (e == null) {
                select.append(c);
            } else {
                select.append(e.sql());
                args.addAll(e.args());
            }
        }
        args.addAll(List.of(whereArgs));
        return jdbc.update("INSERT INTO " + table + " (" + String.join(", ", cols) + ") SELECT " + select + " FROM "
                + table + " WHERE " + where, args.toArray());
    }

    /** 인수인계마다 새 10자 접두어 (Crockford base32, 기존 ID 와 겹치지 않는지 확인) */
    private String prefix() {
        for (int t = 0; t < 10; t++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) {
                sb.append(CROCKFORD.charAt(RANDOM.nextInt(32)));
            }
            String p = sb.toString();
            Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM AS_SYSTEM WHERE SYSTEM_ID LIKE ?", Integer.class,
                    p + "%");
            if (used == null || used == 0) {
                return p;
            }
        }
        throw new IllegalStateException("ID 접두어 생성 실패");
    }

    private static String in(List<String> ids) {
        return String.join(",", ids.stream().map(x -> "?").toList());
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }

    private static String cut(String v, int max) {
        return v.length() <= max ? v : v.substring(0, max);
    }
}
