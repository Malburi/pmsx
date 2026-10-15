# pmsx-web

PMS-X 웹 클라이언트 — Vue 3 + Vuetify 3 + Vite + TypeScript + Pinia (R0: 로그인, As-Is 화면 분석 뷰어, 시스템 관리, 수집 이력/업로드 / R0 후반: 공통 관리 화면·첨부·엑셀·감사·라이선스)

- API 계약: [`../docs/08_API명세_R0.md`](../docs/08_API명세_R0.md) → `src/types/api.ts`, [`../docs/10_API명세_R0후반_공통.md`](../docs/10_API명세_R0후반_공통.md) → `src/types/common.ts`
- 화면 설계: [`../docs/03_AsIs뷰어_화면설계.md`](../docs/03_AsIs뷰어_화면설계.md), 목업 `../docs/mockup/asis-viewer.html`
- 코딩 규칙: 회사 하네스 `../.skills/rules/*` (vue-sfc-structure, ui-vuetify, api-standard, routing-auth, i18n …)

## 실행

```bash
npm install

# 1) 서버 없이 픽스처로 확인 (mock 모드) — 자동 로그인 상태로 시작
npm run dev:mock          # http://localhost:5173

# 2) 실제 서버 연동 — pmsx-server(localhost:8080) 를 먼저 띄운다. /api 는 Vite 프록시
npm run dev

npm run lint              # ESLint (flat config: Vue 3 + TS 권장 규칙)
npm run build             # 타입체크(vue-tsc) + 운영 빌드 → dist/ (서버 jar 의 정적 리소스로 포함됨, base '/')
npm run test              # Vitest 단위·컴포넌트 테스트
```

- 로그인 계정(명세 시드와 동일): `admin` / `pmsx1234!`, `analyst` / `pmsx1234!`
- mock 모드에서 로그아웃하면 그 탭에서는 로그인 화면이 다시 나온다(세션 단위).
- mock 샘플: 인사시스템(HR) › 사원관리(HR0101) 만 캡처·워크플로우가 있다. 바로가기: `/asis/viewer/sys-hr/scr-hr0101?el=el-hr0101-btnSave`
- mock 시나리오: 아이디 `locked01` 로그인 → 계정 잠김(AUTH_LOCKED). 개발자 도구에서 `sessionStorage.setItem('pmsx-mock-license-invalid','1')` 후 새로고침 → 라이선스 무효 배너, admin 외 로그인 거부.

## 환경 변수

| 변수 | 설명 |
|---|---|
| `VITE_API_URL` | API 기준 URL. 비우면 같은 오리진(`/api/...`) |
| `VITE_USE_MOCK` | `true` 이면 `src/mocks` 의 axios adapter 로 응답 (운영 빌드에서는 제거됨) |

`.env.development`(실서버), `.env.mock`(mock), `.env.production` 참고. secret 은 넣지 않는다.

## 구조

```
src/
├── @layouts/           default(GNB+LNB), blank(로그인·오류)
├── pages/              파일 기반 라우팅 (unplugin-vue-router)
│   ├── login.vue, error.vue, index.vue
│   ├── admin/          SCR-CM-002 projects · 003 users · 005 codes(공통코드·용어) · 006 excel-templates · 009 audit-logs · 010 system
│   └── asis/
│       ├── systems.vue                     SCR-AS-003 시스템 관리
│       ├── runs.vue                        SCR-AS-004 수집 이력/업로드
│       └── viewer/[systemId]/[[screenId]].vue   SCR-AS-001 화면 분석 뷰어 (?el=요소ID 딥링크)
├── components/asis/    메뉴 트리, 캡처+핫스팟, 요소 패널, 워크플로우, 소스 스니펫, CRUD 표
├── components/common/  AttachmentPanel(업로드 큐·버전·미리보기), ExcelExportButton(템플릿 선택), ExcelImportDialog(검증→반영),
│                       ConfirmDialog, ChangePasswordDialog, AppNavList(역할별 메뉴)
├── components/admin/   CodeGroupEditor(그룹/코드 2단 일괄 저장), TermManager
├── composables/        useApi, useBlobUrl, useCodes(공통코드 캐시: /codes?groups=)
├── api/                *Api 함수 (도메인별)
├── utils/axios.ts      단일 Axios 인스턴스 (토큰 첨부, .data 언랩, 401 refresh 1회, 5xx 토스트)
├── stores/             auth(accessToken 은 메모리만), project, ui(테마)
├── mocks/              mock adapter + 픽스처 (HR0101)
├── plugins/            vuetify, i18n, pinia, iconify(오프라인 아이콘)
├── locales/ko.json     모든 화면 문구 (i18n 키)
└── themeConfig.ts      라이트/다크 테마 + 요소 타입·레이어·CRUD·상태 색
```

