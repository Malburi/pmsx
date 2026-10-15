package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import kr.pmsx.common.Times;
import kr.pmsx.common.WorkCalendar;
import kr.pmsx.sm.RoutineController;
import kr.pmsx.sm.SlaCalculator;
import kr.pmsx.sm.SlaEngine;
import kr.pmsx.sm.SlaEngine.Event;
import kr.pmsx.sm.SlaEngine.Policy;
import kr.pmsx.sm.SlaEngine.SrSla;
import kr.pmsx.sm.Similarity;
import org.junit.jupiter.api.Test;

/** SLA 업무시간 계산·상태 판정 (분 단위 정확성), 정기 작업 발생일, 유사도 — 순수 로직 단위 테스트 */
class SlaCalculatorTest {

    /** 2026-11-02 = 월요일 */
    static OffsetDateTime t(String s) {
        return OffsetDateTime.parse(s + "+09:00");
    }

    static final SlaCalculator BIZ = new SlaCalculator(SlaCalculator.Hours.weekdays("09:00", "18:00"), Set.of(),
            Times.ZONE);
    static final SlaCalculator ALL = new SlaCalculator(SlaCalculator.Hours.twentyFourSeven(), Set.of(), Times.ZONE);

    @Test
    void 업무시간_더하기_경계() {
        assertThat(BIZ.plus(t("2026-11-02T17:30:00"), 60)).isEqualTo(t("2026-11-03T09:30:00"));   // 월 17:30 + 1h → 화 09:30
        assertThat(BIZ.plus(t("2026-11-02T17:00:00"), 60)).isEqualTo(t("2026-11-02T18:00:00"));   // 정확히 마감
        assertThat(BIZ.plus(t("2026-11-02T08:00:00"), 30)).isEqualTo(t("2026-11-02T09:30:00"));   // 업무 전 시작
        assertThat(BIZ.plus(t("2026-11-02T19:00:00"), 30)).isEqualTo(t("2026-11-03T09:30:00"));   // 업무 후 시작
        assertThat(BIZ.plus(t("2026-11-06T17:00:00"), 120)).isEqualTo(t("2026-11-09T10:00:00"));  // 금 → 월
        assertThat(BIZ.plus(t("2026-11-07T10:00:00"), 30)).isEqualTo(t("2026-11-09T09:30:00"));   // 토요일 시작
        assertThat(BIZ.plus(t("2026-11-02T09:00:00"), 540)).isEqualTo(t("2026-11-02T18:00:00"));  // 1영업일
        assertThat(BIZ.plus(t("2026-11-02T09:00:00"), 1620)).isEqualTo(t("2026-11-04T18:00:00")); // 3영업일
        assertThat(BIZ.plus(t("2026-11-02T10:15:30"), 1)).isEqualTo(t("2026-11-02T10:16:30"));    // 초 유지
    }

    @Test
    void 공휴일_건너뛰기() {
        SlaCalculator c = new SlaCalculator(SlaCalculator.Hours.weekdays("09:00", "18:00"),
                Set.of(LocalDate.of(2026, 11, 3)), Times.ZONE);
        assertThat(c.plus(t("2026-11-02T17:00:00"), 120)).isEqualTo(t("2026-11-04T10:00:00"));
        assertThat(c.minutesBetween(t("2026-11-02T17:00:00"), t("2026-11-04T10:00:00"))).isEqualTo(120);
    }

    @Test
    void 업무시간_경과분() {
        assertThat(BIZ.minutesBetween(t("2026-11-06T17:00:00"), t("2026-11-09T10:00:00"))).isEqualTo(120);
        assertThat(BIZ.minutesBetween(t("2026-11-07T00:00:00"), t("2026-11-08T23:59:00"))).isZero();   // 주말
        assertThat(BIZ.minutesBetween(t("2026-11-09T10:00:00"), t("2026-11-06T17:00:00"))).isEqualTo(-120);
        assertThat(BIZ.minutesBetween(t("2026-11-02T09:00:00"), t("2026-11-02T09:00:59"))).isZero();     // 분 미만 버림
        assertThat(ALL.minutesBetween(t("2026-11-07T10:00:00"), t("2026-11-08T10:00:00"))).isEqualTo(1440);
    }

    @Test
    void 빼기는_더하기의_역() {
        for (String s : List.of("2026-11-02T09:00:00", "2026-11-02T13:37:00", "2026-11-06T17:59:00",
                "2026-11-04T11:11:00")) {
            for (long m : List.of(1L, 30L, 59L, 540L, 1000L, 2700L)) {
                OffsetDateTime a = t(s);
                assertThat(BIZ.minus(BIZ.plus(a, m), m)).as(s + " ± " + m).isEqualTo(a);
                assertThat(BIZ.minutesBetween(a, BIZ.plus(a, m))).isEqualTo(m);
            }
        }
        assertThat(BIZ.minus(t("2026-11-09T09:30:00"), 60)).isEqualTo(t("2026-11-06T17:30:00"));
        assertThat(ALL.minus(t("2026-11-02T00:10:00"), 20)).isEqualTo(t("2026-11-01T23:50:00"));
    }

