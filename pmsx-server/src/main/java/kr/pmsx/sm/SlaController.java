package kr.pmsx.sm;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.audit.AuditService;
import kr.pmsx.common.audit.AuditService.Action;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import kr.pmsx.sm.SlaEngine.Policy;
import kr.pmsx.sm.SlaEngine.SrSla;
import kr.pmsx.sm.SrService.Row;
import kr.pmsx.sm.SrService.Sr;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SLA 정책 CRUD·SLA 현황 (FR-SM-004·005, docs/13 §3). */
@RestController
@RequestMapping("/api/v1")
public class SlaController {

    public record BusinessHours(String start, String end, List<Integer> days) {
    }

    public record SlaPolicy(String policyId, String name, List<String> types, List<String> priorities,
                            int responseMinutes, int resolveMinutes, BusinessHours businessHours,
                            boolean twentyFourSeven, int order) {
    }

    public record TypeStat(int total, int met) {
    }

    public record SlaStatus(String month, double complianceRate, double responseRate, int total, int met,
                            int breached, List<Sr> dueSoon, List<Sr> breachedList, Map<String, TypeStat> byType) {
    }

    private final JdbcTemplate jdbc;
    private final ProjectAccess access;
    private final SlaService sla;
    private final SrService srs;
    private final AuditService audit;

    public SlaController(JdbcTemplate jdbc, ProjectAccess access, SlaService sla, SrService srs, AuditService audit) {
        this.jdbc = jdbc;
        this.access = access;
        this.sla = sla;
        this.srs = srs;
        this.audit = audit;
    }

    static SlaPolicy dto(Policy p) {
        return new SlaPolicy(p.policyId(), p.name(), p.types(), p.priorities(), p.responseMinutes(),
                p.resolveMinutes(), new BusinessHours(p.bhStart(), p.bhEnd(), p.bhDays()), p.twentyFourSeven(),
                p.order());
    }

    @GetMapping("/projects/{projectId}/sla-policies")
    @Transactional
    public List<SlaPolicy> list(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId) {
        access.forProject(user, projectId);
        if (isSm(projectId)) {
            sla.ensureDefaults(projectId);
        }
        return sla.policies(projectId).stream().map(SlaController::dto).toList();
    }

    private boolean isSm(String projectId) {
        return "SM".equals(jdbc.queryForObject("SELECT TYPE FROM PJ_PROJECT WHERE PROJECT_ID = ?", String.class,
                projectId));
    }

