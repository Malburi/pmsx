# pmsx-server

PMS-X 서버 — Spring Boot 3.3 / Java 21 모듈러 모놀리스. API 계약: [`08`](../docs/08_API명세_R0.md)(뷰어), [`10`](../docs/10_API명세_R0후반_공통.md)(공통), [`11`](../docs/11_API명세_R1_AsIs.md)(R1 As-Is: 편집·메모·진척·테이블/ERD·시스템 뷰·산출물).

## 실행

```bash
./gradlew bootRun          # http://localhost:8080 (local 프로필: H2 파일 DB ./data/db, 파일 저장소 ./data/files)
./gradlew test             # 통합 테스트 (H2 메모리 DB + 내장 PostgreSQL 일부)
./gradlew perfTest         # 성능 측정 (화면 5천·엣지 100만, H2·PostgreSQL) → build/perf/perf-*.md, 약 30분
./gradlew bootJar          # build/libs/pmsx-server.jar  (-PwebDist=<웹 dist> 를 주면 웹 포함 단일 jar)
./gradlew licenseToolJar   # build/libs/pmsx-license-tool.jar
```
설치 패키지(오프라인 zip)는 [`../deploy`](../deploy/README.md) 참고.

- **JDK 설치 불필요**: 시스템 JDK가 11이어도 됩니다. `gradle/gradle-daemon-jvm.properties` + foojay 툴체인 설정으로 Gradle이 JDK 21을 `~/.gradle/jdks` 에 내려받아 데몬·컴파일·실행에 씁니다(최초 1회 인터넷 필요).
- Swagger UI: http://localhost:8080/swagger-ui.html (오른쪽 위 Authorize 에 로그인 응답의 `accessToken` 입력)
- 개발 시드: `admin` / `pmsx1234!` (SYS_ADMIN + 샘플 프로젝트 PM), `analyst` / `pmsx1234!` (ANALYST), 프로젝트 `○○공사 차세대 SI`, 시스템 `HR`(인사시스템)
- 초기화: 서버를 끄고 `data/` 폴더 삭제 → 다시 기동하면 시드가 새로 생성됩니다.

## 프로필·설정

| 프로필 | DB | 비고 |
|---|---|---|
| `local` (기본) | H2 파일 DB, `MODE=PostgreSQL` | 시드 자동 생성 |
| `postgres` | `PMSX_DB_URL` / `PMSX_DB_USER` / `PMSX_DB_PASSWORD` | 시드는 `PMSX_SEED=true` 일 때만, 쿠키 Secure 기본 on |
| `prod` | 설치 폴더 `config/application-prod.yml` (PostgreSQL 기본, H2 예시) | 설치 패키지용. 시드 없음 → 최초 기동 시 admin 자동 생성(`run/initial-admin-password.txt`), JWT 키 자동 생성(`data/jwt-secret.key`), 라이선스 검사, Swagger 끔 |
| `test` | H2 메모리 | 테스트 전용. `PostgresFlowTest`·`AsIsR1PostgresTest` 는 내장 PostgreSQL(zonky, 시스템 설치 없음)로 같은 흐름을 검증 |
| `oracle` / `mssql` | 접속 설정만 | **R1 기준 미지원** — 기동 시 `DbVendorGuard` 가 안내와 함께 중단(`pmsx.db.allow-unsupported=true` 로 우회). 지원 계획은 `db/migration/{oracle,sqlserver}/README.md` |

- **라이선스**(docs/10 §6): `pmsx.license.enforce`(local·test 는 false). 공개키는 `src/main/resources/license/public.key`(개발 키) 또는 `pmsx.license.public-key`. 무효 시 SYS_ADMIN 만 로그인.
- **LDAP**: `pmsx.auth.ldap.*` (기본 꺼짐). AUTH_TYPE=LDAP 으로 등록된 사용자만 LDAP 인증.

환경 변수: `PMSX_JWT_SECRET`(Base64, 32바이트 이상 — **운영 필수 교체**), `PMSX_STORAGE_DIR`, `PMSX_COOKIE_SECURE`.
CORS 허용 출처는 `pmsx.cors.allowed-origins`(기본 `http://localhost:5173`).

## 구조

