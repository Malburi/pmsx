# 13. API 명세 — R3 (SM 운영 · 영향도 분석 · SI→SM 인수인계)

- 문서 버전: v0.1 (2026-10-15). 08·10·11·12 명세 규칙을 따른다.
- 관련 요구사항: FR-SM-001~013, FR-AS-028, FR-CL-006·012~015
- SLA 시간 계산 단위는 **분**. 업무시간·공휴일은 정책별 캘린더(코드 그룹 `HOLIDAY` 공용).

## 1. SR (FR-SM-001~003)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET/POST | `/projects/{projectId}/srs` | `?status=&type=&priority=&assigneeId=&screenId=&q=&slaState=DUE_SOON\|BREACHED&page=&size=` → `Page<Sr>` / 생성 |
| GET/PUT | `/srs/{srId}` | `Sr` (상세는 comments, history, similar 포함) |
| POST | `/srs/{srId}/transition` | `{ to: SrStatus, comment?, assigneeId? }` → `Sr` |
| POST | `/srs/{srId}/comments` | `{ content, internal: boolean }` (internal은 고객에게 비공개) |
| POST | `/srs/{srId}/to-kb` | → `KbArticle` 초안 |
| POST | `/sm/inbound-mail` | `{ projectCode, from, subject, body, messageId }` → `Sr` (메일 게이트웨이용, API 키 헤더 `X-PMSX-KEY`) |

```ts
type SrType = 'INQUIRY'|'IMPROVE'|'DATA'|'INCIDENT'|'AUTH'
type SrStatus = 'RECEIVED'|'CLASSIFIED'|'ASSIGNED'|'IN_PROGRESS'|'ON_HOLD'|'RESOLVED'|'CONFIRMED'|'CLOSED'|'REJECTED'
interface Sr {
  srId: string; srNo: string; type: SrType; priority: 'P1'|'P2'|'P3'|'P4'; title: string; description: string
  channel: 'PORTAL'|'EMAIL'|'MANUAL'; requester: { name: string; org: string | null; contact: string | null; userId: string | null }
  status: SrStatus; assignee: { userId: string; name: string } | null
  systemId: string | null; screenId: string | null; elementId: string | null; screenName: string | null
  sla: SrSla | null; effortHours: number; createdAt: string; updatedAt: string
  comments?: { commentId: string; content: string; internal: boolean; author: { userId: string; name: string }; createdAt: string }[]
  history?: { at: string; user: string; from: SrStatus | null; to: SrStatus; comment: string | null }[]
  similar?: { kind: 'KB'|'SR'; id: string; no: string; title: string; score: number }[]
}
interface SrSla { policyId: string; policyName: string; responseDue: string; resolveDue: string; respondedAt: string | null; resolvedAt: string | null; pausedMinutes: number; responseBreached: boolean; resolveBreached: boolean; remainingMinutes: number; state: 'OK'|'DUE_SOON'|'BREACHED'|'MET'|'PAUSED' }
```
- 전이: RECEIVED→CLASSIFIED→ASSIGNED→IN_PROGRESS⇄ON_HOLD→RESOLVED→CONFIRMED→CLOSED, RESOLVED→IN_PROGRESS(고객 재요청), 어느 단계든 →REJECTED(사유 필수). 응답 완료 = ASSIGNED 이후 첫 IN_PROGRESS 또는 첫 공개 코멘트.
- SLA: SR 생성 시 정책 매칭(유형·우선순위) → 응답/해결 기한 계산. ON_HOLD 기간은 일시정지. DUE_SOON = 남은 시간 ≤ 정책 시간의 20% 또는 30분.

## 2. 고객 SR 포털 (SCR-SM-010)
CUSTOMER 역할 전용. 본인(같은 고객사) SR만.

| Method | Path |
|---|---|
| GET | `/portal/projects` → 내가 고객으로 참여한 SM 프로젝트 |
| GET/POST | `/portal/projects/{projectId}/srs` (등록 시 type·title·description·화면 선택(메뉴 트리 검색) 가능) |
| GET | `/portal/srs/{srId}` (internal 코멘트 제외) |
| POST | `/portal/srs/{srId}/comments` · `/confirm`(RESOLVED→CONFIRMED) · `/reopen`(RESOLVED→IN_PROGRESS, 사유) |

