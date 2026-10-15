package kr.pmsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import kr.pmsx.common.WorkCalendar;
import kr.pmsx.pj.wbs.Cpm;
import org.junit.jupiter.api.Test;

/** CPM·영업일 계산 단위 테스트 (스프링 없이) */
class CpmTest {

    @Test
    void 임계경로_FS_체인과_여유있는_병렬작업() {
        // A(3일) → B(2일) → M(마일스톤), C(1일)는 A 와 병렬로 시작해 여유가 있다
        var tasks = List.of(new Cpm.Task("A", 0, 3), new Cpm.Task("B", 3, 2), new Cpm.Task("C", 0, 1),
                new Cpm.Task("M", 5, 0));
        var deps = List.of(new Cpm.Dep("A", "B", "FS", 0), new Cpm.Dep("B", "M", "FS", 0));
        Cpm.Result r = Cpm.compute(tasks, deps);
        assertThat(r.critical()).containsExactlyInAnyOrder("A", "B", "M");
        assertThat(r.totalFloat().get("C")).isEqualTo(4);
        assertThat(r.projectEnd()).isEqualTo(5);
    }

    @Test
    void 계획시작이_늦은_후행작업은_선행에_여유를_준다() {
        // A(2일) 끝난 뒤 B 는 계획상 5일째 시작 → A 는 여유 3, 임계 경로는 B 뿐
        var tasks = List.of(new Cpm.Task("A", 0, 2), new Cpm.Task("B", 5, 2));
        Cpm.Result r = Cpm.compute(tasks, List.of(new Cpm.Dep("A", "B", "FS", 0)));
        assertThat(r.critical()).containsExactly("B");
        assertThat(r.totalFloat().get("A")).isEqualTo(3);
    }

    @Test
    void SS_FF_지연일() {
        // B 는 A 시작 2일 후 시작(SS+2), C 는 B 와 동시 종료(FF)
        var tasks = List.of(new Cpm.Task("A", 0, 4), new Cpm.Task("B", 0, 4), new Cpm.Task("C", 0, 2));
        Cpm.Result r = Cpm.compute(tasks, List.of(new Cpm.Dep("A", "B", "SS", 2), new Cpm.Dep("B", "C", "FF", 0)));
        assertThat(r.es().get("B")).isEqualTo(2);
        assertThat(r.ef().get("C")).isEqualTo(6);
        assertThat(r.critical()).contains("A", "B", "C");
    }

    @Test
    void 순환이면_예외() {
        assertThatThrownBy(() -> Cpm.checkCycle(List.of("A", "B", "C"), List.of(new Cpm.Dep("A", "B", "FS", 0),
                new Cpm.Dep("B", "C", "FS", 0), new Cpm.Dep("C", "A", "FS", 0))))
                .isInstanceOf(Cpm.CycleException.class);
    }

    @Test
    void 영업일_주말과_공휴일_제외() {
        WorkCalendar.Days cal = new WorkCalendar.Days(Set.of(LocalDate.of(2026, 10, 9)));   // 한글날(금)
        // 2026-10-05(월) ~ 2026-10-11(일): 월~목 4일 (금 공휴일, 토·일 제외)
        assertThat(cal.count(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))).isEqualTo(4);
        assertThat(cal.addWorkdays(LocalDate.of(2026, 10, 8), 2)).isEqualTo(LocalDate.of(2026, 10, 12));
        LocalDate epoch = LocalDate.of(2026, 10, 5);
        assertThat(cal.index(epoch, LocalDate.of(2026, 10, 12))).isEqualTo(4);
        assertThat(cal.dateOf(epoch, 4)).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(cal.index(epoch, LocalDate.of(2026, 10, 10))).isEqualTo(4);   // 토요일 → 다음 영업일(월)
    }
}
