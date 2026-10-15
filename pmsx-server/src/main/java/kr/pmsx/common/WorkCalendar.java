package kr.pmsx.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 영업일 계산 (docs/12 머리말): 주말 + 공통코드 그룹 HOLIDAY(code=YYYY-MM-DD, 사용중) 제외.
 * 공휴일은 요청마다 다시 읽는다 (코드 화면에서 바로 반영).
 */
@Component
public class WorkCalendar {

    private final JdbcTemplate jdbc;

    public WorkCalendar(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Days load() {
        Set<LocalDate> holidays = new HashSet<>();
        for (String c : jdbc.queryForList("SELECT CODE FROM CM_CODE WHERE GROUP_CODE = 'HOLIDAY' AND USE_YN = TRUE",
                String.class)) {
            try {
                holidays.add(LocalDate.parse(c.trim()));
            } catch (RuntimeException ignore) {
                // 형식이 아닌 코드는 무시
            }
        }
        return new Days(holidays);
    }

    /** 공휴일 집합을 들고 있는 계산기 (한 요청 안에서 재사용) */
    public static final class Days {

        private final Set<LocalDate> holidays;

        public Days(Set<LocalDate> holidays) {
            this.holidays = holidays;
        }

        public Set<LocalDate> holidays() {
            return holidays;
        }

        public boolean isWorkday(LocalDate d) {
            DayOfWeek w = d.getDayOfWeek();
            return w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && !holidays.contains(d);
        }

        /** d 가 영업일이 아니면 다음 영업일 */
        public LocalDate nextWorkday(LocalDate d) {
            LocalDate x = d;
            while (!isWorkday(x)) {
                x = x.plusDays(1);
            }
            return x;
        }

        /** [from, to] 양끝 포함 영업일 수 (to < from 이면 0) */
        public int count(LocalDate from, LocalDate to) {
            if (from == null || to == null || to.isBefore(from)) {
                return 0;
            }
            int n = 0;
            for (LocalDate x = from; !x.isAfter(to); x = x.plusDays(1)) {
                if (isWorkday(x)) {
                    n++;
                }
            }
            return n;
        }

        /** start 부터 n 영업일째 날짜 (n=1 이면 start 자신이 영업일일 때 start) */
        public LocalDate addWorkdays(LocalDate start, int n) {
            LocalDate x = nextWorkday(start);
            for (int i = 1; i < Math.max(1, n); i++) {
                x = nextWorkday(x.plusDays(1));
            }
            return x;
        }

        /** 기준일(epoch) 이후 d 까지의 영업일 인덱스. d 가 휴일이면 다음 영업일 기준. */
        public int index(LocalDate epoch, LocalDate d) {
            LocalDate t = nextWorkday(d);
            if (t.isBefore(epoch)) {
                return -count(t, epoch.minusDays(1));
            }
            return count(epoch, t) - 1;
        }

        /** index() 의 역함수 */
        public LocalDate dateOf(LocalDate epoch, int idx) {
            LocalDate x = nextWorkday(epoch);
            if (idx >= 0) {
                for (int i = 0; i < idx; i++) {
                    x = nextWorkday(x.plusDays(1));
                }
                return x;
            }
            for (int i = 0; i > idx; i--) {
                x = x.minusDays(1);
                while (!isWorkday(x)) {
                    x = x.minusDays(1);
                }
            }
            return x;
        }
    }
}