## 3. SLA 정책·현황 (FR-SM-004·005)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET/POST, PUT/DELETE | `/projects/{projectId}/sla-policies`, `/sla-policies/{id}` | `SlaPolicy` |
| GET | `/projects/{projectId}/sla/status?month=` | → `{ complianceRate: number; responseRate: number; total: number; met: number; breached: number; dueSoon: Sr[]; breachedList: Sr[]; byType: Record<SrType, { total: number; met: number }> }` |

```ts
interface SlaPolicy { policyId: string; name: string; types: SrType[]; priorities: string[]; responseMinutes: number; resolveMinutes: number; businessHours: { start: string; end: string; days: number[] }; twentyFourSeven: boolean; order: number }   // 매칭은 order 오름차순 첫 정책
```
- 시드(SM 프로젝트 생성 시): P1 장애 응답 30분/해결 4시간(24x7), P2 응답 1시간/해결 1영업일, P3 응답 4시간/해결 3영업일, P4 응답 1영업일/해결 5영업일.

## 4. 장애·문제·변경 (FR-SM-006~008)

| Method | Path | 비고 |
|---|---|---|
| GET/POST, GET/PUT | `/projects/{projectId}/incidents`, `/incidents/{id}` | `Incident` |
| POST | `/incidents/{id}/timeline` | `{ at, content }` |
| GET/POST, GET/PUT | `/projects/{projectId}/problems`, `/problems/{id}` | `Problem` |
| GET | `/projects/{projectId}/problems/candidates` | 최근 90일 같은 화면/요소 또는 유사 제목 SR·장애 3건 이상 묶음 |
| GET/POST, GET/PUT | `/projects/{projectId}/changes`, `/changes/{id}` | `Change` |
| POST | `/changes/{id}/cab` | `{ decision: 'APPROVE'\|'REJECT', comment }` (PM·PL·고객) |
| POST | `/changes/{id}/transition` | `{ to, comment? }` |
| GET | `/projects/{projectId}/changes/calendar?from=&to=` | 배포 예정·이력 |

```ts
interface Incident { incidentId: string; incidentNo: string; title: string; grade: 1|2|3|4; status: 'OPEN'|'RECOVERED'|'CLOSED'; startedAt: string; detectedAt: string | null; recoveredAt: string | null; impact: string; systemIds: string[]; timeline: { at: string; content: string }[]; cause: string | null; rca: string | null; prevention: string | null; srIds: string[]; durationMinutes: number | null }
interface Problem { problemId: string; problemNo: string; title: string; rootCause: string | null; workaround: string | null; knownError: boolean; status: 'OPEN'|'ANALYZING'|'RESOLVED'|'CLOSED'; srIds: string[]; incidentIds: string[] }
interface Change { changeId: string; changeNo: string; title: string; type: 'NORMAL'|'STANDARD'|'EMERGENCY'; risk: 'H'|'M'|'L'; plan: string; rollbackPlan: string; scheduledAt: string | null; deployedAt: string | null; status: 'REQUESTED'|'CAB_APPROVED'|'DEPLOYED'|'CLOSED'|'FAILED'|'ROLLED_BACK'|'REJECTED'; srIds: string[]; screenIds: string[]; cab: { userId: string; name: string; decision: string; comment: string | null; at: string }[]; commits: string[] }
```
- 엑셀 데이터셋: `incident-report`(params `incidentId`, 템플릿 매핑), `changes`(내보내기).

## 5. 정기 작업·운영 자산·KB (FR-SM-009~011)

| Method | Path | 비고 |
|---|---|---|
| GET/POST, PUT/DELETE | `/projects/{projectId}/routine-jobs`, `/routine-jobs/{id}` | `{ jobId, name, rule: { freq: 'DAILY'\|'WEEKLY'\|'MONTHLY'; dow?: number[]; day?: number }, owner, checklist: string[] }` |
| GET | `/projects/{projectId}/routine-jobs/occurrences?from=&to=` | `{ jobId, name, date, done, doneBy, note }[]` |
| POST | `/routine-jobs/{id}/check` | `{ date, done, note? }` |
| GET/POST, PUT/DELETE | `/projects/{projectId}/ops-assets`, `/ops-assets/{id}` | `{ assetId, type: 'SERVER'\|'ACCOUNT'\|'CERT'\|'LICENSE'\|'DOMAIN', name, host, owner, expiresAt, note }` — 만료 30·7·1일 전 알림, 목록에 `daysLeft` |
| GET/POST, GET/PUT/DELETE | `/projects/{projectId}/kb`, `/kb/{id}` | `{ kbId, kbNo, title, content(md), tags, systemId, screenId, sourceSrId, updatedAt }` |
| GET | `/projects/{projectId}/kb/similar?text=` | KB + 종료 SR 상위 5 (한글 2-gram TF-IDF) |

