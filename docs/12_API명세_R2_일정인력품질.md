# 12. API 명세 — R2 (일정·인력·산출물·품질·공통)

- 문서 버전: v0.1 (2026-10-15). 08·10·11 명세 규칙을 따른다. 권한은 06 매트릭스 기준.
- 관련 요구사항: FR-PJ-001~009, FR-RS-001~009, FR-DL-001~006, FR-QA-001~010, FR-CM-003·007·008·009, FR-AS-027
- 날짜는 `YYYY-MM-DD`, 월은 `YYYY-MM`. 영업일 계산은 주말 + 공통코드 그룹 `HOLIDAY`(code=날짜) 제외.
- 공통 번호 규칙: 프로젝트별 일련번호 `ISS-0001`, `RSK-0001`, `CR-0001`, `DEF-0001`, `REQ-0001`, `TC-0001`, `MTG-0001`

## 1. 프로젝트·방법론 (PJ)

| Method | Path | 요청 → 응답 | 권한 |
|---|---|---|---|
| POST | `/projects` | `{ type, name, customer, startDate, endDate, contractMm, pmUserId, methodologyId? }` → `Project` | SYS_ADMIN, PMO |
| PUT | `/projects/{projectId}` | 같은 형식 → `Project` | SYS_ADMIN, PM |
| GET/POST | `/methodologies` | `Methodology[]` / 생성 | 조회 로그인, 변경 SYS_ADMIN |
| GET/PUT/DELETE | `/methodologies/{methodologyId}` | 트리 통째 저장 | SYS_ADMIN |

```ts
interface Methodology { methodologyId: string; name: string; phases: { name: string; order: number; tasks: { name: string; weight: number; durationDays: number; deliverables: string[] }[] }[] }
```
- 시드: "SI 표준(분석·설계·구현·시험·이행)" 1건, 단계별 대표 태스크·산출물 포함.

## 2. WBS·간트·베이스라인 (FR-PJ-002~008)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET | `/projects/{projectId}/wbs` | → `WbsItem[]` (트리 순서 평탄 목록, `critical`·`baseline*` 계산 포함) |
| PUT | `/projects/{projectId}/wbs` | `WbsItem[]` 일괄 저장(신규는 wbsId 없이, 목록에 없는 항목 삭제). 선후행 순환 시 400 `WBS_CYCLE` → `WbsItem[]` |
| PATCH | `/wbs/{wbsId}` | `{ planStart?, planEnd?, actStart?, actEnd?, progress?, status?, assigneeIds? }` → `WbsItem` (간트 드래그·칸반 이동) |
| POST | `/projects/{projectId}/wbs/generate` | `{ methodologyId, startDate, replace: boolean }` → `WbsItem[]` |
| POST | `/projects/{projectId}/wbs/import-msproject` | multipart `file`(MS Project XML), `replace` → `WbsItem[]` |
| GET/POST | `/projects/{projectId}/baselines` | `{ baselineId, name, createdAt, createdBy }[]` / `{ name }` 로 현재 WBS 스냅샷 (PM) |
| GET | `/baselines/{baselineId}/compare` | → `{ wbsId, wbsNo, name, planStart, planEnd, baseStart, baseEnd, startVarDays, endVarDays }[]` |
| GET | `/projects/{projectId}/progress` | → `ProjectProgress` |

```ts
interface WbsItem {
  wbsId: string; parentId: string | null; wbsNo: string; name: string; order: number
  planStart: string | null; planEnd: string | null; actStart: string | null; actEnd: string | null
  weight: number; progress: number                  // 0~100, 상위 항목은 하위 가중 평균(서버 계산, 수정 불가)
  milestone: boolean; status: 'TODO'|'IN_PROGRESS'|'DONE'|'BLOCKED'
  assignees: { userId: string; name: string }[]
  deliverableId: string | null
  deps: { predId: string; type: 'FS'|'SS'|'FF'|'SF'; lagDays: number }[]
  critical: boolean                                  // CPM 계산 결과(영업일)
  baselineStart: string | null; baselineEnd: string | null   // 최신 베이스라인 기준
}
interface ProjectProgress { asOf: string; planned: number; actual: number; spi: number | null; byPhase: { wbsId: string; name: string; planned: number; actual: number }[]; sCurve: { date: string; planned: number; actual: number | null }[] }   // 계획 진척 = 가중치 × 계획 기간 경과율(영업일)
```
- 엑셀 데이터셋: `wbs`(가져오기·내보내기, params `projectId`).

