# 08. API 명세 (R0 PoC 범위)

- 문서 버전: v0.1 (2026-10-14)
- 이 문서는 `pmsx-server` ↔ `pmsx-web` ↔ `pmsx-collector` 간 **계약(contract)** 이다. 변경 시 세 모듈 모두 반영.
- 응답 규칙(회사 `api-standard`): 성공 시 **본문이 곧 payload** (envelope 없음). 오류 시 HTTP 4xx/5xx + `{ "code": string, "message": string, "fields"?: {[name]: string} }`.
- Base: `/api/v1`. 인증: `Authorization: Bearer <accessToken>` (JWT, 30분). Refresh 토큰은 httpOnly 쿠키 `PMSX_RT` (14일, Path=/api/v1/auth).
- 날짜/시간: ISO-8601 문자열 (`2026-10-14T21:30:00+09:00`). ID: 서버 생성 문자열(ULID 26자).
- 개발 포트: server `8080`, web `5173` (Vite proxy `/api` → 8080), 샘플 레거시 mock `8091`.

## 0. 식별자 원칙
수집 결과의 **안정 키**(`scr:HR:HR0101`, `el:HR:HR0101#btnSave`, `fn:js:/js/hr/HR0101.js#fn_save` …)는 시스템 안에서만 유일하다. 그래서 서버는 모든 엔터티에 **대리 ID(ULID)** 를 부여하고 `(SYSTEM_ID, KEY)` 를 유니크로 둔다. URL 경로에는 대리 ID만 쓴다. 재수집 시 같은 키 → 같은 대리 ID 유지(upsert).

## 1. 인증 (cm)

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| POST | `/auth/login` | `{ loginId, password }` | `{ accessToken, user: User }` + Set-Cookie `PMSX_RT` |
| POST | `/auth/refresh` | (쿠키) | `{ accessToken }` |
| POST | `/auth/logout` | (쿠키) | 204, 쿠키 삭제 |
| GET | `/auth/me` | | `User` |

```ts
interface User { userId: string; loginId: string; name: string; roles: string[] }   // roles: SYS_ADMIN, PM ...
```
- 개발 시드 계정: `admin` / `pmsx1234!` (SYS_ADMIN), `analyst` / `pmsx1234!` (샘플 프로젝트 ANALYST)
- 오류 코드: `AUTH_INVALID`(401, 아이디/비밀번호 불일치), `AUTH_EXPIRED`(401), `FORBIDDEN`(403)

## 2. 프로젝트·시스템

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| GET | `/projects` | | `Project[]` (내가 멤버인 프로젝트, SYS_ADMIN은 전체) |
| GET | `/projects/{projectId}/systems` | | `SystemSummary[]` |
| POST | `/projects/{projectId}/systems` | `SystemCreate` | `SystemSummary` |
| GET | `/systems/{systemId}` | | `SystemSummary` |

```ts
interface Project { projectId: string; type: 'SI' | 'SM'; name: string; customer: string; startDate: string; endDate: string }
interface SystemCreate { code: string; name: string; uiTech: string[]; serverTech: string[]; dbType: string; testUrl?: string }
interface SystemSummary extends SystemCreate {
  systemId: string; projectId: string
  screenCount: number; doneCount: number            // 분석 확정 화면 수
  lastRun: { runId: string; seq: number; collectedAt: string } | null
}
```
- 시드: 프로젝트 `○○공사 차세대 SI`(SI) 1건.

## 3. 수집 결과 업로드 (as)

| Method | Path | 요청 | 응답 |
|---|---|---|---|
| POST | `/systems/{systemId}/runs` | `multipart/form-data`, 필드 `file` = 결과 패키지 zip | `RunSummary` (201) |
| GET | `/systems/{systemId}/runs` | | `RunSummary[]` (최신순) |
| GET | `/runs/{runId}/report` | | `text/html` (패키지의 report.html). 인증 필요 + `CSP: sandbox allow-scripts` → web은 캡처 이미지처럼 blob으로 받아 새 창/iframe에 표시 |

**결과 패키지(zip) 구조** — collector가 생성
```
model.json          docs/schema/asis-model.schema.json 을 통과해야 함
captures/*.png      model.screens[].captures[].file 이 가리키는 파일
report.html         실행 리포트
```

```ts
interface RunSummary {
  runId: string; systemId: string; seq: number; collectedAt: string; uploadedAt: string
  stats: Record<string, number>                     // model.manifest.stats 그대로
  diff: { screensAdded: number; screensRemoved: number; elementsAdded: number; elementsRemoved: number; edgesAdded: number; edgesRemoved: number }
}
```
- 검증 실패: 400 `{ code: 'PACKAGE_INVALID', message, fields? }` (스키마 위반 경로를 message에 포함)
- `model.system.code` 가 대상 시스템 `code` 와 다르면 400 `SYSTEM_MISMATCH`
- 적재 규칙: 키 기준 upsert. `source='manual'` 인 엣지·`MANUAL_YN='Y'` 요소는 덮어쓰지 않음. 이번 회차에 없는 객체는 삭제하지 않고 `lastRunId` 미갱신으로 남김.

## 4. As-Is 뷰어 조회 (as)

