// docs/12_API명세_R2_일정인력품질.md 의 타입을 그대로 옮긴 것. 명세가 바뀌면 이 파일을 먼저 고친다.
import type { UserRef } from '@/types/api'

/* ── 1. 프로젝트·방법론 ─────────────────────────────────── */
export interface ProjectPayload {
  type: 'SI' | 'SM'
  name: string
  customer: string
  startDate: string
  endDate: string
  contractMm: number
  pmUserId: string
  methodologyId?: string | null
}

export interface MethodologyTask {
  name: string
  weight: number
  durationDays: number
  deliverables: string[]
}

export interface MethodologyPhase {
  name: string
  order: number
  tasks: MethodologyTask[]
}

export interface Methodology {
  methodologyId: string
  name: string
  phases: MethodologyPhase[]
}

/* ── 2. WBS·간트·베이스라인 ─────────────────────────────── */
export type DepType = 'FS' | 'SS' | 'FF' | 'SF'
export type WbsStatus = 'TODO' | 'IN_PROGRESS' | 'DONE' | 'BLOCKED'

export interface WbsDep {
  predId: string
  type: DepType
  lagDays: number
}

export interface WbsItem {
  wbsId: string
  parentId: string | null
  wbsNo: string
  name: string
  order: number
  planStart: string | null
  planEnd: string | null
  actStart: string | null
  actEnd: string | null
  weight: number
  /** 0~100, 상위 항목은 서버 계산(수정 불가) */
  progress: number
  milestone: boolean
  status: WbsStatus
  assignees: UserRef[]
  deliverableId: string | null
  deps: WbsDep[]
  critical: boolean
  baselineStart: string | null
  baselineEnd: string | null
}

export interface WbsPatch {
  planStart?: string | null
  planEnd?: string | null
  actStart?: string | null
  actEnd?: string | null
  progress?: number
  status?: WbsStatus
  assigneeIds?: string[]
}

export interface Baseline {
  baselineId: string
  name: string
  createdAt: string
  createdBy: string
}

export interface BaselineCompareRow {
  wbsId: string
  wbsNo: string
  name: string
  planStart: string | null
  planEnd: string | null
  baseStart: string | null
  baseEnd: string | null
  startVarDays: number | null
  endVarDays: number | null
}

export interface ProjectProgress {
  asOf: string
  planned: number
  actual: number
  spi: number | null
  byPhase: { wbsId: string; name: string; planned: number; actual: number }[]
  sCurve: { date: string; planned: number; actual: number | null }[]
}

/* ── 3. 주간보고 ─────────────────────────────────────────── */
export interface WeeklyReportSummary {
  reportId: string
  weekStart: string
  status: 'DRAFT' | 'SUBMITTED'
  updatedAt: string
}

export interface WeeklyReport {
  reportId: string
  projectId: string
  weekStart: string
  status: 'DRAFT' | 'SUBMITTED'
  summary: string
  progress: { planned: number; actual: number; prevActual: number }
  thisWeek: { wbsNo: string; name: string; assignees: string; progress: number; done: boolean; note: string }[]
  nextWeek: { wbsNo: string; name: string; assignees: string; planEnd: string; note: string }[]
  issues: { no: string; title: string; status: string; owner: string; dueDate: string | null }[]
  risks: { no: string; title: string; score: number; response: string }[]
  mm: { planned: number; actual: number }
  analysis: { done: number; total: number } | null
}

/* ── 4. 인력·공수 ────────────────────────────────────────── */
export interface Skill {
  skill: string
  level: number
}

export interface Resource {
  userId: string
  name: string
  orgType: string
  company: string | null
  grade: string | null
  skills: Skill[]
  currentProjects: { projectId: string; name: string; roleId: string }[]
}

export interface ResourceQuery {
  q?: string
  skill?: string
  orgType?: string
  grade?: string
  page?: number
  size?: number
}

export interface ResourcePayload {
  grade: string | null
  company: string | null
  phone?: string | null
  skills: Skill[]
}

export interface MmPlanRow {
  userId: string
  name: string
  grade: string | null
  roleId: string
  /** 'YYYY-MM' → MM */
  months: Record<string, number>
}

export interface HeatmapRow {
  userId: string
  name: string
  /** 기간 키 → 배정률(%) */
  cells: Record<string, number>
}

export type WorkTargetType = 'WBS' | 'SCREEN' | 'SR'

export interface WorkItem {
  type: WorkTargetType
  id: string
  label: string
  path: string
  planStart: string | null
  planEnd: string | null
}

export interface Assignment {
  assignmentId: string
  targetType: string
  targetId: string
  userId: string
  allocPct: number
  warning: string | null
}

export type TimesheetStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED'
export type TimesheetTarget = 'WBS' | 'SCREEN' | 'SR' | 'ETC'

export interface TimesheetEntry {
  date: string
  targetType: TimesheetTarget
  targetId: string | null
  targetLabel: string
  hours: number
  note: string | null
}