## 3. 주간보고 (FR-PJ-009)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET | `/projects/{projectId}/weekly-reports` | → `{ reportId, weekStart, status, updatedAt }[]` |
| POST | `/projects/{projectId}/weekly-reports/generate` | `{ weekStart }` (월요일) → `WeeklyReport` (자동 집계 초안, 이미 있으면 409) |
| GET/PUT | `/weekly-reports/{reportId}` | `WeeklyReport` (PUT은 섹션 편집) |
| POST | `/weekly-reports/{reportId}/submit` | → `WeeklyReport` (status=SUBMITTED) |

```ts
interface WeeklyReport {
  reportId: string; projectId: string; weekStart: string; status: 'DRAFT'|'SUBMITTED'
  summary: string                                    // 총평(편집)
  progress: { planned: number; actual: number; prevActual: number }
  thisWeek: { wbsNo: string; name: string; assignees: string; progress: number; done: boolean; note: string }[]
  nextWeek: { wbsNo: string; name: string; assignees: string; planEnd: string; note: string }[]
  issues: { no: string; title: string; status: string; owner: string; dueDate: string | null }[]
  risks: { no: string; title: string; score: number; response: string }[]
  mm: { planned: number; actual: number }            // 해당 주
  analysis: { done: number; total: number } | null   // As-Is 분석 진척(시스템 있으면)
}
```
- 엑셀 데이터셋 `weekly-report`(params `reportId`) — 고객사 템플릿 셀 매핑 지원.

## 4. 인력·공수 (RS)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET | `/resources?q=&skill=&orgType=&grade=&page=&size=` | → `Page<Resource>` |
| PUT | `/resources/{userId}` | `{ grade, company, phone?, skills: { skill: string; level: 1\|2\|3\|4\|5 }[] }` → `Resource` (SYS_ADMIN, PM) |
| GET/PUT | `/projects/{projectId}/mm-plan?from=&to=` | `MmPlanRow[]` (PUT은 일괄 저장, PM) |
| GET | `/resources/heatmap?from=&to=&unit=month\|week&projectId=` | → `HeatmapRow[]` |
| GET | `/projects/{projectId}/work-items/unassigned?type=WBS\|SCREEN` | → `{ type, id, label, path: string, planStart, planEnd }[]` |
| POST | `/assignments` | `{ targetType: 'WBS'\|'SCREEN'\|'SR', targetId, userId, allocPct }` → `Assignment` (응답에 배정 후 가용량 경고 포함) |
| DELETE | `/assignments/{assignmentId}` | 204 |
| GET | `/timesheets?userId=&weekStart=` | → `Timesheet` (없으면 빈 DRAFT) |
| PUT | `/timesheets` | `Timesheet` 저장(DRAFT/REJECTED일 때만) |
| POST | `/timesheets/{timesheetId}/submit` · `/approve` · `/reject` | `{ comment? }` → `Timesheet` (승인·반려는 PM·PL) |
| GET | `/projects/{projectId}/timesheets/pending` | → `Timesheet[]` (제출됨) |
| GET | `/projects/{projectId}/mm-summary?from=&to=` | → `{ userId, name, company, orgType, grade, plan: Record<string,number>, actual: Record<string,number> }[]` |
| GET/PUT | `/projects/{projectId}/members/{userId}/checklist` | `{ item: string; done: boolean; doneAt: string \| null }[]` (기본 항목: 코드 그룹 `ONBOARD_ITEM`) |

```ts
interface Resource { userId: string; name: string; orgType: string; company: string | null; grade: string | null; skills: { skill: string; level: number }[]; currentProjects: { projectId: string; name: string; roleId: string }[] }
interface MmPlanRow { userId: string; name: string; grade: string | null; roleId: string; months: Record<string, number> }   // '2026-11': 1.0
interface HeatmapRow { userId: string; name: string; cells: Record<string, number> }   // 기간 키 → 배정률(%) — month: MM계획 합×100, week: 배정(allocPct)×기간 겹침
interface Assignment { assignmentId: string; targetType: string; targetId: string; userId: string; allocPct: number; warning: string | null }   // 예: '2026-W47 배정률 130%'
interface Timesheet { timesheetId: string | null; userId: string; weekStart: string; status: 'DRAFT'|'SUBMITTED'|'APPROVED'|'REJECTED'; comment: string | null; entries: { date: string; targetType: 'WBS'|'SCREEN'|'SR'|'ETC'; targetId: string | null; targetLabel: string; hours: number; note: string | null }[] }
```
- 실적 MM = 승인된 작업시간 합 / `pmsx.mm.hours-per-month`(기본 168). 승인 시 WBS `actStart` 자동 기록.
- 엑셀 데이터셋: `mm-plan`(가져오기·내보내기), `mm-settlement`(협력사 정산, params `projectId`,`month`).