## 폐쇄망 전제

- 외부 CDN·웹폰트 없음. 폰트는 OS 한글 폰트(맑은 고딕 등) 사용.
- 아이콘: Remix Icon(`@iconify-json/ri`)에서 **사용하는 아이콘만** `scripts/build-icons.mjs` 가 `src/plugins/iconify/ri-subset.json` 으로 추려 번들에 넣는다 (`predev`/`prebuild` 에서 자동 실행). 새 아이콘은 코드에 `'ri-xxx-line'` 으로 쓰면 다음 실행 때 포함된다.
- 수집 리포트(report.html)는 외부 입력이므로 `sandbox="allow-scripts"` iframe(오리진 분리)으로만 표시한다.

## R1 화면 (docs/11)

| 화면 | 경로 | 비고 |
|---|---|---|
| 화면 분석 뷰어 (편집) | `/asis/viewer/:systemId/:screenId?el=` | 헤더 [편집 모드] — 빈 곳 드래그=새 요소, 끌기=이동, 핸들=크기, 방향키 1px(Shift 10px). 워크플로우 연결 ⋮ 메뉴로 확정/기각/조건·CRUD/삭제, [여기서 연결 추가]. 메모(Markdown, VNode 렌더 — v-html 미사용), 분석 이력, 호출 트리·사용처 |
| 분석 진척 현황 | `/asis/progress` | ECharts(필요 차트만 등록), 차트 클릭 → 그리드 필터, 일괄 담당 배정(`/users/lookup`) |
| 테이블 정의서 | `/asis/tables` | 주제영역·코멘트 편집, 관계 확정/기각, 엑셀 = 산출물 `table-spec` |
| ERD | `/asis/erd` | Vue Flow + elkjs(웹 워커). FK·확정 실선 / 추정 점선 / 수동 보라 / 기각 흐린 점선, 관계 추가, PNG 저장 |
| CRUD 매트릭스 | `/asis/crud` | 화면/요소 축, 가상 스크롤(VDataTableVirtual) |
| 화면 이동 맵 | `/asis/navmap` | 노드 클릭 → 뷰어 |
| 산출물 출력 | `/asis/export` | 종류·형식·대상 화면·고객사 템플릿(datasetId = 산출물 type) |
| 수집 이력 | `/asis/runs` | [변경 상세] — 회차 diff·통계(screensWireframe·warnings) |

번들(gzip): ECharts 는 진척 화면 청크(약 185KB)에만, Vue Flow 는 ERD·이동 맵 공용 청크(약 77KB), elk 워커(1.6MB)는 배치할 때만 내려받는다.

## R2 화면 (docs/12)