export interface Timesheet {
  timesheetId: string | null
  userId: string
  /** 목록(승인함) 표시용 — 서버가 주면 사용 */
  userName?: string
  weekStart: string
  status: TimesheetStatus
  comment: string | null
  entries: TimesheetEntry[]
}

export interface MmSummaryRow {
  userId: string
  name: string
  company: string
  orgType: string
  grade: string | null
  plan: Record<string, number>
  actual: Record<string, number>
}

export interface ChecklistItem {
  item: string
  done: boolean
  doneAt: string | null
}

/* ── 5. 산출물·요구사항·추적성 ──────────────────────────── */
export type DeliverableStatus = 'DRAFT' | 'REVIEW' | 'APPROVED' | 'REJECTED'

export interface Deliverable {
  deliverableId: string
  phase: string
  name: string
  owner: UserRef | null
  dueDate: string | null
  status: DeliverableStatus
  customerApproval: boolean
  wbsId: string | null
  fileCount: number
  updatedAt: string
}

export interface DeliverablePayload {
  phase: string
  name: string
  ownerId: string | null
  dueDate: string | null
  customerApproval: boolean
  wbsId: string | null
}

export interface HistoryEntry {
  at: string
  user: string
  action: string
  comment: string | null
}

export type ReqStatus = 'PROPOSED' | 'ACCEPTED' | 'IMPLEMENTED' | 'VERIFIED' | 'DROPPED'
export type Priority = 'H' | 'M' | 'L'

export interface Requirement {
  reqId: string
  reqNo: string
  title: string
  description: string
  source: string | null
  category: string | null
  priority: Priority
  status: ReqStatus
  updatedAt: string
}

export type RequirementPayload = Omit<Requirement, 'reqId' | 'reqNo' | 'updatedAt'>

export interface FieldHistory {
  at: string
  user: string
  field: string
  before: string | null
  after: string | null
}

export type TraceType = 'REQ' | 'SCREEN' | 'ELEMENT' | 'NODE' | 'TC' | 'DEFECT' | 'CR' | 'SR' | 'ISSUE' | 'DELIVERABLE' | 'WBS'

export interface TraceLink {
  linkId: string
  fromType: TraceType
  fromId: string
  fromLabel: string
  toType: TraceType
  toId: string
  toLabel: string
  linkType: string | null
}

export interface TraceLinkPayload {
  fromType: TraceType
  fromId: string
  toType: TraceType
  toId: string
  linkType?: string | null
}

export type TraceGap = 'NO_SCREEN' | 'NO_TEST' | 'TEST_FAILED' | 'OPEN_DEFECT'

export interface TraceRow {
  req: Pick<Requirement, 'reqId' | 'reqNo' | 'title' | 'status'>
  screens: { id: string; label: string }[]
  tests: { id: string; label: string; lastResult: string | null }[]
  defects: { id: string; label: string; status: string }[]
  crs: { id: string; label: string }[]
  gaps: TraceGap[]
}

export interface AuditChecklistItem {
  itemId: string
  phase: string
  item: string
  deliverableIds: string[]
  status: 'READY' | 'NOT_READY' | 'NA'
  note: string
}

/** GET /elements/{id}/links — 추적 링크 + 이 요소에 등록된 결함·이슈 */
export interface ElementLinks {
  links: TraceLink[]
  defects: Pick<Defect, 'defectId' | 'defectNo' | 'title' | 'status' | 'severity'>[]
  issues: Pick<Issue, 'issueId' | 'issueNo' | 'title' | 'status' | 'priority'>[]
}

/* ── 6. 품질 ─────────────────────────────────────────────── */
export type IssueStatus = 'OPEN' | 'IN_PROGRESS' | 'RESOLVED' | 'CLOSED'

export interface Comment {
  commentId: string
  content: string
  author: UserRef
  createdAt: string
}

export interface Issue {
  issueId: string
  issueNo: string
  title: string
  description: string
  priority: Priority
  status: IssueStatus
  owner: UserRef | null
  dueDate: string | null
  escalation: 0 | 1 | 2
  elementId: string | null
  createdAt: string
  updatedAt: string
  comments?: Comment[]
}

export interface IssuePayload {
  title: string
  description: string
  priority: Priority
  status?: IssueStatus
  ownerId: string | null
  dueDate: string | null
  elementId?: string | null
}

export type RiskStatus = 'IDENTIFIED' | 'MITIGATING' | 'OCCURRED' | 'CLOSED'

export interface Risk {
  riskId: string
  riskNo: string
  title: string
  description: string
  probability: 1 | 2 | 3 | 4 | 5
  impact: 1 | 2 | 3 | 4 | 5
  score: number
  response: string
  status: RiskStatus
  owner: UserRef | null
  dueDate: string | null
}

export interface RiskPayload {
  title: string
  description: string
  probability: number
  impact: number
  response: string
  status: RiskStatus
  ownerId: string | null
  dueDate: string | null
}

export interface Decision {
  decisionId: string
  title: string
  content: string
  rationale: string
  decidedBy: string
  decidedAt: string
}

export type CrStatus = 'REQUESTED' | 'ANALYZED' | 'APPROVED' | 'REJECTED' | 'APPLIED'

