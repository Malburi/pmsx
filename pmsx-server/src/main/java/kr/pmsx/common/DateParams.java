package kr.pmsx.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 조회 파라미터의 날짜/일시 해석. `YYYY-MM-DD` 와 ISO-8601 일시(오프셋 유무 모두)를 받는다.
 * 날짜만 오면 from 은 그날 00:00, to 는 그날 끝(다음날 00:00 미만)으로 본다.
 */
public final class DateParams {

    private DateParams() {
    }

    /** 구간 시작(포함). null/공백이면 null. */
    public static OffsetDateTime from(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (isDateOnly(v)) {
            return date(name, v).atStartOfDay(Times.ZONE).toOffsetDateTime();
        }
        return dateTime(name, v);
    }

    /** 구간 끝(미포함). 날짜만 오면 다음날 00:00, 일시면 그 시각 + 1ms (그 시각 포함). */
    public static OffsetDateTime toExclusive(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        if (isDateOnly(v)) {
            return date(name, v).plusDays(1).atStartOfDay(Times.ZONE).toOffsetDateTime();
        }
        return dateTime(name, v).plusNanos(1_000_000);
    }

    /** `YYYY-MM-DD` 만 허용 (일시가 오면 날짜 부분 사용). */
    public static LocalDate localDate(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        return isDateOnly(v) ? date(name, v) : dateTime(name, v).atZoneSameInstant(Times.ZONE).toLocalDate();
    }

    private static boolean isDateOnly(String v) {
        return v.length() == 10 && v.charAt(4) == '-' && v.charAt(7) == '-';
    }

    private static LocalDate date(String name, String v) {
        try {
            return LocalDate.parse(v);
        } catch (DateTimeParseException e) {
            throw invalid(name);
        }
    }

    private static OffsetDateTime dateTime(String name, String v) {
        try {
            return OffsetDateTime.parse(v).atZoneSameInstant(Times.ZONE).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(v).atZone(Times.ZONE).toOffsetDateTime();
            } catch (DateTimeParseException e2) {
                throw invalid(name);
            }
        }
    }

    private static ApiException invalid(String name) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION", "입력값을 확인해주세요.",
                Map.of(name, "YYYY-MM-DD 또는 ISO-8601 일시 형식이어야 합니다."));
    }
}