## 5. 산출물·요구사항·추적성 (DL)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET/POST | `/projects/{projectId}/deliverables` | `?phase=&status=` → `Deliverable[]` / 생성 |
| PUT/DELETE | `/deliverables/{deliverableId}` | |
| POST | `/deliverables/{id}/request-review` · `/approve` · `/reject` | `{ comment? }` → `Deliverable`. 승인 시 연결 WBS progress=100·DONE |
| GET | `/deliverables/{id}/history` | → `{ at, user, action, comment }[]` |
| GET/POST | `/projects/{projectId}/requirements` | `?q=&status=&page=&size=` → `Page<Requirement>` / 생성 |
| GET/PUT/DELETE | `/requirements/{reqId}` | 변경 시 이력 자동 기록 |
| GET | `/requirements/{reqId}/history` | → `{ at, user, field, before, after }[]` |
| GET | `/trace-links?type=&id=` | → `TraceLink[]` (양방향, 라벨 포함) |
| POST / DELETE | `/trace-links`, `/trace-links/{linkId}` | `{ fromType, fromId, toType, toId, linkType? }` |
| GET | `/projects/{projectId}/trace-matrix` | → `TraceRow[]` |
| GET/PUT | `/projects/{projectId}/audit-checklist` | `{ itemId, phase, item, deliverableIds: string[], status: 'READY'\|'NOT_READY'\|'NA', note }[]` (기본 항목: 코드 그룹 `AUDIT_ITEM`) |
| GET | `/elements/{elementId}/links` | → `TraceLink[]` + 해당 요소에 등록된 결함·이슈 (FR-AS-027) |

```ts
interface Deliverable { deliverableId: string; phase: string; name: string; owner: { userId: string; name: string } | null; dueDate: string | null; status: 'DRAFT'|'REVIEW'|'APPROVED'|'REJECTED'; customerApproval: boolean; wbsId: string | null; fileCount: number; updatedAt: string }   // 파일은 첨부(targetType=DL_DELIVERABLE)
interface Requirement { reqId: string; reqNo: string; title: string; description: string; source: string | null; category: string | null; priority: 'H'|'M'|'L'; status: 'PROPOSED'|'ACCEPTED'|'IMPLEMENTED'|'VERIFIED'|'DROPPED'; updatedAt: string }
type TraceType = 'REQ'|'SCREEN'|'ELEMENT'|'NODE'|'TC'|'DEFECT'|'CR'|'SR'|'ISSUE'|'DELIVERABLE'|'WBS'
interface TraceLink { linkId: string; fromType: TraceType; fromId: string; fromLabel: string; toType: TraceType; toId: string; toLabel: string; linkType: string | null }
interface TraceRow { req: Pick<Requirement,'reqId'|'reqNo'|'title'|'status'>; screens: { id: string; label: string }[]; tests: { id: string; label: string; lastResult: string | null }[]; defects: { id: string; label: string; status: string }[]; crs: { id: string; label: string }[]; gaps: ('NO_SCREEN'|'NO_TEST'|'TEST_FAILED'|'OPEN_DEFECT')[] }
```
- 엑셀 데이터셋: `requirements`(가져오기·내보내기), `trace-matrix`(내보내기), `deliverables`(내보내기).

## 6. 품질 (QA)

