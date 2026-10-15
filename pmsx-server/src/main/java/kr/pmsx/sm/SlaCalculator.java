package kr.pmsx.sm;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

/**
 * SLA 업무시간 계산 (docs/13 §1·§3). 업무시간 창 [start, end) 이 요일·공휴일에 따라 열리고, 24x7 이면 벽시계 시간 그대로.
 * 모든 결과는 분 단위(초 이하 버림)이며 경계는 반열린 구간이다: 18:00 정각 마감은 18:00 으로 표시된다.
 */
public final class SlaCalculator {

    /** 업무시간 정의. days 가 비면 월~금. */
    public record Hours(LocalTime start, LocalTime end, Set<DayOfWeek> days, boolean allDay) {

        public static Hours weekdays(String start, String end) {
            return new Hours(LocalTime.parse(start), LocalTime.parse(end),
                    EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), false);
        }

        public static Hours twentyFourSeven() {
            return new Hours(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, EnumSet.allOf(DayOfWeek.class), true);
        }
    }

    /** 무한 루프 방지: 영업일이 하나도 없는 설정에서 최대 탐색 일수 */
    private static final int MAX_DAYS = 3660;

    private final Hours hours;
    private final Set<LocalDate> holidays;
    private final ZoneId zone;

    public SlaCalculator(Hours hours, Set<LocalDate> holidays, ZoneId zone) {
        if (!hours.allDay() && !hours.end().isAfter(hours.start())) {
            throw new IllegalArgumentException("업무 종료 시각은 시작 시각보다 늦어야 합니다.");
        }
        this.hours = hours;
        this.holidays = holidays == null ? Set.of() : holidays;
        this.zone = zone;
    }

    public boolean allDay() {
        return hours.allDay();
    }

    private boolean workday(LocalDate d) {
        return hours.days().contains(d.getDayOfWeek()) && !holidays.contains(d);
    }

    private ZonedDateTime open(LocalDate d) {
        return d.atTime(hours.start()).atZone(zone);
    }

    private ZonedDateTime close(LocalDate d) {
        return d.atTime(hours.end()).atZone(zone);
    }

    /** a~b 사이 업무 시간(분). b < a 면 음수. */
    public long minutesBetween(OffsetDateTime a, OffsetDateTime b) {
        if (a == null || b == null) {
            return 0;
        }
        if (b.isBefore(a)) {
            return -minutesBetween(b, a);
        }
        if (hours.allDay()) {
            return Duration.between(a, b).toMinutes();
        }
        long seconds = 0;
        ZonedDateTime za = a.atZoneSameInstant(zone);
        ZonedDateTime zb = b.atZoneSameInstant(zone);
        LocalDate d = za.toLocalDate();
        LocalDate last = zb.toLocalDate();
        for (int i = 0; !d.isAfter(last) && i < MAX_DAYS * 10; d = d.plusDays(1), i++) {
            if (!workday(d)) {
                continue;
            }
            ZonedDateTime ws = open(d);
            ZonedDateTime we = close(d);
            ZonedDateTime s = za.isAfter(ws) ? za : ws;
            ZonedDateTime e = zb.isBefore(we) ? zb : we;
            if (e.isAfter(s)) {
                seconds += Duration.between(s, e).getSeconds();
            }
        }
        return seconds / 60;
    }

    /** start 에서 업무 시간 minutes 분이 지난 시각. 업무시간 밖에서 시작하면 다음 업무 시작부터 센다. */
    public OffsetDateTime plus(OffsetDateTime start, long minutes) {
        if (minutes < 0) {
            return minus(start, -minutes);
        }
        if (hours.allDay()) {
            return start.plusMinutes(minutes);
        }
        long remaining = minutes * 60;
        ZonedDateTime t = start.atZoneSameInstant(zone);
        LocalDate d = t.toLocalDate();
        for (int i = 0; i < MAX_DAYS; d = d.plusDays(1), i++) {
            if (!workday(d)) {
                continue;
            }
            ZonedDateTime ws = open(d);
            ZonedDateTime we = close(d);
            if (t.isBefore(ws)) {
                t = ws;
            }
            if (!t.isBefore(we)) {
                continue;
            }
            long avail = Duration.between(t, we).getSeconds();
            if (remaining <= avail) {
                return t.plusSeconds(remaining).toOffsetDateTime();
            }
            remaining -= avail;
        }
        throw new IllegalStateException("업무시간이 없는 SLA 정책입니다.");
    }

    /** end 에서 업무 시간 minutes 분 이전 시각 (plus 의 역). */
    public OffsetDateTime minus(OffsetDateTime end, long minutes) {
        if (minutes < 0) {
            return plus(end, -minutes);
        }
        if (hours.allDay()) {
            return end.minusMinutes(minutes);
        }
        long remaining = minutes * 60;
        ZonedDateTime t = end.atZoneSameInstant(zone);
        LocalDate d = t.toLocalDate();
        for (int i = 0; i < MAX_DAYS; d = d.minusDays(1), i++) {
            if (!workday(d)) {
                continue;
            }
            ZonedDateTime ws = open(d);
            ZonedDateTime we = close(d);
            if (t.isAfter(we)) {
                t = we;
            }
            if (!t.isAfter(ws)) {
                continue;
            }
            long avail = Duration.between(ws, t).getSeconds();
            if (remaining <= avail) {
                return t.minusSeconds(remaining).toOffsetDateTime();
            }
            remaining -= avail;
        }
        throw new IllegalStateException("업무시간이 없는 SLA 정책입니다.");
    }
}