export interface ChangeRequest {
  crId: string
  crNo: string
  title: string
  reason: string
  requester: string
  impact: { scope: string; mmDelta: number; scheduleDeltaDays: number }
  status: CrStatus
  reqIds: string[]
  wbsIds: string[]
  updatedAt: string
}

export type ChangeRequestPayload = Omit<ChangeRequest, 'crId' | 'crNo' | 'status' | 'updatedAt'>

export type TestLevel = 'UNIT' | 'INTEGRATION' | 'ACCEPTANCE'
export type TestResult = 'PASS' | 'FAIL' | 'BLOCK' | 'NOT_RUN'

export interface TestCase {
  caseId: string
  tcNo: string
  title: string
  precondition: string | null
  steps: string
  expected: string
  reqIds: string[]
  screenId: string | null
  lastResult: string | null
}

export type TestCasePayload = Omit<TestCase, 'caseId' | 'tcNo' | 'lastResult'>

export interface TestScenario {
  scenarioId: string
  level: TestLevel
  name: string
  cases: TestCase[]
}

export interface TestRound {
  roundId: string
  name: string
  level: TestLevel
  createdAt?: string
}

export interface TestResultRow {
  caseId: string
  result: TestResult
  note: string
  defectId?: string | null
}

export type DefectSeverity = 'CRITICAL' | 'MAJOR' | 'MINOR' | 'TRIVIAL'
export type DefectStatus = 'NEW' | 'ASSIGNED' | 'FIXED' | 'VERIFIED' | 'CLOSED' | 'REOPENED'

export interface Defect {
  defectId: string
  defectNo: string
  title: string
  description: string
  severity: DefectSeverity
  priority: Priority
  status: DefectStatus
  assignee: UserRef | null
  reporter: UserRef
  caseId: string | null
  screenId: string | null
  elementId: string | null
  createdAt: string
  updatedAt: string
}

export interface DefectPayload {
  title: string
  description: string
  severity: DefectSeverity
  priority: Priority
  assigneeId: string | null
  caseId?: string | null
  screenId?: string | null
  elementId?: string | null
}

export interface QualityReport {
  defectTrend: { date: string; opened: number; closed: number; open: number }[]
  bySeverity: Record<string, number>
  byStatus: Record<string, number>
  reopenRate: number
  testProgress: { roundId: string; name: string; total: number; pass: number; fail: number; block: number; notRun: number }[]
  topScreens: { screenId: string; name: string; defects: number }[]
}

export interface ActionItem {
  itemId: string
  content: string
  owner: UserRef | null
  dueDate: string | null
  done: boolean
}

export interface Meeting {
  meetingId: string
  meetingNo: string
  title: string
  at: string
  attendees: string
  content: string
  actionItems: ActionItem[]
}

export interface MeetingPayload {
  title: string
  at: string
  attendees: string
  content: string
  actionItems: { itemId?: string; content: string; ownerId: string | null; dueDate: string | null; done: boolean }[]
}

/* ── 7. 공통 ─────────────────────────────────────────────── */
export type NotifyEvent =
  | 'ASSIGNED' | 'REVIEW_REQUESTED' | 'APPROVED' | 'REJECTED' | 'DUE_SOON' | 'OVERDUE'
  | 'DEFECT_ASSIGNED' | 'ISSUE_ESCALATED' | 'TIMESHEET_REJECTED' | 'MENTION'

export const NOTIFY_EVENTS: NotifyEvent[] = [
  'ASSIGNED', 'REVIEW_REQUESTED', 'APPROVED', 'REJECTED', 'DUE_SOON', 'OVERDUE',
  'DEFECT_ASSIGNED', 'ISSUE_ESCALATED', 'TIMESHEET_REJECTED', 'MENTION',
]

export interface Notification {
  notificationId: string
  event: NotifyEvent
  title: string
  body: string
  link: string | null
  read: boolean
  createdAt: string
}

export interface NotificationSettings {
  email: boolean
  webhook: boolean
  events: Record<NotifyEvent, boolean>
}

export interface ProjectDashboard {
  progress: ProjectProgress
  openIssues: number
  overdueIssues: number
  risksTop: Risk[]
  defects: { open: number; critical: number }
  mm: { planned: number; actual: number }
  deliverables: Record<string, number>
  analysis: { done: number; total: number } | null
  milestones: { name: string; planEnd: string; status: string }[]
  /** SM 프로젝트 위젯 (R3) — SI 프로젝트면 null/생략 */
  sm?: { openSrs: number; dueSoon: number; breached: number; complianceRate: number; openIncidents: number } | null
}

export interface PortfolioRow {
  projectId: string
  name: string
  type: string
  customer: string
  pm: string
  planned: number
  actual: number
  spi: number | null
  openIssues: number
  criticalDefects: number
  health: 'GREEN' | 'YELLOW' | 'RED'
}

export interface GlobalSearchHit {
  type: TraceType | 'WBS' | 'MEETING'
  id: string
  label: string
  sub: string
  projectId: string
}

export interface MemberScope {
  systemIds: string[]
  menuIds: string[]
}
