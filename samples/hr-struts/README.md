# hr-struts — Struts 1 + 자체 프레임워크 가상 샘플

수집기의 `struts1`·`queryxml` 파서와 JSP 확장(include·JSP 변수·상대 URL·폼 action·opener 호출) 검증용.

- `web/WEB-INF/web.xml` : ActionServlet 의 config 목록 (struts-hr.xml 은 여기에만 등록 → web.xml 해석 검증)
- `web/WEB-INF/config/struts-hr.xml` (EUC-KR) : WorkerAction(command=서비스 빈) + DispatchAction
- `web/WEB-INF/config/beans.xml` : 서비스 빈
- `web/WEB-INF/config/query/query-hr.xml` (EUC-KR) : `<query><id>…</id><value>SQL</value></query>`, 동적 블록 `$1{…}`
- `src/` : 서비스(AbstractWorker 상속) — 쿼리 ID 리터럴·지역 변수·StringBuffer·`,1` 접미사, subCall
- `web/WEB-INF/jsp` : JSP(EUC-KR 포함), 공통 include(.jspf), `<%= JS_PATH %>`
- `client/html/js` : 화면 JS (상대 URL 상수 `?method=save`, 폼 action, 팝업, opener 호출)

실행: `python -m pmsx_collector run -c ../samples/hr-struts/collector.yaml --no-capture`
