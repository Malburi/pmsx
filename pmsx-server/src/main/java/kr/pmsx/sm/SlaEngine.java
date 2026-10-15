package kr.pmsx.sm;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * SR SLA 상태 계산 (docs/13 §1). 저장된 값은 시각·이벤트뿐이고 기한·남은 시간·상태는 매번 계산한다.
 * <ul>
 *   <li>응답 기한 = 접수 + 응답 목표(업무시간), 해결 기한 = 접수 + 해결 목표 + 일시정지 시간(업무시간)</li>
 *   <li>일시정지 = PAUSE(보류)·STOP(해결/반려) 이후 RESUME 까지. 진행 중인 보류는 현재까지 포함, 해결 후 정지는 포함하지 않음</li>
 *   <li>DUE_SOON = 남은 시간 ≤ 해당 목표의 20% 또는 30분, 해결·반려되면 MET/BREACHED 로 고정</li>
 * </ul>
 */
public final class SlaEngine {

    private SlaEngine() {
    }

    public static final Set<String> STOPPED = Set.of("RESOLVED", "CONFIRMED", "CLOSED", "REJECTED");

    public record Policy(String policyId, String name, List<String> types, List<String> priorities,
                         int responseMinutes, int resolveMinutes, String bhStart, String bhEnd, List<Integer> bhDays,
                         boolean twentyFourSeven, int order) {

        public boolean matches(String type, String priority) {
            return (types == null || types.isEmpty() || types.contains(type))
                    && (priorities == null || priorities.isEmpty() || priorities.contains(priority));
        }

        public SlaCalculator.Hours hours() {
            if (twentyFourSeven) {
                return SlaCalculator.Hours.twentyFourSeven();
            }
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (Integer d : bhDays == null || bhDays.isEmpty() ? List.of(1, 2, 3, 4, 5) : bhDays) {
                days.add(DayOfWeek.of(d));
            }
            return new SlaCalculator.Hours(LocalTime.parse(bhStart), LocalTime.parse(bhEnd), days, false);
        }
    }

    public record Event(String event, OffsetDateTime at) {
    }

    /** docs/13 SrSla */
    public record SrSla(String policyId, String policyName, OffsetDateTime responseDue, OffsetDateTime resolveDue,
                        OffsetDateTime respondedAt, OffsetDateTime resolvedAt, long pausedMinutes,
                        boolean responseBreached, boolean resolveBreached, long remainingMinutes, String state) {
    }

    public static SrSla evaluate(Policy p, SlaCalculator calc, OffsetDateTime createdAt, OffsetDateTime respondedAt,
            OffsetDateTime resolvedAt, String status, List<Event> events, OffsetDateTime now) {
        boolean stopped = STOPPED.contains(status);
        OffsetDateTime stopAt = null;
        long paused = 0;
        OffsetDateTime open = null;
        for (Event e : events) {
            switch (e.event()) {
                case "PAUSE", "STOP" -> {
                    if (open == null) {
                        open = e.at();
                    }
                    if ("STOP".equals(e.event())) {
                        stopAt = e.at();
                    }
                }
                case "RESUME" -> {
                    if (open != null) {
                        paused += calc.minutesBetween(open, e.at());
                        open = null;
                    }
                    stopAt = null;
                }
                default -> {
                }
            }
        }
        if (open != null && "ON_HOLD".equals(status)) {
            paused += calc.minutesBetween(open, now);
        }
        OffsetDateTime eval = now;
        if (stopped) {
            eval = stopAt != null ? stopAt : resolvedAt != null ? resolvedAt : now;
        }
        OffsetDateTime responseDue = calc.plus(createdAt, p.responseMinutes());
        OffsetDateTime resolveDue = calc.plus(createdAt, p.resolveMinutes() + paused);
        OffsetDateTime respAt = respondedAt != null ? respondedAt : eval;
        boolean responseBreached = respAt.isAfter(responseDue);
        boolean resolveBreached = eval.isAfter(resolveDue);
        boolean responding = respondedAt == null && !stopped;
        OffsetDateTime target = responding ? responseDue : resolveDue;
        long remaining = calc.minutesBetween(eval, target);
        String state;
        if (stopped) {
            state = responseBreached || resolveBreached ? "BREACHED" : "MET";
        } else if (responseBreached || resolveBreached) {
            state = "BREACHED";          // 한 번 위반하면 종료 전까지 위반으로 표시
        } else if ("ON_HOLD".equals(status)) {
            state = "PAUSED";
        } else {
            int goal = responding ? p.responseMinutes() : p.resolveMinutes();
            state = remaining <= Math.max(goal * 0.2, 30) ? "DUE_SOON" : "OK";
        }
        return new SrSla(p.policyId(), p.name(), responseDue, resolveDue, respondedAt, stopped ? eval : resolvedAt,
                paused, responseBreached, resolveBreached, remaining, state);
    }
}
