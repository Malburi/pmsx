package kr.pmsx.common.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

/**
 * 벤더 SQL 검증용 캡처 (테스트 전용). H2 호환 모드 통합 테스트가 실행한 SQL 을 실 DB 문법으로 번역하고
 * 바인딩 값을 리터럴로 풀어 기록한다 — SQL Server 는 sqlcmd 로 실DB 재생, Oracle·Tibero 는 정적 검사에 쓴다.
 * 문장마다 별도 배치(GO)로 쓰고 앞에 PRINT '#번호' 표식을 둬서 오류를 문장에 대응시킨다.
 * 사용: ./gradlew sqlCapture  →  python tools/db/mssql_replay.py / python tools/db/oracle_sql_check.py
 */
public final class SqlCaptureFile implements DialectDataSource.SqlListener {

    private final Writer out;
    private int seq;

    private SqlCaptureFile(Path file, DbVendor vendor) throws IOException {
        this.real = new SqlTranslator(vendor, vendor == DbVendor.TIBERO, false);
        Files.createDirectories(file.getParent());
        this.out = Files.newBufferedWriter(file, StandardCharsets.UTF_8);
        out.write(0xFEFF);      // BOM (sqlcmd -f 65001)
    }

    /** 시스템 속성 pmsx.sqlCapture(폴더)가 있으면 {vendor}/name.sql 로 캡처를 시작한다 (./gradlew sqlCapture). */
    public static void startIfRequested(String vendor, String name) {
        String dir = System.getProperty("pmsx.sqlCapture");
        if (dir == null || dir.isBlank()) {
            return;
        }
        try {
            DbVendor v = DbVendor.of(vendor, null);
            DialectDataSource.setListener(new SqlCaptureFile(Path.of(dir, vendor, name + ".sql"), v));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 실 DB 기준 번역 (캡처는 H2 호환 모드에서 돌지만 재생·검사는 실 DB 문법으로) */
    private final SqlTranslator real;

    @Override
    public synchronized void executed(String originalSql, Object[] params) {
        try {
            SqlTranslator.Translation t = real.translate(originalSql);
            String sql = t.sql();
            if (t.remapped()) {
                Object[] p = new Object[t.paramMap().length];
                for (int j = 0; j < p.length; j++) {
                    int o = t.paramMap()[j] - 1;
                    p[j] = o < params.length ? params[o] : null;
                }
                params = p;
            }
            seq++;
            out.write("PRINT '#" + seq + "'\nGO\n");
            out.write(SqlTranslator.inline(sql, params, SqlCaptureFile::literal).strip());
            out.write("\nGO\n");
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String literal(Object v) {
        if (v == null) {
            return "NULL";
        }
        if (v instanceof Boolean b) {
            return b ? "1" : "0";
        }
        if (v instanceof Number n) {
            return n instanceof BigDecimal d ? d.toPlainString() : n.toString();
        }
        if (v instanceof OffsetDateTime t) {
            return "'" + t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "'";
        }
        if (v instanceof LocalDateTime t) {
            return "'" + t.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "'";
        }
        if (v instanceof LocalDate d) {
            return "'" + d + "'";
        }
        if (v instanceof java.sql.Timestamp t) {
            return "'" + t.toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "'";
        }
        if (v instanceof java.sql.Date d) {
            return "'" + d.toLocalDate() + "'";
        }
        if (v instanceof byte[] b) {
            return "0x" + HexFormat.of().formatHex(b);
        }
        return "N'" + v.toString().replace("'", "''") + "'";
    }
}