| 화면 | 경로 | 비고 |
|---|---|---|
| 홈 대시보드 (SCR-CM-001) | `/home` | PM/고객 뷰 + PMO·SYS_ADMIN 포트폴리오. 로그인 후 첫 화면 |
| WBS/간트 (SCR-PJ-001) | `/schedule/wbs` | 계층 그리드 인라인 편집 + SVG 간트(`components/schedule/WbsGantt.vue`, 외부 라이브러리 없음) |
| 칸반 · 베이스라인 · 주간보고 | `/schedule/kanban` · `/schedule/baseline` · `/schedule/weekly` | |
| 인력 풀 · 투입계획/히트맵 · 작업배분 · 작업일지 · MM | `/resource/*` | 역할별 노출 (docs/06) |
| 산출물 · 요구사항 · 추적성 · 감리 | `/deliverable/*` | 첨부 targetType = `DL_DELIVERABLE` |
| 이슈 · 리스크 · CR · 테스트 · 결함 · 회의록 | `/quality/*` | 결함은 허용 전이만 버튼 노출 (`utils/quality.ts`) |
| 방법론 템플릿 · 알림 설정 | `/admin/methodologies` · `/admin/notifications` | |

- GNB: 통합 검색(`GlobalSearch`), 알림 벨(`NotificationBell`, 1분 주기 미읽음 수).
- 뷰어 "연결 이력" 탭: `GET /elements/{id}/links` + 핫스팟에서 결함·이슈 바로 등록 (FR-AS-027, FR-QA-008).
- R2 문구는 `src/locales/ko.r2.json` 에 두고 `plugins/i18n.ts` 에서 기본 문구와 깊게 합친다.
- 날짜 계산은 `utils/date.ts`(영업일·ISO 주차), 간트 좌표는 `utils/gantt.ts`, WBS 계층 편집은 `utils/wbs.ts` — 모두 단위 테스트 있음.

## R3 화면 (docs/13)

| 화면 | 경로 | 비고 |
|---|---|---|
| SCR-SM-001 SR 목록/처리 | `/sm/srs?id=` `?sla=DUE_SOON` | SLA 카운트다운(1분 재조회), 허용 전이만 버튼, 내부/공개 코멘트, 대상 화면 → 뷰어 딥링크(`?el=`), 유사 KB·SR, KB 전환, 첨부 `SM_SR` |
| SCR-SM-002 장애 | `/sm/incidents` | 타임라인·RCA·재발방지, 장애보고서 엑셀(`incident-report`) |
| SCR-SM-003 문제 | `/sm/problems` | 후보 묶음 → 문제 등록 |
| SCR-SM-004 변경·배포 | `/sm/changes` | CAB 승인/반려, 전이, 월 캘린더 |
| SCR-SM-005 정기 작업 | `/sm/routine` | 월 달력 체크 |
| SCR-SM-006 운영 자산 | `/sm/assets` | 만료 D-day 강조 |
| SCR-SM-007 KB | `/sm/kb?id=` | Markdown |
| SCR-SM-008 SLA 현황 | `/sm/sla` | 준수율·유형별 차트·임박/위반 |
| SCR-SM-009 월간 운영보고 | `/sm/reports` | 생성·총평 편집·엑셀(`monthly-ops-report`) |
| SCR-CM-007 SLA 정책·업무 캘린더 | `/admin/sla-policies` | 공휴일은 `/admin/codes?group=HOLIDAY` |
| SCR-AS-009 영향도 분석 | `/asis/impact?systemId=&targetId=&column=` | 테이블 정의서·노드 패널에서 진입 |
| SCR-SM-010 고객 SR 포털 | `/portal`, `/portal/srs/:srId` | 별도 레이아웃(`portal`), CUSTOMER 단독 역할은 로그인 후 포털로만 이동 |
| SI→SM 인수인계 | 프로젝트 관리(SI 프로젝트) | 대상 → 범위 → 미리보기 → 실행 마법사 |

- 메뉴는 선택한 프로젝트 유형으로 거른다: SI 는 일정·감리·테스트, SM 은 운영(SM)·SLA 정책.
- mock 계정: `admin`(SYS_ADMIN+PM), `analyst`, `operator`(OPERATOR), `customer`(CUSTOMER, 포털) — 비밀번호 `pmsx1234!`. mock 프로젝트: `prj-si-01`(SI), `prj-sm-01`(SM, 시스템 `sys-hr-op`).
