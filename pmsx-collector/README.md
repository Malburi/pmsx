# pmsx-collector — As-Is 수집기

고객망 안에서 레거시 시스템의 소스·DB 메타·실제 화면을 분석해 **결과 패키지(zip)** 를 만든다.
패키지는 `pmsx-server` 의 `POST /api/v1/systems/{systemId}/runs` 로 업로드한다. (docs/04, docs/08 §3)

```
pmsx-HR-<yyyyMMdd-HHmmss>.zip
├── model.json        docs/schema/asis-model.schema.json (v0.2) 통과
├── captures/*.png    화면 캡처
└── report.html       실행 리포트 (화면별 현황·서버 미연결 버튼과 원인·차단 요청·미매칭·추정 관계·실패·증분 정보)
```

## 현장 투입 절차 (설치 → init → doctor → run → upload)

| 단계 | 명령 | 하는 일 |
|---|---|---|
| 1. 설치 | `pmsx-collector.exe` 반입 (아래 '설치') | Python 없이 실행. 캡처는 고객 PC의 Chrome/Edge 사용 |
| 2. 설정 초안 | `pmsx-collector.exe init D:\src\hr-web --code HR --name 인사시스템` | 소스를 훑어 화면·서버 기술, 소스 폴더, DDL, 메뉴, DB 종류, 공통 함수(gfn_ajax 등)를 추정해 `collector.yaml` 생성. 근거는 파일 주석에. **캡처는 꺼진 상태**로 만든다 |
| 3. 설정 확인 | 편집기로 `collector.yaml` | TODO 확인: 시스템 코드(서버 등록 코드와 같게), `frontApis` 추정값, 캡처를 쓸 거면 `capture.baseUrl`·로그인 스텝·`denyHosts`(운영계)·`allowRequests` |
| 4. 환경 점검 | `pmsx-collector.exe doctor -c collector.yaml` | 설정·소스 경로·출력 폴더 쓰기·디스크·한글 글꼴·브라우저 실행·대상 URL 응답·운영계 차단·DB 드라이버/접속(읽기 전용 질의 1건)·데스크톱 실행 파일. 실패가 있으면 종료코드 1 |
| 5. 수집 | `pmsx-collector.exe run -c collector.yaml` | 결과 패키지 `out\pmsx-HR-*.zip` + `report.html` (화면별 현황·서버까지 이어지지 않은 버튼과 원인 추정) |
| 5'. 재수집 | `pmsx-collector.exe run -c collector.yaml --incremental` | 이전 회차(`out\.state.json`)와 비교 — 바뀐 소스가 없으면 이전 결과 재사용, 있으면 바뀐 화면만 다시 캡처 |
| 6. 반입 | 망분리: zip 을 반출해 서버 '수집 이력/업로드' 화면에서 업로드 / 망 연결: `set PMSX_UPLOAD_PW=…` 후 `pmsx-collector.exe upload -c collector.yaml --server https://pmsx.local --user analyst` | 시스템 코드로 서버의 시스템을 찾아 최신 패키지를 올린다 (비밀번호는 환경변수로만) |

`doctor` 옵션: `--skip browser,network,db` (해당 점검 생략), `--timeout 5` (네트워크·DB 시간 제한, 브라우저는 6배).
`upload` 옵션: `--package x.zip --system HR` (파일 직접 지정), `--project <ID|이름>` (같은 시스템 코드가 여러 프로젝트에 있을 때), `--ca-file` (사내 CA 인증서).