    // ───────────────────────── SLA 상태 ─────────────────────────

    static final Policy P2 = new Policy("p2", "P2", List.of(), List.of("P2"), 60, 540, "09:00", "18:00",
            List.of(1, 2, 3, 4, 5), false, 2);
    static final Policy P1 = new Policy("p1", "P1", List.of(), List.of("P1"), 30, 240, "09:00", "18:00",
            List.of(1, 2, 3, 4, 5), true, 1);

    static SrSla eval(Policy p, String created, String responded, String resolved, String status, List<Event> ev,
            String now) {
        SlaCalculator c = p.twentyFourSeven() ? ALL : BIZ;
        return SlaEngine.evaluate(p, c, t(created), responded == null ? null : t(responded),
                resolved == null ? null : t(resolved), status, ev, t(now));
    }

    static Event e(String type, String at) {
        return new Event(type, t(at));
    }

    @Test
    void 응답_임박과_위반() {
        SrSla ok = eval(P2, "2026-11-02T10:00:00", null, null, "RECEIVED", List.of(), "2026-11-02T10:20:00");
        assertThat(ok.state()).isEqualTo("OK");
        assertThat(ok.remainingMinutes()).isEqualTo(40);
        assertThat(ok.responseDue()).isEqualTo(t("2026-11-02T11:00:00"));
        assertThat(ok.resolveDue()).isEqualTo(t("2026-11-03T10:00:00"));
        SrSla soon = eval(P2, "2026-11-02T10:00:00", null, null, "ASSIGNED", List.of(), "2026-11-02T10:30:00");
        assertThat(soon.state()).isEqualTo("DUE_SOON");   // 남은 30분 ≤ max(12, 30)
        assertThat(soon.remainingMinutes()).isEqualTo(30);
        SrSla late = eval(P2, "2026-11-02T10:00:00", null, null, "ASSIGNED", List.of(), "2026-11-02T11:01:00");
        assertThat(late.state()).isEqualTo("BREACHED");
        assertThat(late.responseBreached()).isTrue();
        assertThat(late.remainingMinutes()).isEqualTo(-1);
        // 업무시간 밖에서는 시계가 멈춘다: 17:50 접수 → 응답 기한 다음날 09:50
        SrSla night = eval(P2, "2026-11-02T17:50:00", null, null, "RECEIVED", List.of(), "2026-11-02T23:00:00");
        assertThat(night.responseDue()).isEqualTo(t("2026-11-03T09:50:00"));
        assertThat(night.remainingMinutes()).isEqualTo(50);
        assertThat(night.state()).isEqualTo("OK");
    }

    @Test
    void 해결_임박_기준_20퍼센트() {
        // 응답 완료, 해결 540분 중 남은 108분(20%) → 임박, 109분 → 정상
        SrSla a = eval(P2, "2026-11-02T09:00:00", "2026-11-02T09:10:00", null, "IN_PROGRESS", List.of(),
                "2026-11-02T16:12:00");
        assertThat(a.remainingMinutes()).isEqualTo(108);
        assertThat(a.state()).isEqualTo("DUE_SOON");
        SrSla b = eval(P2, "2026-11-02T09:00:00", "2026-11-02T09:10:00", null, "IN_PROGRESS", List.of(),
                "2026-11-02T16:11:00");
        assertThat(b.remainingMinutes()).isEqualTo(109);
        assertThat(b.state()).isEqualTo("OK");
    }

    @Test
    void 보류는_해결기한을_미룬다() {
        List<Event> ev = List.of(e("START", "2026-11-02T09:00:00"), e("PAUSE", "2026-11-02T10:00:00"));
        SrSla hold = eval(P2, "2026-11-02T09:00:00", "2026-11-02T09:05:00", null, "ON_HOLD", ev,
                "2026-11-02T12:00:00");
        assertThat(hold.state()).isEqualTo("PAUSED");
        assertThat(hold.pausedMinutes()).isEqualTo(120);
        assertThat(hold.resolveDue()).isEqualTo(t("2026-11-03T11:00:00"));
        // 재개 후 해결 → 충족
        List<Event> ev2 = List.of(e("START", "2026-11-02T09:00:00"), e("PAUSE", "2026-11-02T10:00:00"),
                e("RESUME", "2026-11-02T12:00:00"), e("STOP", "2026-11-03T10:30:00"));
        SrSla met = eval(P2, "2026-11-02T09:00:00", "2026-11-02T09:05:00", "2026-11-03T10:30:00", "RESOLVED", ev2,
                "2026-11-05T10:00:00");
        assertThat(met.state()).isEqualTo("MET");
        assertThat(met.resolveBreached()).isFalse();
        assertThat(met.pausedMinutes()).isEqualTo(120);
        // 보류가 없었다면 위반
        SrSla noHold = eval(P2, "2026-11-02T09:00:00", "2026-11-02T09:05:00", "2026-11-03T10:30:00", "RESOLVED",
                List.of(e("START", "2026-11-02T09:00:00"), e("STOP", "2026-11-03T10:30:00")), "2026-11-05T10:00:00");
        assertThat(noHold.state()).isEqualTo("BREACHED");
        assertThat(noHold.resolveBreached()).isTrue();
    }