| Method | Path | 비고 |
|---|---|---|
| GET/POST | `/projects/{projectId}/issues` | `?status=&q=&page=&size=` → `Page<Issue>` |
| GET/PUT/DELETE | `/issues/{issueId}` | |
| POST | `/issues/{issueId}/comments` | `{ content }` → `Comment` |
| POST | `/issues/{issueId}/escalate` | 레벨 +1(최대 2) + 알림 |
| GET/POST, GET/PUT/DELETE | `/projects/{projectId}/risks`, `/risks/{riskId}` | `score = probability × impact` |
| POST | `/risks/{riskId}/to-issue` | → `Issue` (리스크 status=OCCURRED) |
| GET/POST, PUT/DELETE | `/projects/{projectId}/decisions`, `/decisions/{id}` | 의사결정 로그 |
| GET/POST, GET/PUT | `/projects/{projectId}/change-requests`, `/change-requests/{crId}` | |
| POST | `/change-requests/{crId}/transition` | `{ to, comment? }` (APPROVED는 PM 또는 고객) |
| GET/POST | `/projects/{projectId}/test-scenarios` | 시나리오 + 케이스 트리 |
| PUT/DELETE | `/test-scenarios/{id}`, `/test-cases/{id}` | |
| POST | `/test-scenarios/{id}/cases` | 케이스 추가 |
| GET/POST | `/projects/{projectId}/test-rounds` | 회차 |
| GET/PUT | `/test-rounds/{roundId}/results` | `{ caseId, result: 'PASS'\|'FAIL'\|'BLOCK'\|'NOT_RUN', note, defectId? }[]` (증적은 첨부 targetType=QA_RESULT) |
| GET/POST | `/projects/{projectId}/defects` | `?status=&severity=&screenId=&q=` → `Page<Defect>`; POST에 `elementId` 주면 핫스팟 결함(FR-QA-008) |
| GET/PUT | `/defects/{defectId}` | |
| POST | `/defects/{defectId}/transition` | `{ to, comment? }` 허용 전이만(아래) |
| GET | `/projects/{projectId}/quality-report` | → `QualityReport` |
| GET/POST, GET/PUT/DELETE | `/projects/{projectId}/meetings`, `/meetings/{id}` | 회의록 + 액션아이템 |
| GET | `/projects/{projectId}/action-items?open=true` | 미완료 액션아이템 |
| PATCH | `/action-items/{itemId}` | `{ done?, ownerId?, dueDate? }` |

```ts
interface Issue { issueId: string; issueNo: string; title: string; description: string; priority: 'H'|'M'|'L'; status: 'OPEN'|'IN_PROGRESS'|'RESOLVED'|'CLOSED'; owner: {userId:string;name:string} | null; dueDate: string | null; escalation: 0|1|2; elementId: string | null; createdAt: string; updatedAt: string; comments?: Comment[] }
interface Comment { commentId: string; content: string; author: {userId:string;name:string}; createdAt: string }
interface Risk { riskId: string; riskNo: string; title: string; description: string; probability: 1|2|3|4|5; impact: 1|2|3|4|5; score: number; response: string; status: 'IDENTIFIED'|'MITIGATING'|'OCCURRED'|'CLOSED'; owner: {userId:string;name:string} | null; dueDate: string | null }
interface Decision { decisionId: string; title: string; content: string; rationale: string; decidedBy: string; decidedAt: string }
interface ChangeRequest { crId: string; crNo: string; title: string; reason: string; requester: string; impact: { scope: string; mmDelta: number; scheduleDeltaDays: number }; status: 'REQUESTED'|'ANALYZED'|'APPROVED'|'REJECTED'|'APPLIED'; reqIds: string[]; wbsIds: string[]; updatedAt: string }
interface TestScenario { scenarioId: string; level: 'UNIT'|'INTEGRATION'|'ACCEPTANCE'; name: string; cases: TestCase[] }
interface TestCase { caseId: string; tcNo: string; title: string; precondition: string | null; steps: string; expected: string; reqIds: string[]; screenId: string | null; lastResult: string | null }
interface Defect { defectId: string; defectNo: string; title: string; description: string; severity: 'CRITICAL'|'MAJOR'|'MINOR'|'TRIVIAL'; priority: 'H'|'M'|'L'; status: 'NEW'|'ASSIGNED'|'FIXED'|'VERIFIED'|'CLOSED'|'REOPENED'; assignee: {userId:string;name:string} | null; reporter: {userId:string;name:string}; caseId: string | null; screenId: string | null; elementId: string | null; createdAt: string; updatedAt: string }
interface QualityReport { defectTrend: { date: string; opened: number; closed: number; open: number }[]; bySeverity: Record<string, number>; byStatus: Record<string, number>; reopenRate: number; testProgress: { roundId: string; name: string; total: number; pass: number; fail: number; block: number; notRun: number }[]; topScreens: { screenId: string; name: string; defects: number }[] }
interface Meeting { meetingId: string; meetingNo: string; title: string; at: string; attendees: string; content: string; actionItems: { itemId: string; content: string; owner: {userId:string;name:string} | null; dueDate: string | null; done: boolean }[] }
```
- 결함 전이: NEW→ASSIGNED→FIXED→VERIFIED→CLOSED, FIXED→REOPENED, VERIFIED→REOPENED, REOPENED→ASSIGNED.
- 엑셀 데이터셋: `issues`, `risks`, `defects`, `test-cases`(가져오기·내보내기).