### 증분 수집 (`--incremental`)
- 판단 기준: 소스 루트 아래 파일 SHA-1 (출력·`node_modules`·`bin`·`obj`·`target` 등 제외), 화면별 해시(화면 소스 + 화면 이벤트에서 이어지는 화면 코드 파일 + 정적 요소·연결 조각), 이전 캡처 PNG 해시.
- 설정 파일이나 수집기 버전이 바뀌면 전체 수집. 이전에 실패·부분 실패한 화면은 항상 다시 캡처.
- 소스가 바뀌면 정적 분석은 **전체 다시** 한다 (호출·Mapper·라우트 교차 참조를 맞추기 위해. 샘플 기준 1~3초). 비용이 큰 동적 캡처만 바뀐 화면으로 줄인다.
- 결과 `model.json` 은 항상 전체 화면을 담는다 (재사용한 화면의 동적 관측·차단 기록 포함).
- 웹 캡처는 화면 단위로 건너뛴다. 데스크톱 캡처는 메뉴를 따라 창을 여는 방식이라 바뀐 화면이 하나라도 있으면 전체를 다시 한다.
- 소스는 그대로인데 테스트계 화면만 바뀐 경우(서버 배포 등)는 감지하지 못한다 → 주기적으로 전체 수집 권장.
- 샘플 hr-legacy 측정: 전체 30.3초 → 변경 없음 1.3초 → JS 1개 수정 8.0초(화면 1개만 재캡처).

## 설치

### A. 단일 실행파일 (권장 — 고객 PC에 Python 불필요)
`pmsx-collector.exe` 하나만 반입한다 (약 70MB, Playwright 드라이버·pywinauto 포함, 브라우저 미포함).

```bat
pmsx-collector.exe init <소스 루트>                    :: 설정 초안 생성
pmsx-collector.exe doctor -c collector.yaml            :: 실행 환경 점검
pmsx-collector.exe run -c collector.yaml              :: 정적 분석 + 동적 캡처
pmsx-collector.exe run -c collector.yaml --no-capture :: 정적 분석만
pmsx-collector.exe run -c collector.yaml --incremental :: 바뀐 화면만 재처리
pmsx-collector.exe upload -c collector.yaml --server <URL> --user <ID>   :: 서버 업로드 (선택)
pmsx-collector.exe validate out\HR-...\model.json      :: 스키마 검증
```

### B. Python (개발·리눅스)
```bash
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements-dev.txt     # 개발 (pytest·pyinstaller 포함)
```
폐쇄망: 인터넷 되는 PC에서 `build_wheelhouse.ps1` 로 `wheelhouse/` 를 만들어 반입 →
`python -m pip install --no-index --find-links wheelhouse -r requirements.txt`
(다른 OS용: `-Platform manylinux2014_x86_64 -PythonVersion 3.11`)

### 브라우저 (캡처용) — 번들하지 않음
| 설정 | 설명 |
|---|---|
| `capture.browserChannel` | `chrome`(기본) · `msedge` — 고객 PC에 설치된 브라우저 사용. `playwright install` 불필요 |
| `capture.browserPath` | 포터블 Chrome/Edge/Chromium 실행파일 경로 (지정 시 channel 무시) |
| `capture.deviceScaleFactor` | 캡처 배율(기본 1). 결과 `captures[].scale` 에 기록, `elements.box` 는 CSS 픽셀 |
| `capture.extraEvents` | 클릭 외 이벤트(change·그리드 행 dblclick) 시도 (기본 true) |

## 빌드 (exe)
```powershell
powershell -ExecutionPolicy Bypass -File build.ps1     # → dist\pmsx-collector.exe
```

## 구성

