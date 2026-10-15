PMS-X 추가 JDBC 드라이버 폴더 (lib/)

PostgreSQL·Oracle·SQL Server·H2 드라이버는 pmsx-server.jar 에 들어 있습니다.
Maven 중앙 저장소에 없는 드라이버(예: Tibero)는 이 폴더에 jar 를 넣으면 bin/start 가 읽습니다(-Dloader.path=lib).

Tibero
  1. Tibero 설치 폴더의 client/lib/jar/tibero7-jdbc.jar (Tibero 6 은 tibero6-jdbc.jar) 를 이 폴더에 복사
  2. config/application-prod.yml 의 datasource 를 Tibero 로 설정
       url: jdbc:tibero:thin:@<host>:8629:<DB명>
       driver-class-name: com.tmax.tibero.jdbc.TbDriver
  3. bin/start 로 기동 (설치가이드 '저장소 DB 별 설정' 참고)
