// docs/13_API명세_R3_SM운영.md 의 타입을 그대로 옮긴 것. 명세가 바뀌면 이 파일을 먼저 고친다.
import type { UserRef } from '@/types/api'

/* ── 1. SR ───────────────────────────────────────────────── */
export const SR_TYPES = ['INQUIRY', 'IMPROVE', 'DATA', 'INCIDENT', 'AUTH'] as const
export type SrType = (typeof SR_TYPES)[number]
export const SR_STATUSES = ['RECEIVED', 'CLASSIFIED', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD', 'RESOLVED', 'CONFIRMED', 'CLOSED', 'REJECTED'] as const
export type SrStatus = (typeof SR_STATUSES)[number]
export const SR_PRIORITIES = ['P1', 'P2', 'P3', 'P4'] as const
export type SrPriority = (typeof SR_PRIORITIES)[number]
export type SrChannel = 'PORTAL' | 'EMAIL' | 'MANUAL'
export type SlaState = 'OK' | 'DUE_SOON' | 'BREACHED' | 'MET' | 'PAUSED'

export interface SrSla {
  policyId: string
  policyName: string
  responseDue: string
  resolveDue: string
  respondedAt: string | null
  resolvedAt: string | null
  pausedMinutes: number
  responseBreached: boolean
  resolveBreached: boolean
  /** 해결 기한까지 남은 업무 분(음수면 초과) — 응답 시점 기준 */
  remainingMinutes: number
  state: SlaState
}

export interface SrComment {
  commentId: string
  content: string
  internal: boolean
  author: UserRef
  createdAt: string
}

export interface SrHistory {
  at: string
  user: string
  from: SrStatus | null
  to: SrStatus
  comment: string | null
}

export interface SimilarItem {
  kind: 'KB' | 'SR'
  id: string
  no: string
  title: string
  score: number
}

export interface Sr {
  srId: string
  srNo: string
  type: SrType
  priority: SrPriority
  title: string
  description: string
  channel: SrChannel
  requester: { name: string; org: string | null; contact: string | null; userId: string | null }
  status: SrStatus
  assignee: UserRef | null
  systemId: string | null
  screenId: string | null
  elementId: string | null
  screenName: string | null
  sla: SrSla | null
  effortHours: number
  createdAt: string
  updatedAt: string
  comments?: SrComment[]
  history?: SrHistory[]
  similar?: SimilarItem[]
}

export interface SrPayload {
  type: SrType
  priority: SrPriority
  title: string
  description: string
  channel?: SrChannel
  requester?: { name: string; org: string | null; contact: string | null }
  assigneeId?: string | null
  systemId: string | null
  screenId: string | null
  elementId: string | null
}

export interface SrQuery {
  status?: SrStatus
  type?: SrType
  priority?: SrPriority
  assigneeId?: string
  screenId?: string
  q?: string
  slaState?: 'DUE_SOON' | 'BREACHED'
  page?: number
  size?: number
}

/** 대상 화면 선택 결과 (SR 등록·포털) */
export interface ScreenPick {
  systemId: string
  screenId: string
  elementId: string | null
  screenName: string
  elementLabel: string | null
}

/* ── 2. 고객 포털 ─────────────────────────────────────────── */
export interface PortalSrPayload {
  type: SrType
  title: string
  description: string
  systemId: string | null
  screenId: string | null
  elementId: string | null
}

/* ── 3. SLA ──────────────────────────────────────────────── */
export interface SlaPolicy {
  policyId: string
  name: string
  types: SrType[]
  priorities: string[]
  responseMinutes: number
  resolveMinutes: number
  businessHours: { start: string; end: string; days: number[] }
  twentyFourSeven: boolean
  order: number
}

export interface SlaStatus {
  complianceRate: number
  responseRate: number
  total: number
  met: number
  breached: number
  dueSoon: Sr[]
  breachedList: Sr[]
  byType: Record<SrType, { total: number; met: number }>
}

/* ── 4. 장애·문제·변경 ─────────────────────────────────────── */
export type IncidentStatus = 'OPEN' | 'RECOVERED' | 'CLOSED'
export interface Incident {
  incidentId: string
  incidentNo: string
  title: string
  grade: 1 | 2 | 3 | 4
  status: IncidentStatus
  startedAt: string
  detectedAt: string | null
  recoveredAt: string | null
  impact: string
  systemIds: string[]
  timeline: { at: string; content: string }[]
  cause: string | null
  rca: string | null
  prevention: string | null
  srIds: string[]
  durationMinutes: number | null
}
export type IncidentPayload = Omit<Incident, 'incidentId' | 'incidentNo' | 'durationMinutes' | 'timeline'> & { timeline?: Incident['timeline'] }

export type ProblemStatus = 'OPEN' | 'ANALYZING' | 'RESOLVED' | 'CLOSED'
export interface Problem {
  problemId: string
  problemNo: string
  title: string
  rootCause: string | null
  workaround: string | null
  knownError: boolean
  status: ProblemStatus
  srIds: string[]
  incidentIds: string[]
}
export type ProblemPayload = Omit<Problem, 'problemId' | 'problemNo'>

/**
 * 문제 후보 묶음 — 명세는 "최근 90일 같은 화면/요소 또는 유사 제목 SR·장애 3건 이상 묶음"만 정의.
 * 응답 형태는 웹 R3 가 정한 아래 형식을 서버에 제안한다 (docs/13 v0.3 후보).
 */
export interface ProblemCandidate {
  key: string
  reason: 'SAME_SCREEN' | 'SAME_ELEMENT' | 'SIMILAR_TITLE'
  label: string
  count: number
  srs: { srId: string; srNo: string; title: string; createdAt: string }[]
  incidents: { incidentId: string; incidentNo: string; title: string }[]
}

export const CHANGE_STATUSES = ['REQUESTED', 'CAB_APPROVED', 'DEPLOYED', 'CLOSED', 'FAILED', 'ROLLED_BACK', 'REJECTED'] as const
export type ChangeStatus = (typeof CHANGE_STATUSES)[number]
export interface Change {
  changeId: string
  changeNo: string
  title: string
  type: 'NORMAL' | 'STANDARD' | 'EMERGENCY'
  risk: 'H' | 'M' | 'L'
  plan: string
  rollbackPlan: string
  scheduledAt: string | null
  deployedAt: string | null
  status: ChangeStatus
  srIds: string[]
  screenIds: string[]
  cab: { userId: string; name: string; decision: string; comment: string | null; at: string }[]
  commits: string[]
}
export type ChangePayload = Omit<Change, 'changeId' | 'changeNo' | 'status' | 'cab' | 'deployedAt'>

/* ── 5. 정기 작업·운영 자산·KB ─────────────────────────────── */
export interface RoutineRule { freq: 'DAILY' | 'WEEKLY' | 'MONTHLY'; dow?: number[]; day?: number }
export interface RoutineJob {
  jobId: string
  name: string
  rule: RoutineRule
  owner: UserRef | null
  checklist: string[]
}
export type RoutineJobPayload = Omit<RoutineJob, 'jobId' | 'owner'> & { ownerId: string | null }
export interface RoutineOccurrence {
  jobId: string
  name: string
  date: string
  done: boolean
  doneBy: string | null
  note: string | null
}

export const OPS_ASSET_TYPES = ['SERVER', 'ACCOUNT', 'CERT', 'LICENSE', 'DOMAIN'] as const
export type OpsAssetType = (typeof OPS_ASSET_TYPES)[number]
export interface OpsAsset {
  assetId: string
  type: OpsAssetType
  name: string
  host: string | null
  owner: string | null
  expiresAt: string | null
  note: string | null
  /** 만료까지 남은 일수(만료일 없으면 null, 지났으면 음수) */
  daysLeft: number | null
}
export type OpsAssetPayload = Omit<OpsAsset, 'assetId' | 'daysLeft'>

export interface KbArticle {
  kbId: string
  kbNo: string
  title: string
  content: string
  tags: string[]
  systemId: string | null
  screenId: string | null
  sourceSrId: string | null
  updatedAt: string
}
export type KbPayload = Omit<KbArticle, 'kbId' | 'kbNo' | 'updatedAt'>

/* ── 6. 월간 운영보고 ──────────────────────────────────────── */
export interface MonthlyReportSummary { reportId: string; month: string; updatedAt: string }
export interface MonthlyReport {
  reportId: string
  month: string
  summary: string
  plan: string
  sr: { total: number; byType: Record<string, number>; byStatus: Record<string, number>; carriedOver: number }
  sla: { complianceRate: number; responseRate: number; breaches: { srNo: string; title: string; reason: string | null }[] }
  incidents: { incidentNo: string; title: string; grade: number; durationMinutes: number | null }[]
  changes: { changeNo: string; title: string; deployedAt: string | null; status: string }[]
  effort: { userId: string; name: string; hours: number; mm: number }[]
  topScreens: { screenId: string; name: string; srCount: number }[]
}

/* ── 7. SI → SM 인수인계 ──────────────────────────────────── */
export interface HandoverRequest {
  target: { newProject: { name: string; customer: string; startDate: string; endDate: string; pmUserId: string } } | { projectId: string }
  systemIds: string[]
  memberUserIds: string[]
  include: { notes: boolean; traceLinks: boolean; deliverables: boolean; openDefectsAsSr: boolean }
}
export interface HandoverPreview {
  systems: { systemId: string; name: string; screens: number; elements: number; edges: number }[]
  members: number
  traceLinks: number
  notes: number
  deliverables: number
}
export interface HandoverResult { smProjectId: string; copied: Record<string, number> }

/* ── 8. 영향도 분석 ───────────────────────────────────────── */
export interface ImpactResult {
  target: { nodeId: string; nodeType: string; name: string; column: string | null }
  screens: {
    screenId: string
    screenCode: string
    name: string
    menuPath: string[]
    owner: string | null
    elements: { elementId: string; label: string; crud: string | null; path: string[] }[]
  }[]
  apis: { nodeId: string; name: string }[]
  serverFunctions: { nodeId: string; name: string; file: string | null }[]
  batches: { nodeId: string; name: string; file: string | null }[]
  srs: { srId: string; srNo: string; title: string; status: string; createdAt: string }[]
  defects: { defectId: string; defectNo: string; title: string; status: string }[]
  owners: { userId: string; name: string; reason: string }[]
  summary: { screens: number; elements: number; apis: number; srs: number; truncated?: boolean }
}