## 6. 월간 운영보고 (FR-SM-012)

| Method | Path |
|---|---|
| GET | `/projects/{projectId}/monthly-reports` |
| POST | `/projects/{projectId}/monthly-reports/generate` `{ month }` → `MonthlyReport` |
| GET/PUT | `/monthly-reports/{reportId}` (`summary`·`plan` 편집) |

```ts
interface MonthlyReport { reportId: string; month: string; summary: string; plan: string; sr: { total: number; byType: Record<string, number>; byStatus: Record<string, number>; carriedOver: number }; sla: { complianceRate: number; responseRate: number; breaches: { srNo: string; title: string; reason: string | null }[] }; incidents: { incidentNo: string; title: string; grade: number; durationMinutes: number | null }[]; changes: { changeNo: string; title: string; deployedAt: string | null; status: string }[]; effort: { userId: string; name: string; hours: number; mm: number }[]; topScreens: { screenId: string; name: string; srCount: number }[] }
```
- 엑셀 데이터셋 `monthly-ops-report`(params `reportId`, 템플릿 매핑).

## 7. SI → SM 인수인계 (FR-SM-013)

| Method | Path | 요청 → 응답 | 권한 |
|---|---|---|---|
| POST | `/projects/{siProjectId}/handover/preview` | `HandoverRequest` → `{ systems: { systemId, name, screens, elements, edges }[]; members: number; traceLinks: number; notes: number; deliverables: number }` | PM, SYS_ADMIN |
| POST | `/projects/{siProjectId}/handover` | `HandoverRequest` → `{ smProjectId; copied: Record<string, number> }` | PM, SYS_ADMIN |

```ts
interface HandoverRequest {
  target: { newProject: { name: string; customer: string; startDate: string; endDate: string; pmUserId: string } } | { projectId: string }
  systemIds: string[]; memberUserIds: string[]
  include: { notes: boolean; traceLinks: boolean; deliverables: boolean; openDefectsAsSr: boolean }
}
```
- 시스템 As-Is 자산(화면·요소·노드·엣지·캡처·테이블·관계·분석 상태·메모)은 **복사**(SI 이력 보존). SM 프로젝트 `ORIGIN_PROJECT_ID` 에 SI 프로젝트 기록. 미해결 결함은 옵션에 따라 SR(RECEIVED)로 생성.

## 8. 영향도 분석 (FR-AS-028, SCR-AS-009)

| Method | Path | 응답 |
|---|---|---|
| GET | `/systems/{systemId}/impact?targetId=&column=` | → `ImpactResult` (targetId = TABLE·FN·API·SQL·SP 노드, column은 TABLE일 때 선택) |

```ts
interface ImpactResult {
  target: { nodeId: string; nodeType: string; name: string; column: string | null }
  screens: { screenId: string; screenCode: string; name: string; menuPath: string[]; owner: string | null; elements: { elementId: string; label: string; crud: string | null; path: string[] }[] }[]
  apis: { nodeId: string; name: string }[]
  serverFunctions: { nodeId: string; name: string; file: string | null }[]
  batches: { nodeId: string; name: string; file: string | null }[]      // 화면에서 도달하지 않는 진입점(스케줄러·배치 클래스 등)
  srs: { srId: string; srNo: string; title: string; status: string; createdAt: string }[]          // 영향 화면의 최근 1년 SR
  defects: { defectId: string; defectNo: string; title: string; status: string }[]
  owners: { userId: string; name: string; reason: string }[]         // 화면 분석 담당·최근 SR 처리자
  summary: { screens: number; elements: number; apis: number; srs: number }
}
```
- 컬럼 단위: `accesses` 엣지의 `columns` 에 해당 컬럼이 있는 경로만. 역방향 탐색 깊이 10, 노드 2,000 상한(초과 시 `truncated` 표시는 summary에 포함 가능).

