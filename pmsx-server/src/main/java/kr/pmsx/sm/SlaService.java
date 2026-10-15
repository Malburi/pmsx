package kr.pmsx.sm;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kr.pmsx.common.Times;
import kr.pmsx.common.Ulid;
import kr.pmsx.common.WorkCalendar;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.sm.SlaEngine.Event;
import kr.pmsx.sm.SlaEngine.Policy;
import kr.pmsx.sm.SlaEngine.SrSla;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** SLA 정책 저장·매칭·SR 시계 이벤트 기록과 일괄 평가 (docs/13 §1·§3). */
@Service
public class SlaService {

    /** SLA 평가에 필요한 SR 값 */
    public record Clock(String srId, String policyId, OffsetDateTime createdAt, OffsetDateTime respondedAt,
                        OffsetDateTime resolvedAt, String status) {
    }

    private final JdbcTemplate jdbc;
    private final WorkCalendar calendar;

    public SlaService(JdbcTemplate jdbc, WorkCalendar calendar) {
        this.jdbc = jdbc;
        this.calendar = calendar;
    }

    // ───────────────────────── 정책 ─────────────────────────

    public List<Policy> policies(String projectId) {
        return jdbc.query("""
                SELECT POLICY_ID, NAME, TYPES, PRIORITIES, RESPONSE_MIN, RESOLVE_MIN, BH_START, BH_END, BH_DAYS,
                       TWENTY_FOUR_SEVEN, ORD FROM SM_SLA_POLICY WHERE PROJECT_ID = ? ORDER BY ORD, NAME""",
                (rs, i) -> new Policy(rs.getString(1), rs.getString(2), PjSupport.csv(rs.getString(3)),
                        PjSupport.csv(rs.getString(4)), rs.getInt(5), rs.getInt(6), rs.getString(7), rs.getString(8),
                        PjSupport.csv(rs.getString(9)).stream().map(Integer::parseInt).toList(), rs.getBoolean(10),
                        rs.getInt(11)), projectId);
    }

    public Map<String, Policy> policyMap(String projectId) {
        Map<String, Policy> m = new HashMap<>();
        policies(projectId).forEach(p -> m.put(p.policyId(), p));
        return m;
    }

    /** SM 프로젝트가 생기거나 SM 으로 바뀌면 기본 정책 시드 */
    @org.springframework.context.event.EventListener
    public void onProjectSaved(kr.pmsx.pj.ProjectSavedEvent e) {
        if ("SM".equals(e.type())) {
            ensureDefaults(e.projectId());
        }
    }

    /** 정책이 하나도 없으면 기본 4종 (docs/13 §3 시드) */
    public void ensureDefaults(String projectId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM SM_SLA_POLICY WHERE PROJECT_ID = ?", Integer.class,
                projectId);
        if (n != null && n > 0) {
            return;
        }
        insert(projectId, "P1 긴급(24x7)", null, "P1", 30, 240, true, 1);
        insert(projectId, "P2 높음", null, "P2", 60, 540, false, 2);
        insert(projectId, "P3 보통", null, "P3", 240, 1620, false, 3);
        insert(projectId, "P4 낮음", null, "P4", 540, 2700, false, 4);
    }

    private void insert(String projectId, String name, String types, String priorities, int resp, int res,
            boolean allDay, int ord) {
        jdbc.update("""
                INSERT INTO SM_SLA_POLICY (POLICY_ID, PROJECT_ID, NAME, TYPES, PRIORITIES, RESPONSE_MIN, RESOLVE_MIN,
                                           BH_START, BH_END, BH_DAYS, TWENTY_FOUR_SEVEN, ORD, CREATED_AT)
                VALUES (?, ?, ?, ?, ?, ?, ?, '09:00', '18:00', '1,2,3,4,5', ?, ?, ?)""", Ulid.next(), projectId, name,
                types, priorities, resp, res, allDay, ord, Times.now());
    }

    /** 유형·우선순위로 첫 매칭 정책 (order 오름차순) */
    public Policy match(String projectId, String type, String priority) {
        ensureDefaults(projectId);
        return policies(projectId).stream().filter(p -> p.matches(type, priority)).findFirst().orElse(null);
    }

    // ───────────────────────── 시계 ─────────────────────────

    public void event(String srId, String event, OffsetDateTime at) {
        Integer seq = jdbc.queryForObject("SELECT COALESCE(MAX(SEQ), 0) + 1 FROM SM_SLA_EVENT WHERE SR_ID = ?",
                Integer.class, srId);
        jdbc.update("INSERT INTO SM_SLA_EVENT (SR_ID, SEQ, EVENT, EVENT_AT) VALUES (?, ?, ?, ?)", srId, seq, event, at);
    }

    public Map<String, List<Event>> events(Collection<String> srIds) {
        Map<String, List<Event>> out = new HashMap<>();
        List<String> list = new ArrayList<>(srIds);
        for (int i = 0; i < list.size(); i += 500) {
            List<String> chunk = list.subList(i, Math.min(list.size(), i + 500));
            String in = String.join(",", chunk.stream().map(x -> "?").toList());
            jdbc.query("SELECT SR_ID, EVENT, EVENT_AT FROM SM_SLA_EVENT WHERE SR_ID IN (" + in + ") ORDER BY SR_ID, SEQ",
                    rs -> {
                        out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>())
                                .add(new Event(rs.getString(2), Times.read(rs, "EVENT_AT")));
                    }, chunk.toArray());
        }
        return out;
    }

    /** 한 요청 안에서 정책·공휴일·이벤트를 한 번만 읽는 평가기 */
    public final class Evaluator {
        private final Map<String, Policy> policies = new HashMap<>();
        private final Set<LocalDate> holidays;
        private final Map<String, SlaCalculator> calcs = new HashMap<>();
        private final OffsetDateTime now;

        Evaluator(OffsetDateTime now) {
            this.holidays = calendar.load().holidays();
            this.now = now;
        }

        public SlaCalculator calculator(Policy p) {
            return calcs.computeIfAbsent(p.policyId(), k -> new SlaCalculator(p.hours(), holidays, Times.ZONE));
        }

        public Map<String, SrSla> evaluate(List<Clock> clocks) {
            Map<String, SrSla> out = new HashMap<>();
            List<Clock> withPolicy = clocks.stream().filter(c -> c.policyId() != null).toList();
            if (withPolicy.isEmpty()) {
                return out;
            }
            for (Clock c : withPolicy) {
                if (!policies.containsKey(c.policyId())) {
                    jdbc.query("SELECT PROJECT_ID FROM SM_SLA_POLICY WHERE POLICY_ID = ?", rs -> {
                        policies.putAll(policyMap(rs.getString(1)));
                    }, c.policyId());
                }
            }
            Map<String, List<Event>> ev = events(withPolicy.stream().map(Clock::srId).toList());
            for (Clock c : withPolicy) {
                Policy p = policies.get(c.policyId());
                if (p == null) {
                    continue;
                }
                out.put(c.srId(), SlaEngine.evaluate(p, calculator(p), c.createdAt(), c.respondedAt(),
                        c.resolvedAt(), c.status(), ev.getOrDefault(c.srId(), List.of()), now));
            }
            return out;
        }
    }

    public Evaluator evaluator() {
        return new Evaluator(Times.now());
    }

    public Evaluator evaluator(OffsetDateTime now) {
        return new Evaluator(now);
    }
}