```
kr.pmsx
├── common      ApiException·전역 오류 핸들러({code,message,fields}), ULID, 보안(JWT 필터), 파일 저장소, OpenAPI
├── cm          인증(로그인·refresh·비밀번호 변경·잠금·LDAP), 사용자·역할 관리, 공통코드·용어, 첨부, 엑셀 엔진, 라이선스·시스템 정보, 시드
├── common      (+ audit: 감사 로그 기록·조회, web: SPA 정적 리소스 fallback)
├── tools       LicenseTool (라이선스 발급 CLI, 단독 jar)
├── pj          프로젝트 등록·수정·멤버, 멤버십 권한(ProjectAccess, PARTNER 열람 범위), R2 권한 집합(Perm), 일련번호(ProjectSeq),
│   │           R2 엑셀 데이터셋(R2Datasets)
│   └── wbs     방법론, WBS 일괄 저장·롤업·CPM(Cpm, 영업일 WorkCalendar)·생성·MS Project XML, 베이스라인, 진척·S-curve, 주간보고
├── rs          인력 풀·스킬, MM 계획, 히트맵, 미배정 작업·배정(경고), 작업일지 제출/승인/반려·MM 실적, 투입 체크리스트
├── dl          산출물 대장·승인(WBS 완료 연동), 요구사항·변경 이력, 추적 링크·매트릭스, 감리 체크리스트, R2 첨부 정책
├── qa          이슈·코멘트(@멘션)·에스컬레이션, 리스크·이슈 전환, 의사결정, CR 전이, 테스트 시나리오/케이스/회차, 결함 전이,
│               품질 리포트, 회의록·액션아이템
├── cm.notify   알림(인앱+메일+웹훅, 사용자 설정), 기한 임박·지연 스케줄러(매일 08:00)
├── cm.dash     프로젝트 대시보드(PM/고객 뷰, SM 위젯), 포트폴리오(health), 통합 검색(SR·KB 포함)
├── sm          SM 운영 (docs/13): SR(전이·코멘트 internal·유사 추천·메일 접수)·고객 포털(/portal/**)·SLA(SlaCalculator 업무시간,
│               SlaEngine 상태 판정, 정책·현황)·장애·문제(후보 묶음)·변경(CAB·캘린더)·정기 작업·운영 자산·KB(2-gram TF-IDF)·
│               월간 운영보고·SI→SM 인수인계(HandoverController)·SM 스케줄러(매분 SLA, 매일 자산 만료)·R3 엑셀·첨부 정책·데모 시드
└── as          As-Is 분석
    ├── SystemController     시스템 목록·등록·조회
    ├── ingest               결과 패키지 업로드: zip 읽기 → JSON Schema 검증 → 키 기준 upsert → diff
    ├── viewer               메뉴 트리, 화면 상세, 캡처 이미지, 워크플로우, 노드, CRUD, 검색, 분석 상태
    ├── edit                 핫스팟 편집(요소 추가·수정·숨김/복원), 수동 캡처, 노드 검색, 연결 추가·확정·기각·삭제
    ├── analysis             메모(Markdown), 분석 이력, 일괄 배정, 진척, 분석 화면 그리드
    ├── table                테이블 정의서·주제영역·ERD·관계 확정/기각/수동 추가
    ├── system               시스템 CRUD 매트릭스·화면 이동 맵·호출 트리·공통 함수 사용처·회차 diff 상세
    ├── impact               영향도 분석(역방향 탐색, 컬럼 단위, 배치 진입점, 최근 1년 SR·결함·담당자) + 엑셀 데이터셋 impact
    ├── deliverable          산출물 출력(화면정의서·프로그램 사양서 xlsx/pptx, 테이블 정의서·CRUD·인터페이스 xlsx, 고객사 템플릿)
    └── UsageIndex           시스템 단위 요소→테이블 CRUD 색인(메모리, 그래프 변경 시 무효화)
```

- 첨부 권한: 대상 유형별 `AttachmentTargetPolicy` 빈 — `AS_SCREEN`, `DL_DELIVERABLE`, `DL_REQUIREMENT`, `QA_ISSUE`, `QA_RISK`,
  `QA_CR`, `QA_DEFECT`, `QA_MEETING`, `QA_RESULT`(targetId = `{roundId}:{caseId}`). 등록되지 않은 대상 유형은 400.
- R2 데모 데이터: `local` 프로필(`pmsx.seed.demo=true`)에서 시드 프로젝트에 WBS 가 없으면 1회 생성 — 인력 9명(+admin·analyst),
  WBS 32행(진행 중 상태)·베이스라인, MM 계획, 승인된 작업일지, 요구사항·테스트·결함·이슈·리스크·CR·회의록. 다시 만들려면 `data/` 삭제.
- 알림: 웹훅 `pmsx.notification.webhook-url`(JSON `{title, body, link}` POST), 메일은 `spring.mail.*` 설정 시(없으면 로그만).
  기한 알림 cron `pmsx.jobs.due-cron`(기본 `0 0 8 * * *`). 실적 MM 환산 `pmsx.mm.hours-per-month`(기본 168).
- 캡처 이미지 조회는 감사 로그를 남기지 않고, `?download=1` 로 내려받을 때만 DOWNLOAD 기록.

