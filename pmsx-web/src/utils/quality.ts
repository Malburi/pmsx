// 품질·인력 화면의 업무 규칙 (docs/12 §4·§6). 서버가 최종 검증하고, 화면은 버튼 노출만 정한다.
import type { CrStatus, DefectStatus, IssueStatus, RiskStatus, WbsStatus } from '@/types/r2'

/** 결함 허용 전이 (12 §6) */
export const DEFECT_TRANSITIONS: Record<DefectStatus, DefectStatus[]> = {
  NEW: ['ASSIGNED'],
  ASSIGNED: ['FIXED'],
  FIXED: ['VERIFIED', 'REOPENED'],
  VERIFIED: ['CLOSED', 'REOPENED'],
  CLOSED: [],
  REOPENED: ['ASSIGNED'],
}

export const allowedDefectTransitions = (s: DefectStatus): DefectStatus[] => DEFECT_TRANSITIONS[s] ?? []

/** CR 전이 — APPROVED/REJECTED 는 PM·고객만 (화면에서 역할로 한 번 더 거른다) */
export const CR_TRANSITIONS: Record<CrStatus, CrStatus[]> = {
  REQUESTED: ['ANALYZED', 'REJECTED'],
  ANALYZED: ['APPROVED', 'REJECTED'],
  APPROVED: ['APPLIED'],
  REJECTED: [],
  APPLIED: [],
}
export const CR_APPROVAL_STATUSES: CrStatus[] = ['APPROVED', 'REJECTED']

export const ISSUE_STATUSES: IssueStatus[] = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED']
export const RISK_STATUSES: RiskStatus[] = ['IDENTIFIED', 'MITIGATING', 'OCCURRED', 'CLOSED']
export const WBS_STATUSES: WbsStatus[] = ['TODO', 'IN_PROGRESS', 'DONE', 'BLOCKED']

export type RiskLevel = 'low' | 'medium' | 'high' | 'critical'

/** 리스크 점수(확률×영향, 1~25) 등급 */
export const riskLevel = (score: number): RiskLevel => {
  if (score >= 15) return 'critical'
  if (score >= 9) return 'high'
  if (score >= 4) return 'medium'
  return 'low'
}

export const RISK_LEVEL_COLOR: Record<RiskLevel, string> = {
  low: 'success', medium: 'info', high: 'warning', critical: 'error',
}

/** 배정률(%) 단계 — 히트맵 색 */
export type AllocLevel = 'none' | 'low' | 'normal' | 'full' | 'over'

export const allocLevel = (pct: number | undefined): AllocLevel => {
  if (!pct) return 'none'
  if (pct > 100) return 'over'
  if (pct >= 90) return 'full'
  if (pct >= 50) return 'normal'
  return 'low'
}

/** 기간 키별 배정률 합 → 과배정(>100%) 기간 목록 */
export const overAllocated = (cells: Record<string, number>): string[] =>
  Object.entries(cells).filter(([, v]) => v > 100).map(([k]) => k).sort()

/** MM 합계 (소수 둘째 자리) */
export const sumMm = (values: Record<string, number> | undefined): number =>
  Math.round(Object.values(values ?? {}).reduce((s, v) => s + (Number(v) || 0), 0) * 100) / 100

/** 작업일지 하루 합계 — 하루 12시간 초과는 입력 오류로 본다 */
export const MAX_HOURS_PER_DAY = 12

export const dailyTotals = (entries: { date: string; hours: number }[]): Record<string, number> =>
  entries.reduce<Record<string, number>>((acc, e) => {
    acc[e.date] = Math.round(((acc[e.date] ?? 0) + (Number(e.hours) || 0)) * 10) / 10
    return acc
  }, {})

export const STATUS_COLORS: Record<string, string> = {
  // 공통 진행 상태
  TODO: 'secondary', IN_PROGRESS: 'info', DONE: 'success', BLOCKED: 'error',
  OPEN: 'warning', RESOLVED: 'success', CLOSED: 'secondary',
  // 산출물·주간보고·작업일지
  DRAFT: 'secondary', REVIEW: 'info', APPROVED: 'success', REJECTED: 'error', SUBMITTED: 'info',
  // 요구사항
  PROPOSED: 'secondary', ACCEPTED: 'info', IMPLEMENTED: 'primary', VERIFIED: 'success', DROPPED: 'secondary',
  // 리스크
  IDENTIFIED: 'warning', MITIGATING: 'info', OCCURRED: 'error',
  // CR
  REQUESTED: 'secondary', ANALYZED: 'info', APPLIED: 'success',
  // 결함
  NEW: 'error', ASSIGNED: 'warning', FIXED: 'info', REOPENED: 'error',
  // 테스트 결과
  PASS: 'success', FAIL: 'error', BLOCK: 'warning', NOT_RUN: 'secondary',
  // 감리
  READY: 'success', NOT_READY: 'warning', NA: 'secondary',
  // 포트폴리오
  GREEN: 'success', YELLOW: 'warning', RED: 'error',
  // 심각도
  CRITICAL: 'error', MAJOR: 'warning', MINOR: 'info', TRIVIAL: 'secondary',
  H: 'error', M: 'warning', L: 'secondary',
  // R3 SR·SLA (docs/13)
  RECEIVED: 'secondary', CLASSIFIED: 'info', ON_HOLD: 'warning', CONFIRMED: 'success',
  OK: 'success', DUE_SOON: 'warning', BREACHED: 'error', MET: 'success', PAUSED: 'secondary',
  P1: 'error', P2: 'warning', P3: 'info', P4: 'secondary',
  // 장애·문제·변경
  RECOVERED: 'info', ANALYZING: 'info', CAB_APPROVED: 'info', DEPLOYED: 'primary', FAILED: 'error', ROLLED_BACK: 'warning',
  NORMAL: 'secondary', STANDARD: 'info', EMERGENCY: 'error',
}

export const statusColorOf = (s: string | null | undefined): string => (s ? STATUS_COLORS[s] ?? 'secondary' : 'secondary')