| 모듈 | 역할 |
|---|---|
| `parsers/ddl.py` | DDL 파일 → 테이블·컬럼·PK·FK·코멘트, 프로시저 본문 → 테이블 CRUD (`register_routine` 은 DB 수집과 공용) |
| `dbmeta.py` + `parsers/db_meta.py` | **DB 직접 수집(읽기 전용)**: Oracle·Tibero·PostgreSQL·MSSQL·MySQL/MariaDB 테이블·컬럼·PK·FK·인덱스·코멘트·SP 본문 |
| `parsers/mybatis.py` | MyBatis Mapper / iBatis sqlMap(`dynamic`·`isNotEmpty prepend`) 펼치기 → SQL 분석. 동명 Mapper 는 전체 네임스페이스 키 |
| `parsers/sql_analyzer.py` | sqlglot 테이블·CRUD·컬럼·조인 조건 (방언 지정), 실패 시 정규식 |
| `parsers/spring.py` | URL 매핑, Controller→Service(구현체)→Mapper/DAO. 필드·**생성자 주입**(`final` 필드, `@RequiredArgsConstructor`), import 기반 타입 해석, iBatis/SqlSession **문자열 호출**(`list("ns.id")`, `getSqlMapClientTemplate().queryForList(...)`) |
| `parsers/menu.py` | 메뉴 CSV / INSERT SQL / **DB 조회(`menu.query`, SELECT만)** |
| `parsers/jsp_jquery.py` | JSP 요소, JS 함수(선언·`var f = function`·`const f = () =>`·**객체 메서드 `Obj.fn`**·`this.fn`), 바인딩(`onclick`·`.on/click`·**위임 `$(document).on('click', '#id', fn)`**·`.delegate`), 선택자(**`#id`·`.class`·`[attr=v]`·`tag[attr]`**) |
| `parsers/nexacro.py` | **Nexacro N/14/17** XFDL: 좌표(left/top/width/height·right/bottom·%, `position="absolute …"`) + Div·Tab 오프셋 누적, 컴포넌트 타입, 이벤트 속성 → 스크립트 함수, `transaction()`·래퍼 → API(+콜백), Dataset 바인딩(Grid binddataset·BindItem·innerdataset), `include "Lib::x.xjs"` 공통 스크립트. 런타임 없이 **와이어프레임 PNG** |
| `parsers/spa.py` | **Vue 2/3**(SFC, Options·Composition API)·**React**(JSX/TSX): 라우터(정적·lazy import·children·`<Route>`) → 화면, 템플릿/JSX 요소·이벤트(`@click`·`onClick`·행 이벤트→그리드), import 그래프(이름·default 객체·`* as`) → API 모듈 → axios/fetch(템플릿 리터럴 `${id}`→`{id}`, 상수 결합, `axios.create({baseURL})`), `router.push`·`<router-link>`·`navigate()`, 대화상자 컴포넌트(…Dialog/Modal) → 팝업 화면 |
| `parsers/websquare.py` | **WebSquare 5/SP** 화면 XML: `style="position:absolute;…"` 좌표를 `xf:group`·`w2:tabControl/w2:content` 기준으로 누적, `xf:trigger`·`xf:input`·`xf:select1`·`w2:gridView`(header/gridBody)…, `ev:onclick` → `scwin.fn`, `xf:submission`(action·method·`ev:submitdone`) ← `$p.executeSubmission("sbm")`·설정 `websquare.submissionCallers`, `$p.ajax({action})`, `$p.openPopup`, 공통 `gcm.fn = function`. 와이어프레임 |
| `parsers/xplatform.py` | **XPlatform**(`*.xfdl`, Form 바로 아래 컴포넌트) · **MiPlatform**(`<Window><Form>`, 대문자 속성 `Left/Top/OnClick/BindDataset`, `contents/format` 그리드, `Tab/Contents/TabPage`) 를 Nexacro 모양으로 **정규화** 후 Nexacro 파서 재사용. `Transaction()`·`Confirm()`·`#include` |
| `parsers/dotnet.py` | **C# 색인**(tree-sitter-c-sharp, partial 클래스 병합): 필드·속성·지역변수·매개변수 타입 → 인터페이스 구현체 → 호출 그래프, 생성자 주입. **데이터 접근**: ADO.NET(`new SqlCommand("…")`·`CommandText`·`CommandType.StoredProcedure`), Dapper(`Query/Execute`, `commandType:`), EF(`DbSet<T>` + `[Table]` → C/R/U/D, `Find`+대입+`SaveChanges` → U). SQL 문자열은 리터럴·`@""`·`$""`·`+`·`+=`·`StringBuilder.Append`·`const`·`string.Format` 조립. `MessageBox.Show(…YesNo)` → `confirm(…)` 조건, `new XxxForm().Show/ShowDialog` · `Response.Redirect` · `RedirectToAction` → 화면 이동. MVC 액션 → URL(`[Route]`/`[HttpPost("…")]`/관례 `/{컨트롤러}/{액션}`), `[WebMethod]` |
| `parsers/winforms.py` | **WinForms** `*.Designer.cs` `InitializeComponent()`: 컨트롤·`Location/Size`(GroupBox·Panel·TabPage 오프셋 누적)·`Text`·`Click +=` 핸들러·`Controls.Add` 부모·`MenuStrip`(메뉴 트리 + 메뉴 항목 요소)·`DataGridView` 열 → 화면·요소·이벤트·메뉴·와이어프레임 |
| `parsers/aspnet.py` | **ASP.NET WebForms**: 서버 컨트롤 → 렌더링 HTML 로 변환해 JSP 파서 재사용(+`<%= x.ClientID %>`), `OnClick`·`OnSelectedIndexChanged`… → 코드비하인드(포스트백), `OnClientClick="return confirm(…)"` 조건, `Page_Load`. **MVC**: Razor 전처리(`@Url.Action`·`@Html.TextBoxFor`·`@section`) → JSP 파서, 화면 ID `Controller.Action` |
| `desktop_capture.py` | **데스크톱 캡처**(UI Automation + Win32 메시지): 실행 → 메뉴(UIA Expand/Invoke) → `PrintWindow` 캡처 + AutomationId(=디자이너 필드명) 좌표 → 비위험 버튼 `BM_CLICK` → 메시지 상자 '아니요/취소'로 닫기, 새 폼은 팝업 이동 기록·캡처 후 `WM_CLOSE` → 앱 종료. **마우스·키보드 입력을 쓰지 않아 잠긴 화면·원격 세션에서도 동작**. 숨은 탭 요소는 매칭된 요소로 좌표 선형 보정 |
| `parsers/harness.py` | AX-Harness `_workspace/index/*.json`(call_graph·sql_usage·schema·api_contract) 병합 — `fill`(기본: 우리 노드 사이 빈 곳만) / `union` |
| `parsers/ui_common.py` | 화면 기술 공용 후처리: 메뉴↔화면, 화면 API↔서버 매핑(REST 메서드별, 경로 변수 `{id}`·`:id` 매칭), 팝업 타입 |
| `wireframe.py` | 소스 좌표 → 레거시 스타일 화면 PNG (Pillow, 한글 글꼴 자동 탐색 / `wireframe.font`) |
| `capture.py` | 로그인 → 메뉴 순회 → 스크린샷·요소 좌표 → 비위험 버튼 클릭 + change/dblclick, **변경성 요청 차단**, 상태가 바뀔 때(이동·모달이 덮음)만 재로딩, 화면 이동 클릭은 이동한 화면의 onload 요청을 자기 것으로 기록하지 않음 |
| `merge.py` | 동적↔정적 요소 매칭(id→name→라벨, 행 반복 버튼은 1개로), 관측 요청으로 연결 확인(`source=dynamic`, 값 경로→`{id}` 템플릿), 추정 관계, 통계 |
| `package.py` / `report.py` | zip·리포트(실패 + **경고**: `${}` 치환, 정의 없는 외부 프로시저 호출) |