    @Test
    void 재요청은_해결후_정지구간을_제외한다() {
        // 11-02 09:00 접수, 11:00 해결(STOP), 11-03 09:00 재요청(RESUME) → 정지 구간 업무시간 7h(=420분) 제외
        List<Event> ev = List.of(e("START", "2026-11-02T09:00:00"), e("STOP", "2026-11-02T11:00:00"),
                e("RESUME", "2026-11-03T09:00:00"));
        SrSla x = eval(P2, "2026-11-02T09:00:00", "2026-11-02T09:05:00", null, "IN_PROGRESS", ev,
                "2026-11-03T12:00:00");
        assertThat(x.pausedMinutes()).isEqualTo(420);
        assertThat(x.resolveDue()).isEqualTo(t("2026-11-03T16:00:00"));   // 540+420분 = 월 9h + 화 7h
        assertThat(x.remainingMinutes()).isEqualTo(240);
        assertThat(x.state()).isEqualTo("OK");
    }

    @Test
    void 위반은_종료전까지_유지되고_24x7은_벽시계() {
        SrSla p1 = eval(P1, "2026-11-07T23:00:00", null, null, "RECEIVED", List.of(), "2026-11-07T23:10:00");
        assertThat(p1.responseDue()).isEqualTo(t("2026-11-07T23:30:00"));   // 토요일 밤도 계산
        assertThat(p1.state()).isEqualTo("DUE_SOON");                        // 남은 20분 ≤ 30분
        SrSla late = eval(P1, "2026-11-07T23:00:00", "2026-11-08T00:10:00", null, "IN_PROGRESS", List.of(),
                "2026-11-08T00:20:00");
        assertThat(late.responseBreached()).isTrue();
        assertThat(late.state()).isEqualTo("BREACHED");
        // 늦은 응답 후 해결 기한 안에 해결해도 위반으로 기록
        SrSla done = eval(P1, "2026-11-07T23:00:00", "2026-11-08T00:10:00", "2026-11-08T01:00:00", "CLOSED",
                List.of(e("STOP", "2026-11-08T01:00:00")), "2026-11-09T09:00:00");
        assertThat(done.state()).isEqualTo("BREACHED");
        assertThat(done.resolveBreached()).isFalse();
        assertThat(done.resolvedAt()).isEqualTo(t("2026-11-08T01:00:00"));
    }

    // ───────────────────────── 정기 작업·유사도 ─────────────────────────

    @Test
    void 정기작업_발생일() {
        WorkCalendar.Days days = new WorkCalendar.Days(Set.of(LocalDate.of(2026, 11, 3)));
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);
        assertThat(RoutineController.occurrences(new RoutineController.Rule("DAILY", null, null), from, to, days))
                .hasSize(20).doesNotContain(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 7));
        assertThat(RoutineController.occurrences(new RoutineController.Rule("WEEKLY", List.of(1, 5), null), from, to,
                days)).containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 9),
                LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 20),
                LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 27), LocalDate.of(2026, 11, 30));
        assertThat(RoutineController.occurrences(new RoutineController.Rule("MONTHLY", null, 31),
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 4, 30), days)).containsExactly(LocalDate.of(2027, 1, 31),
                LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30));
    }

    @Test
    void 한글_2gram_유사도() {
        var docs = List.of(new Similarity.Doc("KB", "1", "KB-0001", "비밀번호 초기화 절차", "계정 잠금 해제와 임시 비밀번호"),
                new Similarity.Doc("KB", "2", "KB-0002", "엑셀 다운로드가 느릴 때", "조회 조건"),
                new Similarity.Doc("SR", "3", "SR-0003", "부서 코드 변경", "조직개편"));
        var top = Similarity.top("비밀번호 초기화 요청드립니다 계정이 잠겼어요", docs, 5, 0.05);
        assertThat(top).isNotEmpty();
        assertThat(top.get(0).doc().id()).isEqualTo("1");
        assertThat(Similarity.tokens("SR 저장오류")).containsExactly("sr", "저장", "장오", "오류");
        assertThat(Similarity.jaccard("사원 저장 오류", "사원 저장 시 오류")).isGreaterThan(0.45);
    }
}