## 7. 공통 (CM)

| Method | Path | 요청 → 응답 |
|---|---|---|
| GET | `/notifications?unread=&page=&size=` | → `Page<Notification>` |
| POST | `/notifications/{id}/read` · `/notifications/read-all` | 204 |
| GET/PUT | `/notifications/settings` | `{ email: boolean; webhook: boolean; events: Record<NotifyEvent, boolean> }` |
| GET | `/projects/{projectId}/dashboard?view=PM\|CUSTOMER` | → `ProjectDashboard` |
| GET | `/dashboard/portfolio` | → `PortfolioRow[]` (PMO·SYS_ADMIN, 임원 뷰) |
| GET | `/search?q=&projectId=` | → `{ type: TraceType\|'WBS'\|'MEETING'; id: string; label: string; sub: string; projectId: string }[]` (최대 50) |
| PUT | `/projects/{projectId}/members/{userId}/scope` | `{ systemIds: string[]; menuIds: string[] }` — PARTNER 열람 범위 |

```ts
type NotifyEvent = 'ASSIGNED'|'REVIEW_REQUESTED'|'APPROVED'|'REJECTED'|'DUE_SOON'|'OVERDUE'|'DEFECT_ASSIGNED'|'ISSUE_ESCALATED'|'TIMESHEET_REJECTED'|'MENTION'
interface Notification { notificationId: string; event: NotifyEvent; title: string; body: string; link: string | null; read: boolean; createdAt: string }
interface ProjectDashboard { progress: ProjectProgress; openIssues: number; overdueIssues: number; risksTop: Risk[]; defects: { open: number; critical: number }; mm: { planned: number; actual: number }; deliverables: Record<string, number>; analysis: { done: number; total: number } | null; milestones: { name: string; planEnd: string; status: string }[] }
interface PortfolioRow { projectId: string; name: string; type: string; customer: string; pm: string; planned: number; actual: number; spi: number | null; openIssues: number; criticalDefects: number; health: 'GREEN'|'YELLOW'|'RED' }
```
- 알림 채널: 인앱(항상) + 메일(`spring.mail.*` 설정 시, 없으면 로그만) + 웹훅(`pmsx.notify.webhook-url`, JSON `{ title, body, link }` POST — 사내 메신저/Teams/Slack 호환). 기한 임박·지연은 매일 08:00 스케줄러.
- health: SPI<0.9 또는 치명 결함>0 → RED, SPI<0.95 또는 지연 이슈>0 → YELLOW.

## v0.2 변경 (2026-10-15, 웹 R2 결과)
- `PUT /projects/{id}/wbs`: 신규 항목은 `wbsId: "tmp-…"` 임시 ID, `parentId`·`deps.predId` 가 같은 요청의 임시 ID를 참조 가능 → 서버가 매핑.
- `GET /elements/{id}/links` → `{ links: TraceLink[]; defects: { defectId, defectNo, title, status, severity }[]; issues: { issueId, issueNo, title, status }[] }`
- `Project` 에 `contractMm`, `pmUserId`, `pmName` 추가. `Timesheet` 에 `userName`. 액션아이템 목록 항목에 `meetingId`, `meetingNo`.
- 산출물 이력 action: `CREATE`·`UPDATE`·`REQUEST_REVIEW`·`APPROVE`·`REJECT`.
- `GET /projects/{projectId}/members/{userId}/scope` → `{ systemIds, menuIds }` 추가.
- 미읽음 알림 수 = `GET /notifications?unread=true&size=1` 의 `totalElements` (웹 1분 폴링).
