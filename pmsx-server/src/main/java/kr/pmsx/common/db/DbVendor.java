package kr.pmsx.common.db;

import java.util.Locale;

/** PMS-X 저장소 DB 종류. */
public enum DbVendor {
    H2, POSTGRESQL, ORACLE, TIBERO, MSSQL;

    /** 서버 SQL(PostgreSQL 문법)을 실행 전에 번역해야 하는 DB 인가 */
    public boolean needsTranslation() {
        return this == ORACLE || this == TIBERO || this == MSSQL;
    }

    /** Oracle 계열(Oracle·Tibero): 빈 문자열 = NULL, FROM DUAL, ROWNUM */
    public boolean oracleLike() {
        return this == ORACLE || this == TIBERO;
    }

    /** Flyway 벤더 폴더 이름 (db/migration/{folder}) */
    public String migrationFolder() {
        return switch (this) {
            case H2 -> "h2";
            case POSTGRESQL -> "postgresql";
            case ORACLE, TIBERO -> "oracle";
            case MSSQL -> "sqlserver";
        };
    }

    /** 설정값(auto 제외) 또는 JDBC DatabaseProductName 으로 판별 */
    public static DbVendor of(String configured, String productName) {
        String c = configured == null ? "" : configured.trim().toLowerCase(Locale.ROOT);
        switch (c) {
            case "h2": return H2;
            case "postgresql", "postgres", "pg": return POSTGRESQL;
            case "oracle": return ORACLE;
            case "tibero": return TIBERO;
            case "mssql", "sqlserver", "sql-server": return MSSQL;
            default: break;
        }
        String p = productName == null ? "" : productName.toLowerCase(Locale.ROOT);
        if (p.contains("tibero")) {
            return TIBERO;
        }
        if (p.contains("oracle")) {
            return ORACLE;
        }
        if (p.contains("microsoft") || p.contains("sql server")) {
            return MSSQL;
        }
        if (p.contains("postgres")) {
            return POSTGRESQL;
        }
        if (p.contains("h2")) {
            return H2;
        }
        throw new IllegalStateException("지원하지 않는 DB 입니다: " + productName
                + " (지원: PostgreSQL, Oracle, Tibero, SQL Server, H2). pmsx.db.vendor 로 지정할 수 있습니다.");
    }
}
