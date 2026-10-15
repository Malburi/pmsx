# sqlserver 마이그레이션 (SQL Server 2016+)

- `V*__*.sql` 은 **생성 파일**이다: `python tools/ddl/convert_ddl.py` 가 `common/` 을 T-SQL 로 바꿔 만든다. 직접 고치지 말 것.
- 타입: VARCHAR→NVARCHAR, TEXT→NVARCHAR(MAX), BOOLEAN→`${bit}`, TIMESTAMP WITH TIME ZONE→`${tstz}`.
  Flyway placeholder 로 실 SQL Server 는 BIT·DATETIMEOFFSET, H2 MSSQLServer 호환 모드(테스트)는 TINYINT·TIMESTAMP WITH TIME ZONE 이 된다(DbConfig).
- DB 는 대소문자 구분 데이터 정렬(예: Korean_100_CS_AS)로 만들어야 한다.
- 실DB 검증: `./gradlew sqlCapture` → `python tools/db/mssql_replay.py` (sqlcmd 로 테스트 DB 를 만들어 시나리오 SQL 재생 후 삭제).
