package kr.pmsx.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqlTranslatorTest {

    private static SqlTranslator.Translation ora(String sql) {
        return new SqlTranslator(DbVendor.ORACLE, false).translate(sql);
    }

    private static SqlTranslator.Translation tib(String sql) {
        return new SqlTranslator(DbVendor.TIBERO, true).translate(sql);
    }

    private static SqlTranslator.Translation ms(String sql) {
        return new SqlTranslator(DbVendor.MSSQL, false).translate(sql);
    }

    @Test
    void postgresAndH2AreUntouched() {
        String sql = "SELECT A || B FROM T WHERE F = TRUE ORDER BY A LIMIT ? OFFSET ?";
        assertThat(new SqlTranslator(DbVendor.POSTGRESQL, false).translate(sql).sql()).isEqualTo(sql);
        assertThat(new SqlTranslator(DbVendor.H2, false).translate(sql).sql()).isEqualTo(sql);
    }

    @Test
    void booleansAndStringsAreRespected() {
        assertThat(ora("SELECT 'TRUE', X FROM T WHERE F = TRUE AND G <> FALSE").sql())
                .isEqualTo("SELECT 'TRUE', X FROM T WHERE F = 1 AND G <> 0");
        assertThat(ms("UPDATE T SET F = TRUE, NAME = '이름 || 그대로' WHERE A.TRUE_YN = 'Y'").sql())
                .isEqualTo("UPDATE T SET F = 1, NAME = N'이름 || 그대로' WHERE A.TRUE_YN = 'Y'");
    }

    @Test
    void pagingWithParamsIsReordered() {
        SqlTranslator.Translation t = ora("SELECT * FROM T WHERE A = ? ORDER BY A LIMIT ? OFFSET ?");
        assertThat(t.sql()).isEqualTo("SELECT * FROM T WHERE A = ? ORDER BY A OFFSET ? ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(t.paramMap()).containsExactly(1, 3, 2);

        SqlTranslator.Translation m = ms("SELECT X FROM T WHERE A = ? LIMIT ?");
        assertThat(m.sql()).isEqualTo("SELECT X FROM T WHERE A = ? ORDER BY (SELECT NULL) OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY");
        assertThat(m.paramMap()).isNull();

        assertThat(ora("SELECT X FROM T ORDER BY X LIMIT 1").sql()).isEqualTo("SELECT X FROM T ORDER BY X FETCH FIRST 1 ROWS ONLY");
        assertThat(ms("SELECT X FROM T ORDER BY X FETCH FIRST 300 ROWS ONLY").sql())
                .isEqualTo("SELECT X FROM T ORDER BY X OFFSET 0 ROWS FETCH NEXT 300 ROWS ONLY");
    }

    @Test
    void legacyRownumPaging() {
        SqlTranslator.Translation t = tib("SELECT * FROM T WHERE A = ? ORDER BY A LIMIT ? OFFSET ?");
        assertThat(t.sql()).isEqualTo("SELECT * FROM (SELECT q__.*, ROWNUM rn__ FROM (SELECT * FROM T WHERE A = ? ORDER BY A)"
                + " q__ WHERE ROWNUM <= ? + ?) WHERE rn__ > ?");
        assertThat(t.paramMap()).containsExactly(1, 3, 2, 3);
        assertThat(tib("SELECT X FROM T ORDER BY X LIMIT 1").sql())
                .isEqualTo("SELECT * FROM (SELECT X FROM T ORDER BY X) WHERE ROWNUM <= 1");
    }

    @Test
    void oracleSpecifics() {
        assertThat(ora("SELECT 1").sql()).isEqualTo("SELECT 1 FROM DUAL");
        assertThat(ora("SELECT (SELECT COUNT(*) FROM T)").sql()).isEqualTo("SELECT (SELECT COUNT(*) FROM T) FROM DUAL");
        assertThat(ora("SELECT CONCAT(CAST(? AS VARCHAR(10)), SUBSTRING(C, 11)) FROM T").sql())
                .isEqualTo("SELECT CONCAT(CAST(? AS VARCHAR2(10)), SUBSTR(C, 11)) FROM T");
        assertThat(ora("WITH RECURSIVE R(N) AS (SELECT 1 FROM DUAL) SELECT N FROM R").sql())
                .isEqualTo("WITH R(N) AS (SELECT 1 FROM DUAL) SELECT N FROM R");
    }

    @Test
    void mssqlSpecifics() {
        assertThat(ms("SELECT A || ' (' || B || ')' FROM T").sql()).isEqualTo("SELECT A + ' (' + B + ')' FROM T");
        assertThat(ms("SELECT X FROM T WHERE LENGTH(C) <> 26 AND SUBSTRING(C, 11) = CHR(1)").sql())
                .isEqualTo("SELECT X FROM T WHERE LEN(C) <> 26 AND SUBSTRING(C, 11, 1000000) = CHAR(1)");
        assertThat(ms("SELECT X FROM T ORDER BY D.DUE_DT NULLS LAST, D.NAME").sql())
                .isEqualTo("SELECT X FROM T ORDER BY CASE WHEN D.DUE_DT IS NULL THEN 1 ELSE 0 END, D.DUE_DT, D.NAME");
        assertThat(ms("SELECT X FROM T ORDER BY DECIDED_AT DESC NULLS LAST, CREATED_AT DESC").sql())
                .isEqualTo("SELECT X FROM T ORDER BY CASE WHEN DECIDED_AT IS NULL THEN 1 ELSE 0 END, DECIDED_AT DESC,"
                        + " CREATED_AT DESC");
        assertThat(ms("SELECT CAST(? AS TIMESTAMP WITH TIME ZONE), CAST(? AS VARCHAR(200)) FROM T").sql())
                .isEqualTo("SELECT CAST(? AS DATETIMEOFFSET), CAST(? AS NVARCHAR(200)) FROM T");
        assertThat(ms("SELECT N'가' FROM T WHERE X = '나'").sql()).isEqualTo("SELECT N'가' FROM T WHERE X = N'나'");
    }

    @Test
    void ddlIsUntouched() {
        String ddl = "CREATE TABLE X (F BOOLEAN DEFAULT TRUE)";
        assertThat(ora(ddl).sql()).isEqualTo(ddl);
    }
}