프로젝트마다 다른 공통 함수(`gfn_ajax`, `gfn_transaction` 등)는 `collector.yaml` 의 `frontApis` / `frontNavigations` 로 알려 준다.

## 화면 기술별 설정 (요약 — 샘플 `samples/*/collector.yaml` 참고)

| 기술 | `system.uiTech` | 소스 | 주요 설정 |
|---|---|---|---|
| JSP + jQuery | `jsp-jquery` | `source.webapp` / `source.jsp` | `frontApis`, `frontNavigations`, `source.commonScripts` |
| Nexacro | `nexacro` | `source.nexacro` (TypeDefinition 위치) | `nexacro.services`(접두어→폴더), `nexacro.serviceUrls`(`svc::`→서버 경로), `nexacro.wireframe`, 메뉴 `menu.nexacroDataset` |
| Vue | `vue` | `source.vue` (프로젝트 루트) | `spa.router`, `spa.alias`, `spa.apiBaseUrl`, `spa.httpClients`, 메뉴 `menu.router: true` |
| React | `react` | `source.react` | `spa.reactRouter` |
| WebSquare | `websquare` | `source.websquare` (화면 XML 루트) | `websquare.urlPrefix`, `websquare.commonScripts`, `websquare.submissionCallers` |
| XPlatform / MiPlatform | `xplatform`, `miplatform` (같은 파서) | `source.xplatform` | `xplatform.services`·`serviceUrls` (TypeDefinition 자동), `frontNavigations: { Dialog: … }` |
| WinForms | `winforms` | `source.dotnet` (C# 루트) | `dotnet.exclude`(glob), `winforms.wireframe`, 데스크톱 캡처 `capture.desktop.*` |
| ASP.NET WebForms / MVC | `aspnet-webforms`, `aspnet-mvc` | `source.webforms`(웹 루트) · `source.mvc`(Views 상위) | `aspnet.views`, `aspnet.webRoot` |
| .NET 서버·데이터 | `serverTech: [dotnet]` | `source.dotnet` | `db.dialect: tsql`(MSSQL), `dotnet.efTables`(엔터티→테이블) |
| harness 병합 | (추가) | — | `harness.workspace`, `harness.root`, `harness.mode: fill\|union` |
| Struts 1 (+ 자체 프레임워크) | `serverTech: [struts1]` | `source.java`, `source.webapp`(WEB-INF 상위), `source.jsp` | `struts.webXml`(ActionServlet config 목록·모듈), `struts.configs`, `struts.beanConfigs`, `struts.workerActions`(type·serviceAttr·methodPrefix — coperframe 류), `struts.serviceCalls`(subCall 류), `struts.queryCalls`(쿼리 호출 메서드·수신 객체) |
| 쿼리 XML (ID→SQL) | (자동: `queryXml` 있으면) | — | `queryXml.files`, `item`/`id`/`sql` 태그, `dynamicBlock`(`$1{…}`) — DDL 없으면 SQL 에서 테이블·컬럼 추정 |

메뉴 소스(`menu.*`)는 하나 선택: `csv` · `sqlFile` · `query`(DB) · `nexacroDataset` · `router`(SPA 라우트 meta: `title`·`screenId`·`menuGroup`·`menuOrder`) · `directory`(메뉴 테이블이 없을 때 화면 폴더 구조, `labels`·`maxDepth`).

레거시 JSP 보조 설정 (Struts·자체 프레임워크 현장): `source.jspIncludes`(정적 include 의 요소·스크립트 합침), `source.jspVars`·`jspVarsByPath`(`<%= JS_PATH %>` 치환), `source.scriptRoots`(정적 리소스가 별도 폴더/서버에 배포된 구조: URL 접두→폴더), `source.keepQueryParams`(`?method=save` 를 엔드포인트로 구분)·`queryParamIgnoreCase`, `source.actionExtensions`, `source.popupPattern`, `source.skipScripts`, `source.skipJsp`, `source.ignoreNavigation`. 소스 인코딩은 UTF-8/EUC-KR 자동 판별.
상대 URL(`?method=save`, `frm.action = SAVE_PATH`)은 그 화면을 forward 하는 Action 기준으로 해석하고(없으면 같은 폴더 화면들의 Action 이 하나뿐일 때 그것), 공유 JS 에서 생긴 서버 매핑 없는 조합은 오탐으로 제외한다. `opener.fn()` 호출은 팝업을 연 화면의 함수로 연결한다.
캡처 보조: `capture.excludeSelectors`(공통 레이아웃 제외), `capture.excludeUrls`(캡처 제외 경로).

## DB 직접 수집 (`db.connection`)
```yaml
db:
  type: oracle                 # oracle | tibero | postgresql | mssql | mysql | mariadb
  schema: HR                   # SQL 의 스키마 미지정 테이블 키 표기
  schemas: [HR]                # 수집 대상 (Oracle OWNER / MySQL DB 이름)
  includeRoutines: true
  connection:
    host: 10.0.0.5
    port: 1521
    service: HRDEV             # Oracle (또는 dsn: host:port/service)
    user: PMSX_RO              # 읽기 전용 계정
    passwordEnv: PMSX_DB_PW    # 비밀번호는 환경변수로만 (설정 파일 평문 금지)
```
| DB | 드라이버 | 비고 |
|---|---|---|
| Oracle | `oracledb` thin | Oracle Client 설치 불필요. `ALL_*` 딕셔너리 |
| Tibero | `pyodbc` + Tibero ODBC | `odbcDriver: "Tibero 7 ODBC Driver"`. Oracle 호환 `ALL_*` 뷰 사용 — 실제 Tibero 환경 검증 필요 |
| PostgreSQL | `psycopg` | `pg_catalog`, 세션 `read_only` |
| MSSQL | `pyodbc` + ODBC Driver 17/18 | `trusted: true`(Windows 인증) 또는 user/passwordEnv, `ApplicationIntent=ReadOnly` |
| MySQL/MariaDB | `PyMySQL` | `information_schema`, `SET SESSION TRANSACTION READ ONLY` |

- 카탈로그 **SELECT 만** 실행, commit 없이 rollback 후 종료. 스키마 이름은 식별자 문자만 허용.
- DDL 파일(`db.ddlFiles`)과 함께 쓰면 같은 테이블은 DB 메타가 덮어쓴다. 접속 실패는 리포트에 기록하고 나머지는 계속 수집.

### 데스크톱 캡처 (`capture.desktop`)
```yaml
capture:
  enabled: true
  desktop:
    exe: src/HrWin/bin/Release/net7.0-windows/HrWin.exe   # 테스트용 실행 파일 (운영 DB 연결 설정 금지)
    args: []
    mainWindowTitle: "인사관리 시스템"                      # 정규식, 없으면 첫 창
    clickWaitMs: 800
    clickButtons: true
    skipLabels: [닫기, 종료, 취소, 선택, 확인]               # 창을 닫는 버튼은 누르지 않음
```
- 메뉴 경로는 정적 분석(MenuStrip)에서 얻는다. 열린 창은 제목(폼 `Text`)으로 화면을 찾는다.
- 좌표: UIA 물리 px ÷ DPI 배율 → `elements.box`(논리 px), `captures[].scale` = DPI/96.
- **DB 를 변경할 수 있는 실행 파일은 쓰지 말 것** — 버튼 클릭은 위험 라벨 제외 규칙만 따르고, 웹처럼 요청을 가로채 막을 수는 없다.

### 정적 렌더링 캡처 (`capture.mode: static-render`) — 테스트 서버 없이 화면 그림
```yaml
capture:
  mode: static-render        # JSP 를 HTML 로 바꿔 로컬 브라우저로 그림 → 스크린샷 + 요소 좌표 (서버 접속 없음)
render:
  parallel: 6                # 동시 렌더 화면 수
  timeoutSec: 10
  imageFormat: jpeg          # jpeg | png
  branchPolicy: rich         # 스크립틀릿 if/else 중 그릴 분기: rich(내용 많은 쪽) | last(else) | first(if)
  repeat: 2                  # c:forEach / logic:iterate 반복 횟수
  placeholders: {}           # <%= expr %>·${expr} 자리표시값 (예: { cmd: "U" }, "re:^title": "제목")
  screens: []                # 일부 화면만 (화면 ID) — 미리보기·시험용
  # scriptRoots: 기본은 source.scriptRoots, staticRoots: 기본은 webapp·source.root
```
- include 인라인, 스크립틀릿·표현식·EL 제거(경로 상수는 `source.jspVars`), `bean:message`(MessageResources 실제 문구)·`html:*`·JSTL 처리
- 정적 분석 요소마다 `data-pmsx` 표식을 넣어 렌더 후 좌표를 정확히 매칭 → `elements.box`, `captures[].state = "rendered"`
- 외부 호스트·XHR/fetch·폼 전송·화면 이동 차단, alert/confirm·window.open 무력화. 원본 폴더에는 쓰지 않음
- 증분 수집(`--incremental`)과 함께 쓰면 바뀐 화면(include 파일 포함)만 다시 그림
- 한계: 서버 데이터가 없으므로 목록·입력값은 비어 있고, ajax 로 그리는 DOM 은 나오지 않음. 실적: 실제 운영 Struts 1 시스템 1,814화면 중 1,744화면(96%) 렌더, 요소 좌표 77%

## 캡처 안전 규칙
1. POST/PUT/PATCH/DELETE 는 `allowRequests` 패턴(조회성 POST)이 아니면 **기록 후 abort** → 서버에 도달하지 않음
2. `dangerLabels` / `dangerIds` 에 걸리는 버튼은 클릭·이벤트 시도하지 않음 (정적 분석으로만 연결)
3. `alert` / `confirm` 은 자동 취소, 새 창은 URL 기록 후 닫기, 다운로드 취소
4. `denyHosts`(운영계) 와 일치하면 캡처 거부
5. 데스크톱: 위험 버튼·창 닫기 버튼 제외, 메시지 상자는 '아니요/취소' 로 응답, 끝나면 앱 프로세스 강제 종료

## 테스트

```bash
.venv/Scripts/python -m pytest            # 전체 (Chrome·로컬 SQLEXPRESS 필요한 테스트는 없으면 skip)
.venv/Scripts/python -m pytest -m "not capture"
```

`pmsx_collector/schema/asis-model.schema.json` 은 `docs/schema` 사본이다. 테스트가 두 파일이 같은지 확인한다.

## 알려진 한계
- JS: 클로저/IIFE 안에서 정의한 함수, `prototype` 메서드, 동적으로 만든 함수 이름(`window['fn_' + x]`)은 인식하지 않음
- 선택자: 마지막 compound 만 매칭(조상 조건 무시), `:eq()` 등 jQuery 의사 선택자 무시
- Spring: 로컬 변수·메서드 반환 타입을 따라가는 호출, XML 빈 설정(`<bean>`) 주입은 미지원
- Tibero 는 Oracle 호환 질의로 구현했으나 실제 Tibero 환경에서 검증하지 않았음
- Nexacro: 런타임 캡처 없음(와이어프레임만), 다른 컴포넌트 기준 좌표(`left="btn_a:10"`)·Layout 다중 해상도는 첫 Layout만, `this.ds.addRow()` 같은 Dataset 조작은 연결로 표시하지 않음
- SPA: 하위(비대화상자) 컴포넌트의 요소는 부모 화면에 펼치지 않음, Vuex/Pinia 액션을 거친 API 호출·props 콜백(`onClick={onClose}`)은 끊김, 동적 import 된 API 모듈 경로를 변수로 만들면 해석 안 됨
- harness 연계: 인덱서 결과의 오탐(메서드명이 어노테이션명으로 잡히는 경우 등)은 `fill` 모드에서 걸러내지만 `union` 은 그대로 들어옴
- WebSquare: `position:absolute` 가 아닌 흐름 레이아웃(table·relative)은 좌표 없음(요소·이벤트는 수집), `$p.createSubmission` 동적 서브미션·`gcm` 래퍼 안에서 만드는 서브미션 id 는 해석 안 함
- MiPlatform/XPlatform: 전용 런타임(ActiveX·XPlatform Runtime) 캡처는 데스크톱 캡처 대상이지만 샘플로 검증하지 않음 — 와이어프레임 사용. Div `Url` 로 다른 폼을 끼워 넣는 경우 펼치지 않음
- .NET: VB.NET 미지원(실패로 기록), 람다 이벤트 핸들러 본문, `dynamic`·리플렉션·DI 컨테이너 설정(Startup/Autofac 등록)으로만 정해지는 구현체, LINQ to SQL·Stored procedure 이름을 변수로 만드는 경우, EF 의 `Include`·탐색 속성으로 생기는 조인 테이블은 추적하지 않음
- WinForms: `Dock`/`Anchor` 로만 배치된 컨트롤(Location 없음)은 정적 좌표 없음(캡처 시 보정), 사용자 정의 컨트롤(UserControl) 내부는 펼치지 않음, 람다 `+= (s, e) => …` 핸들러는 실패로 기록
- 데스크톱 캡처: 클릭은 네이티브 HWND 가 있는 버튼만(`ToolStripButton`·그리드 셀 버튼 제외), 웹처럼 서버 요청을 관측·차단하지 못함 → 동적 근거는 화면 이동(팝업)만
- ASP.NET: `ClientIDMode=Static` 이 아니면 렌더링 id(`ctl00_…`)와 jQuery 선택자가 다를 수 있음, `.ascx` 사용자 컨트롤·마스터 페이지 내용은 펼치지 않음, ASMX/ASHX·Web API 2 특성 라우팅은 기본만