    @PostMapping("/projects/{projectId}/sla-policies")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public SlaPolicy create(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestBody SlaPolicy req) {
        access.forProject(user, projectId).require(SmPerm.SLA_EDIT);
        String id = Ulid.next();
        Object[] v = validate(req);
        jdbc.update("""
                INSERT INTO SM_SLA_POLICY (POLICY_ID, PROJECT_ID, NAME, TYPES, PRIORITIES, RESPONSE_MIN, RESOLVE_MIN,
                                           BH_START, BH_END, BH_DAYS, TWENTY_FOUR_SEVEN, ORD, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, projectId, v[0], v[1], v[2], v[3], v[4], v[5],
                v[6], v[7], req.twentyFourSeven(), req.order(), Times.now());
        audit.log(Action.CREATE, "sla-policy:" + id, "SLA 정책 등록 " + v[0], projectId);
        return one(id);
    }

    private SlaPolicy one(String id) {
        String projectId = projectOf(id);
        return sla.policies(projectId).stream().filter(p -> p.policyId().equals(id)).map(SlaController::dto)
                .findFirst().orElseThrow(() -> ApiException.notFound("SLA 정책"));
    }

    private String projectOf(String id) {
        return jdbc.query("SELECT PROJECT_ID FROM SM_SLA_POLICY WHERE POLICY_ID = ?", (rs, i) -> rs.getString(1), id)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("SLA 정책"));
    }

    /** [name, types, priorities, resp, res, bhStart, bhEnd, bhDays] */
    private static Object[] validate(SlaPolicy req) {
        String name = PjSupport.required("name", req.name(), 200);
        for (String t : req.types() == null ? List.<String>of() : req.types()) {
            if (!SmPerm.SR_TYPES.contains(t)) {
                throw PjSupport.validation("types", "알 수 없는 유형: " + t);
            }
        }
        for (String p : req.priorities() == null ? List.<String>of() : req.priorities()) {
            if (!SmPerm.PRIORITIES.contains(p)) {
                throw PjSupport.validation("priorities", "알 수 없는 우선순위: " + p);
            }
        }
        if (req.responseMinutes() <= 0) {
            throw PjSupport.validation("responseMinutes", "1분 이상이어야 합니다.");
        }
        if (req.resolveMinutes() < req.responseMinutes()) {
            throw PjSupport.validation("resolveMinutes", "해결 목표는 응답 목표 이상이어야 합니다.");
        }
        BusinessHours bh = req.businessHours() == null ? new BusinessHours("09:00", "18:00", List.of(1, 2, 3, 4, 5))
                : req.businessHours();
        String start = bh.start() == null ? "09:00" : bh.start();
        String end = bh.end() == null ? "18:00" : bh.end();
        List<Integer> days = bh.days() == null || bh.days().isEmpty() ? List.of(1, 2, 3, 4, 5) : bh.days();
        try {
            if (!req.twentyFourSeven() && !java.time.LocalTime.parse(end).isAfter(java.time.LocalTime.parse(start))) {
                throw PjSupport.validation("businessHours.end", "종료 시각은 시작 시각보다 늦어야 합니다.");
            }
        } catch (java.time.format.DateTimeParseException e) {
            throw PjSupport.validation("businessHours", "시각은 HH:mm 형식이어야 합니다.");
        }
        for (Integer d : days) {
            if (d == null || d < 1 || d > 7) {
                throw PjSupport.validation("businessHours.days", "요일은 1(월)~7(일)입니다.");
            }
        }
        return new Object[] {name, PjSupport.csv(req.types()), PjSupport.csv(req.priorities()), req.responseMinutes(),
                req.resolveMinutes(), start, end, PjSupport.csv(days.stream().map(String::valueOf).toList())};
    }

    @PutMapping("/sla-policies/{policyId}")
    @Transactional
    public SlaPolicy update(@AuthenticationPrincipal AuthUser user, @PathVariable String policyId,
            @RequestBody SlaPolicy req) {
        String projectId = projectOf(policyId);
        access.forProject(user, projectId).require(SmPerm.SLA_EDIT);
        Object[] v = validate(req);
        jdbc.update("""
                UPDATE SM_SLA_POLICY SET NAME = ?, TYPES = ?, PRIORITIES = ?, RESPONSE_MIN = ?, RESOLVE_MIN = ?,
                       BH_START = ?, BH_END = ?, BH_DAYS = ?, TWENTY_FOUR_SEVEN = ?, ORD = ?, UPDATED_AT = ?
                 WHERE POLICY_ID = ?""", v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], req.twentyFourSeven(),
                req.order(), Times.now(), policyId);
        audit.log(Action.UPDATE, "sla-policy:" + policyId, "SLA 정책 수정 " + v[0], projectId);
        return one(policyId);
    }

    @DeleteMapping("/sla-policies/{policyId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void delete(@AuthenticationPrincipal AuthUser user, @PathVariable String policyId) {
        String projectId = projectOf(policyId);
        access.forProject(user, projectId).require(SmPerm.SLA_EDIT);
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM SM_SR WHERE POLICY_ID = ?", Integer.class, policyId);
        if (used != null && used > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "CONFLICT", "SR " + used + "건이 사용 중인 정책은 삭제할 수 없습니다.");
        }
        jdbc.update("DELETE FROM SM_SLA_POLICY WHERE POLICY_ID = ?", policyId);
        audit.log(Action.DELETE, "sla-policy:" + policyId, "SLA 정책 삭제", projectId);
    }

    // ───────────────────────── 현황 ─────────────────────────

    /**
     * month 에 접수된 SR(반려 제외) 기준: 위반 = 응답 또는 해결 기한 초과, 준수율 = (전체 - 위반) / 전체,
     * 응답률 = 응답 기한 내 응답 / 전체. dueSoon·breachedList 는 현재 진행 중인 SR 전체에서.
     */
    @GetMapping("/projects/{projectId}/sla/status")
    public SlaStatus status(@AuthenticationPrincipal AuthUser user, @PathVariable String projectId,
            @RequestParam(required = false) String month) {
        access.forProject(user, projectId);
        return compute(projectId, month == null || month.isBlank() ? YearMonth.now(Times.ZONE) : parse(month));
    }

    static YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (RuntimeException e) {
            throw PjSupport.validation("month", "YYYY-MM 형식이어야 합니다.");
        }
    }

    public SlaStatus compute(String projectId, YearMonth ym) {
        OffsetDateTime from = ym.atDay(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
        OffsetDateTime to = ym.plusMonths(1).atDay(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
        List<Row> month = srs.rows("R.PROJECT_ID = ? AND R.CREATED_AT >= ? AND R.CREATED_AT < ? AND R.STATUS <> 'REJECTED'"
                + " ORDER BY R.CREATED_AT", projectId, from, to);
        List<Row> open = srs.rows("R.PROJECT_ID = ? AND R.STATUS NOT IN ('RESOLVED', 'CONFIRMED', 'CLOSED', 'REJECTED')"
                + " ORDER BY R.CREATED_AT", projectId);
        List<Row> all = new ArrayList<>(month);
        all.addAll(open);
        Map<String, SrSla> s = srs.evaluate(all);
        int total = 0;
        int breached = 0;
        int met = 0;
        int responded = 0;
        Map<String, int[]> byType = new LinkedHashMap<>();
        for (String t : List.of("INQUIRY", "IMPROVE", "DATA", "INCIDENT", "AUTH")) {
            byType.put(t, new int[2]);
        }
        for (Row r : month) {
            SrSla x = s.get(r.srId());
            if (x == null) {
                continue;
            }
            total++;
            boolean bad = x.responseBreached() || x.resolveBreached();
            if (bad) {
                breached++;
            }
            if (!x.responseBreached()) {
                responded++;
            }
            boolean ok = "MET".equals(x.state());
            if (ok) {
                met++;
            }
            int[] c = byType.computeIfAbsent(r.type(), k -> new int[2]);
            c[0]++;
            if (!bad) {
                c[1]++;
            }
        }
        Map<String, TypeStat> types = new LinkedHashMap<>();
        byType.forEach((k, v) -> types.put(k, new TypeStat(v[0], v[1])));
        List<Row> soon = open.stream().filter(r -> s.get(r.srId()) != null
                && "DUE_SOON".equals(s.get(r.srId()).state())).toList();
        List<Row> bad = open.stream().filter(r -> s.get(r.srId()) != null
                && "BREACHED".equals(s.get(r.srId()).state())).toList();
        return new SlaStatus(ym.toString(), pct(total - breached, total), pct(responded, total), total, met, breached,
                srs.assemble(soon, s, false, false), srs.assemble(bad, s, false, false), types);
    }

    static double pct(int n, int total) {
        return total == 0 ? 100.0 : Math.round(n * 1000.0 / total) / 10.0;
    }
}
