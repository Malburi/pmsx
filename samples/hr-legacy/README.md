# 샘플 레거시: 인사시스템(HR)

PMS-X 수집기·뷰어 개발용으로 만든 **전자정부 프레임워크 스타일** 가상 레거시 시스템. 실제 고객사 시스템이 아니다.

| 구분 | 내용 |
|---|---|
| 화면 | `HR0101` 사원관리, `HR0101P01` 사원검색(팝업), `HR0102` 사원상세(팝업, 인라인 스크립트·`$.ajax`), `HR0103` 발령관리, `HR0104` 부서관리(인라인 스크립트), `HR0105` 교육이력관리(객체 모듈·위임 이벤트·iBatis DAO) |
| 화면 기술 | JSP + jQuery 3.7.1 (`vendor/`), 공통 `js/cmm/common.js` (`gfn_ajax`, `gfn_popup`, `gfn_setCombo`, `gfn_bindGrid` …) |
| 서버 | Spring MVC Controller → Service(인터페이스/Impl) → MyBatis Mapper (`@Mapper` eGov) |
| SQL | 동적 SQL(`if`/`where`/`set`/`include`/`foreach`), JOIN·구식 조인, 논리 삭제, 이력 INSERT, `MERGE`, 프로시저 호출 `SP_APPT_CONFIRM` |
| DB | `db/ddl.sql` (Oracle) — **FK 일부러 누락** (`TB_EMP.DEPT_CD`, `TB_APPOINT.*`) → 추정 관계 검증용, `db/procedures.sql`, `db/menu.csv` |

일부러 넣은 분석 난제
- `btnPrint` (인쇄): 핸들러 없는 버튼 → 리포트 "핸들러를 찾지 못한 버튼"
- `btnApplySort` (정렬적용): 확인창 없이 바로 쓰기 요청 → 캡처 시 **요청 가로채기로 차단**
- 메뉴 `근태신청`(HR0201): 메뉴에는 있으나 소스 없음 → 실패 기록
- 바인딩 방식 혼합: `onclick` 속성 / `.on('click', fn)` / `.click(function(){})` / 조건 있는 익명 핸들러 / 그리드 위임 이벤트
- `HR0105` (v0.2 보완 패턴 검증용)
  - JS: 객체 리터럴 모듈 `var HR0105 = { search: function(){}, save(){} }`, `this.search()`, `var fn_reset = function`, `const fn_eduStat = () =>`, `let fn_eduExcel = function`
  - 바인딩: 위임 `$(document).on('click', '#btnEduSearch', HR0105.search)`, class 선택자 `.btn-excel`, 속성 선택자 `button[data-act="reset"]`·`input[name="eduYear"]`, `change`, 그리드 행 `dblclick`
  - 서버: 생성자 주입 Controller, `@RequiredArgsConstructor` + `final` 필드 Service, iBatis DAO(`list("edu.x")`, `getSqlMapClientTemplate().queryForObject(...)`) + MyBatis Mapper 혼용
  - SQL: iBatis sqlMap(`<dynamic prepend="WHERE">`, `<isNotEmpty prepend="AND">`, `#param#`), `TB_EDU_HIST`(FK 없음 → 추정 관계)

## mock 서버 (캡처용 런타임)

```bash
python mock_server.py              # http://127.0.0.1:8091/login.do (아이디·비밀번호 아무 값)
```
- JSP 를 지시자·태그 제거 후 HTML 로 서빙, `*.do` 는 샘플 JSON 응답
- 쓰기 엔드포인트(`save.do`, `delete.do`, `confirm.do`, `sort.do` …)가 호출되면 `writes.log` 에 기록 → 수집기 차단 검증용

## 수집

```bash
cd ../../pmsx-collector
.venv/Scripts/python -m pmsx_collector run -c ../samples/hr-legacy/collector.yaml
```
결과: `samples/hr-legacy/out/pmsx-HR-<시각>.zip`
