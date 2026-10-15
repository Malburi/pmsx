# oracle 마이그레이션 (Oracle 12c+ · Tibero 공용)

- `V*__*.sql` 은 **생성 파일**이다: `python tools/ddl/convert_ddl.py` 가 `common/` 을 Oracle 문법으로 바꿔 만든다. 직접 고치지 말고 common 또는 생성기를 고친다.
- 타입: VARCHAR(n)→VARCHAR2(n CHAR), TEXT→CLOB, BOOLEAN→NUMBER(1), INTEGER→NUMBER(10), DOUBLE PRECISION→FLOAT(53).
- Oracle 은 빈 문자열을 NULL 로 저장하므로 `DEFAULT ''` 컬럼과 사용자가 비울 수 있는 본문 컬럼은 NOT NULL 을 뺐다(생성기 `ORACLE_NULLABLE`).
- Oracle 은 Flyway, Tibero 는 서버 내장 실행기(`SimpleSqlMigrator`, 이력 테이블 PMSX_SCHEMA_HISTORY)가 실행한다.
- 정적 검사: `VendorDdlCheckTest` (식별자 30자, 예약어, 인덱스 키 6398바이트).