### R3 SM 운영 요약
- **SLA 계산**: 기한·남은 시간·상태는 저장하지 않고 매번 계산(`SlaEngine`). 업무시간 `[시작, 종료)`·요일·공휴일(코드 `HOLIDAY`) 또는 24x7, 분 단위(초 버림).
  응답 기한 = 접수 + 응답 목표, 해결 기한 = 접수 + 해결 목표 + 일시정지(보류 PAUSE~RESUME, 해결 후 재요청 STOP~RESUME). DUE_SOON = 남은 시간 ≤ 목표의 20% 또는 30분.
  한 번 위반하면 종료 전까지 BREACHED. 시계 이벤트는 `SM_SLA_EVENT`(START/PAUSE/RESUME/STOP).
- **SR 전이**: 허용 외 전이 409 `CONFLICT`, 반려 사유·배정 담당자 누락 400 `VALIDATION`. 응답 완료 = IN_PROGRESS 전이 또는 운영 측 첫 공개 코멘트.
  PM·PL 전체, OPERATOR 본인 담당(미배정 건을 본인에게 배정 가능), CUSTOMER 는 포털(확인·재요청)만.
- **고객 범위**: 본인 또는 같은 소속사(`CM_USER.COMPANY` = 요청 기관) SR. 내부 코멘트·공수·유사 추천 비공개, 소스 스니펫 비공개.
- **메일 접수**: `POST /api/v1/sm/inbound-mail` + 헤더 `X-PMSX-KEY` = `pmsx.sm.inbound-mail-key`(비면 비활성). `projectCode` = 프로젝트 ID 또는 SM 프로젝트의 시스템 코드. 같은 `messageId` 는 1회만.
- **CAB**: 반대 1건 → REJECTED, PM 승인 1건 또는 승인 `pmsx.sm.cab-approvals-required`(기본 2)건 → CAB_APPROVED. STANDARD·EMERGENCY 는 CAB 없이 배포 가능.
- **알림**: 매분(`pmsx.jobs.sla-cron`) SLA 임박(DUE_SOON)·위반(OVERDUE) 단계별 1회(`SM_SR.NOTIFY_FLAGS`), 매일 08:05(`pmsx.jobs.asset-cron`) 운영 자산 만료 30·7·1일·경과 단계별 1회.
- **SI→SM 인수인계**: 복사본 ID = 인수인계마다 새 10자 접두어 + 원본 ULID 뒤 16자 → 테이블마다 `INSERT … SELECT` 한 문장(연결선 100만 건도 메모리 사용 없음),
  참조 컬럼도 같은 규칙으로 치환, 수집 키 유지, 캡처·첨부 파일은 같은 `CM_FILE` 공유. 멤버 역할 ANALYST·DEV·QA → OPERATOR.
- **R3 데모**(local): SM 프로젝트가 없으면 1회 — `pmsx.sm.demo-asis-package`(기본 `../samples/hr-legacy/out`)의 최신 zip 을 인사시스템에 적재 → 인수인계로
  "○○공사 인사시스템 운영(SM)" 생성 → SR 30(정상·임박·위반·보류·충족·반려), 장애 3, 변경 5, KB 8, 운영 자산 10, 정기 작업 4, 지난달 운영보고, 고객 `customer` / `pmsx1234!`.

- DB 마이그레이션: `src/main/resources/db/migration/{common,h2,postgresql,oracle,sqlserver}` (Flyway, `{vendor}` 위치). `common` = H2·PostgreSQL 공용 문법. 물리 PK는 ULID 대리 키, 수집 키는 `(SYSTEM_ID, *_KEY)` 유니크.
- 결과 패키지 스키마: `src/main/resources/schema/asis-model.schema.json` (= `docs/schema` 복사본. 스키마를 고치면 둘 다 갱신)
- 인증: access token(JWT HS256, 30분, `Authorization: Bearer`) + refresh token(불투명 토큰, httpOnly 쿠키 `PMSX_RT`, Path `/api/v1/auth`, 14일, 사용 시 회전·DB에 SHA-256 해시만 저장).

## 적재 규칙 요약

