package kr.pmsx.common;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public final class Times {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private Times() {
    }

    public static OffsetDateTime now() {
        return OffsetDateTime.now(ZONE);
    }

    /** DB의 TIMESTAMP WITH TIME ZONE 을 서울 오프셋으로 읽는다. */
    public static OffsetDateTime read(ResultSet rs, String column) throws SQLException {
        OffsetDateTime v = rs.getObject(column, OffsetDateTime.class);
        return v == null ? null : v.atZoneSameInstant(ZONE).toOffsetDateTime();
    }
}
