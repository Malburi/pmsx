package kr.pmsx.common.db;

/**
 * 접속한 저장소 DB 의 방언 정보 (emulated = H2 호환 모드로 다른 벤더를 흉내 내는 테스트).
 * 서버 SQL 은 PostgreSQL 문법으로 쓰고, Oracle·Tibero·SQL Server 는
 * {@link DialectDataSource} 가 실행 직전에 번역한다. 코드에서 벤더 분기가 꼭 필요할 때만 이 빈을 주입해 쓴다.
 */
public record SqlDialect(DbVendor vendor, boolean legacyPaging, boolean emulated) {

    public SqlDialect(DbVendor vendor, boolean legacyPaging) {
        this(vendor, legacyPaging, false);
    }

    /** 빈 문자열을 NULL 로 저장하는 DB(Oracle·Tibero) 인가 */
    public boolean emptyStringIsNull() {
        return vendor.oracleLike();
    }

    public SqlTranslator translator() {
        return new SqlTranslator(vendor, legacyPaging, emulated);
    }
}