- 같은 키는 같은 대리 ID 유지(업서트). 요소 배지 번호(`seqNo`)는 최초 등록 순서로 고정.
- `source='manual'`·수정된(`EDITED`) 엣지와 `MANUAL_YN='Y'` 요소는 재수집해도 내용을 덮어쓰지 않음. 숨김(`HIDDEN`)·확정(`CONFIRMED`)·기각(`STATUS`)·관계 판정·테이블 주제영역/수동 코멘트도 유지. 수동 객체는 diff 의 "삭제"로 세지 않음.
- 이번 회차에 없는 객체는 삭제하지 않고 `LAST_RUN_ID` 미갱신 → 회차 diff 계산에 사용. 메뉴 트리는 최신 회차 기준으로 보여줌.
- 워크플로우: 요소의 이벤트 엣지에서 시작해 `calls/request/handles/executes/accesses/navigates` 를 깊이 우선(최대 8)으로 전개, 재방문 노드는 "(위와 동일 흐름)" 표시 후 중단(순환 콜백 포함), 공통 함수(`common=true`)는 전개하지 않음. 기각 엣지는 `edge.status='rejected'` 로 표시만 하고 전개하지 않음. 같은 부모의 자식 순서는 `order` → 모델 내 순서.
- 활성 엣지 = `STATUS='active'` 이면서 최신 회차에 있거나 수동·수정·확정 엣지(이전 회차에만 있던 엣지는 숨김).

## 성능 (NFR-PRF-001·002, `./gradlew perfTest`)

합성 데이터 한 시스템: 화면 5,000 · 요소 200,000 · 노드 약 300,000 · 엣지 약 1,150,000 (요소마다 핸들러 → 함수 20개 순환 체인 → API → 서버 → SQL → 테이블로 일부러 무겁게 구성).
MockMvc 인-프로세스 측정(워밍업 1회 후 중앙값·최대), 2026-10-15, 개발 PC(다른 빌드가 동시에 돌던 상태).

**H2 (MODE=PostgreSQL, 메모리)** — R3 재측정

| 항목 | 중앙값(ms) | 최대(ms) | 목표 |
|---|---|---|---|
| GET /systems/{id}/menu-tree | 151 | 167 | ≤ 2,000 |
| GET /screens/{id} | 78 | 139 | ≤ 2,000 |
| GET /elements/{id}/workflow | 22 | 61 | ≤ 1,000 |
| GET /systems/{id}/search?q=화면 4321 | 637 | 670 | ≤ 1,000 |
| GET /systems/{id}/search?q=fn_ (다수 일치) | 647 | 748 | ≤ 1,000 |
| GET /systems/{id}/analysis/progress | 56 | 84 | - |
| GET /systems/{id}/crud (첫 호출: 색인 구성) | 950 | 950 | - |
| GET /systems/{id}/crud (캐시) | 954 | 960 | - |
| GET /systems/{id}/tables?page=0 | 11 | 17 | - |

데이터: 노드 300,001 · 화면 5,000 · 요소 200,000 · 엣지 1,148,999 · 생성 98초

**PostgreSQL 14 (내장 바이너리)** — R3 재측정

| 항목 | 중앙값(ms) | 최대(ms) | 목표 |
|---|---|---|---|
| GET /systems/{id}/menu-tree | 206 | 252 | ≤ 2,000 |
| GET /screens/{id} | 98 | 115 | ≤ 2,000 |
| GET /elements/{id}/workflow | 63 | 91 | ≤ 1,000 |
| GET /systems/{id}/search?q=화면 4321 | 834 | 905 | ≤ 1,000 |
| GET /systems/{id}/search?q=fn_ (다수 일치) | 849 | 924 | ≤ 1,000 |
| GET /systems/{id}/analysis/progress | 117 | 135 | - |
| GET /systems/{id}/crud (첫 호출: 색인 구성) | 987 | 987 | - |
| GET /systems/{id}/crud (캐시) | 1094 | 1227 | - |
| GET /systems/{id}/tables?page=0 | 19 | 19 | - |

데이터: 노드 300,001 · 화면 5,000 · 요소 200,000 · 엣지 1,148,999 · 생성 235초

- 목표 4개(화면 진입 ≤ 2초, 워크플로우 ≤ 1초, 메뉴 트리 ≤ 2초, 검색 ≤ 1초) 모두 충족. 검색은 `LIKE '%q%'` 전체 스캔이라 데이터가 더 커지면 PostgreSQL `pg_trgm` 인덱스 도입 검토.
- R3 개선: **시스템 CRUD(화면 축)** 약 6초 → 약 1초 — `UsageIndex` 가 색인을 만들 때 화면별 CRUD 합을 함께 만들어 두고, 화면 축 조회는 요소 20만 건을 다시 읽지 않는다(남은 시간은 대부분 5,000행 × 테이블 수백 개 JSON 직렬화). 요소 축(`by=element`)은 기존 방식.
- R3 개선: **진척 집계** 1.7~3.3초 → 0.1초 내외 — 요소 통계(이벤트 핸들러 매칭)·미확정 연결 수를 시스템별로 캐시하고 그래프 변경(`GraphChangedEvent`: 적재·편집) 시 비운다. 캐시가 비어 있는 첫 호출은 이전과 같은 1.7~3.3초.