## 9. 수집기 R3 (FR-CL-006·012~015)
- **WebSquare**: 화면 XML(`w2:` 컴포넌트·좌표/스타일, `ev:onclick`), `submission`(ref·action·target), `scwin.*` 스크립트.
- **MiPlatform/XPlatform**: `.xml`/`.xfdl` 화면 정의(좌표·이벤트), `Transaction()` 호출.
- **WinForms**: `*.Designer.cs` (컨트롤 Location/Size/Text, `this.btn.Click += new EventHandler(btn_Click)`), 코드비하인드 → 서비스/DAO → ADO.NET(`SqlCommand` CommandText, `CommandType.StoredProcedure`), Dapper(`Query<T>("sql")`), EF(`DbSet` → 테이블 추정).
- **ASP.NET WebForms**: `.aspx`(`asp:Button OnClick=`), code-behind; **MVC**: Razor(`form action`, `$.ajax`, `@Html.ActionLink`) + Controller 액션.
- **데스크톱 캡처**: Windows UI Automation(pywinauto `uia` 백엔드) — 실행 파일 기동 → 메인폼 메뉴 순회 → 창 캡처 + 컨트롤 좌표(폼 클라이언트 영역 기준). 클릭은 비위험 버튼만, 모달 대화상자 자동 닫기.
- 샘플: `samples/hr-websquare`, `samples/hr-miplatform`, `samples/hr-winforms`(.NET 7 WinForms, 빌드 가능), `samples/hr-aspnet`.

## v0.2 변경 (2026-10-15, 수집기 R3 결과)
- 새 uiTech: `websquare`, `xplatform`, `miplatform`, `winforms`, `aspnet-webforms`, `aspnet-mvc`.
- lane: `cs`/`vb` 코드 단위가 화면 sourceFile(또는 `.Designer.cs`·`.aspx.cs` 코드비하인드)과 같은 파일이면 **FRONT(화면 코드)**. WebForms·WinForms는 요소 → `fn:cs` → (API 없이) 서비스/DAO → SQL.
- 화면 url은 HTTP 경로가 아닐 수 있음(`winforms:HrWin.Forms.EmpForm`, `AT::AT0101.xfdl`). 화면·요소 ID에 점(.) 포함 가능.
- C# SQL 노드 키 `sql:{Class}.{Method}`(복수면 `_2`…), EF 접근은 `-- EntityFramework:` SQL 노드, `fn → sp:` executes 직접 연결.
- 통계 키 추가 `buttonsReachingData`(HTTP API 없이 SQL·테이블까지 도달한 버튼 수).

## v0.3 변경 (2026-10-15, 웹 R3 결과)
- `GET /projects/{id}/problems/candidates` → `{ key, reason: 'SAME_SCREEN'|'SAME_ELEMENT'|'SIMILAR_TITLE', label, count, srs: { srId, srNo, title, createdAt }[], incidents: { incidentId, incidentNo, title }[] }[]`
- `GET /projects/{id}/kb?q=&page=&size=` → `Page<KbArticle>`
- 엑셀 데이터셋 추가: `srs`(projectId), `impact`(systemId, targetId, column)
- 첨부 `SM_SR`: 포털 CUSTOMER(본인 SR)도 업로드·조회. CUSTOMER는 포털 화면 선택용으로 systems·menu-tree·screens·search 읽기 가능(메모·소스 스니펫 제외).
- SR 전이 오류: 허용되지 않은 전이 409 `CONFLICT`, 사유 누락 400 `VALIDATION`.
- `Project.originProjectId` 추가. 포털 `status=IN_PROGRESS` = CLASSIFIED·ASSIGNED·IN_PROGRESS·ON_HOLD 묶음.
- CAB: 승인 `pmsx.sm.cab-approvals-required`(기본 2)건 이상 또는 PM 승인 → CAB_APPROVED, 반려 1건 → REJECTED.
- `Incident`·`Change` 에 `srs: { srId, srNo, title }[]` 추가(srIds 유지).