| Method | Path | 응답 |
|---|---|---|
| GET | `/systems/{systemId}/menu-tree` | `MenuNode[]` |
| GET | `/screens/{screenId}` | `ScreenDetail` |
| GET | `/captures/{captureId}/image` | `image/png` (인증 필요 → web은 blob으로 받아 object URL 사용) |
| GET | `/elements/{elementId}/workflow` | `Workflow` |
| GET | `/nodes/{nodeId}` | `NodeDetail` |
| GET | `/screens/{screenId}/crud` | `CrudMatrix` |
| GET | `/systems/{systemId}/search?q=` | `SearchHit[]` (최대 50) |
| PATCH | `/screens/{screenId}/analysis` | 요청 `{ status?: AnalysisStatus; ownerId?: string \| null }` → `ScreenDetail['analysis']` |

```ts
type AnalysisStatus = 'AUTO' | 'WORKING' | 'REVIEW' | 'DONE'
type ElementType = 'button'|'input'|'combo'|'check'|'radio'|'date'|'grid'|'tab'|'tree'|'popup'|'link'|'file'|'other'
type EdgeSource = 'static' | 'dynamic' | 'manual'

interface MenuNode {
  menuId: string; name: string; order: number
  screen: { screenId: string; screenCode: string; kind: string; status: AnalysisStatus } | null
  children: MenuNode[]                               // 팝업 화면은 호출 화면 노드의 children 으로
}

interface ScreenDetail {
  screenId: string; screenCode: string; name: string; kind: string; uiTech: string; sourceFile: string | null
  menuPath: string[]                                 // ['인사시스템','인사관리','사원관리']
  analysis: { status: AnalysisStatus; owner: { userId: string; name: string } | null; reviewer: { userId: string; name: string } | null; updatedAt: string }
  capture: { captureId: string; width: number; height: number; collectedAt: string; runSeq: number } | null
  elements: ElementSummary[]
}
interface ElementSummary {
  elementId: string; seqNo: number; elementCode: string; label: string; type: ElementType
  box: { x: number; y: number; w: number; h: number } | null
  binding: string | null
  matched: 'full' | 'static-only' | 'dynamic-only'
  danger: boolean
  events: { event: string; handler: string }[]       // handler = 함수명 (예: 'fn_save()')
}

interface Workflow {
  elementId: string
  events: {
    event: string                                    // click, change, onload ...
    steps: WorkflowStep[]                            // 깊이 우선(pre-order) 순서
  }[]
}
interface WorkflowStep {
  nodeId: string | null                              // 첫 단계(UI 이벤트)는 null
  lane: 'UI' | 'FRONT' | 'API' | 'SERVER' | 'SQL' | 'DB' | 'POST'
  title: string; subtitle: string | null
  depth: number                                      // 0부터. 분기 들여쓰기용
  crud: string | null                                // DB 단계: 'R', 'CU' ...
  columns: string[] | null
  hasSource: boolean
  edge: { kind: string; source: EdgeSource; confidence: number; condition: string | null; evidence: string | null } | null   // 직전 단계 → 이 단계 연결
}
```
- lane 결정: `fn`(js/ts/jsp/xfdl) → FRONT, `fn`(java/cs) → SERVER, `api` → API, `sql`/`sp` → SQL, `tbl` → DB, `navigates` 대상 화면 → UI.
- 탐색 깊이 기본 8, 같은 노드 재방문 시 `subtitle`에 "(위와 동일 흐름)" 표시 후 중단. 공통 함수(`common=true`)는 하위를 펼치지 않음.

```ts
interface NodeDetail {
  nodeId: string; nodeType: 'FN' | 'API' | 'SQL' | 'SP' | 'TABLE' | 'SCREEN'; key: string; name: string
  lang: string | null; file: string | null; lineStart: number | null; lineEnd: number | null
  snippet: string | null
  usedBy: { screenId: string; screenName: string; elementId: string; elementLabel: string }[]   // 이 노드에 도달하는 화면/요소 (최대 100)
}

interface CrudMatrix {
  tables: { nodeId: string; name: string }[]
  rows: { elementId: string; seqNo: number; label: string; cells: Record<string, string> }[]   // cells[tableNodeId] = 'CRUD' 부분 문자열
}

interface SearchHit {
  kind: 'SCREEN' | 'ELEMENT' | 'NODE'
  id: string; label: string; sub: string             // sub: 화면 경로나 파일 경로
  screenId: string | null; elementId: string | null
}
```

## 5. 공통 오류 코드

| HTTP | code | 상황 |
|---|---|---|
| 400 | `VALIDATION` | 요청 값 검증 실패 (`fields` 포함) |
| 400 | `PACKAGE_INVALID` / `SYSTEM_MISMATCH` | 업로드 패키지 문제 |
| 401 | `AUTH_INVALID` / `AUTH_EXPIRED` | 인증 |
| 403 | `FORBIDDEN` | 권한 없음 (프로젝트 비멤버 등) |
| 404 | `NOT_FOUND` | 대상 없음 |
| 409 | `CONFLICT` | 중복 (시스템 코드 등) |
| 500 | `INTERNAL` | 서버 오류 (상세는 로그) |

## v1.1 변경 (2026-10-16)
- `SystemCreate` 의 `uiTech`·`serverTech`·`dbType` 은 **선택**. 비워 두면(또는 `dbType: "auto"`) 첫 수집 패키지 업로드 때 `model.json` 의 `system.uiTech`·`serverTech`·`dbType` 으로 채운다. 이미 값이 있으면 덮어쓰지 않는다. → [17. 시스템 등록·기술 자동 감지 가이드](17_시스템등록_기술자동감지_가이드.md)
