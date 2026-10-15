// SM 운영(R3) 화면 규칙 — SR 전이, SLA 남은 시간 표시, 운영 자산 D-day, 정기 작업 발생일 (docs/13)
import type { ChangeStatus, OpsAsset, RoutineRule, SlaState, SrSla, SrStatus } from '@/types/r3'
import { addDays, dayOfWeek, diffDays, monthEnd } from '@/utils/date'

/* ── SR 전이 (docs/13 §1) ─────────────────────────────────── */
// RECEIVED→CLASSIFIED→ASSIGNED→IN_PROGRESS⇄ON_HOLD→RESOLVED→CONFIRMED→CLOSED,
// RESOLVED→IN_PROGRESS(재요청), 종료 전 어느 단계든 →REJECTED(사유 필수)
const SR_FLOW: Record<SrStatus, SrStatus[]> = {
  RECEIVED: ['CLASSIFIED'],
  CLASSIFIED: ['ASSIGNED'],
  ASSIGNED: ['IN_PROGRESS'],
  IN_PROGRESS: ['ON_HOLD', 'RESOLVED'],
  ON_HOLD: ['IN_PROGRESS'],
  RESOLVED: ['CONFIRMED', 'IN_PROGRESS'],
  CONFIRMED: ['CLOSED'],
  CLOSED: [],
  REJECTED: [],
}
const SR_TERMINAL: SrStatus[] = ['CLOSED', 'REJECTED']

export const allowedSrTransitions = (from: SrStatus): SrStatus[] =>
  SR_TERMINAL.includes(from) ? [] : [...SR_FLOW[from], 'REJECTED']

export const isSrTransitionAllowed = (from: SrStatus, to: SrStatus): boolean => allowedSrTransitions(from).includes(to)

/** 사유(코멘트) 없이는 보낼 수 없는 전이 */
export const srTransitionNeedsComment = (from: SrStatus, to: SrStatus): boolean =>
  to === 'REJECTED' || to === 'ON_HOLD' || (from === 'RESOLVED' && to === 'IN_PROGRESS')

/** 담당자를 함께 지정해야 하는 전이 */
export const srTransitionNeedsAssignee = (to: SrStatus): boolean => to === 'ASSIGNED'

export const isSrOpen = (s: SrStatus): boolean => !['RESOLVED', 'CONFIRMED', 'CLOSED', 'REJECTED'].includes(s)

/* ── SLA 남은 시간 ────────────────────────────────────────── */
/**
 * 서버가 준 남은 업무 분(remainingMinutes)에서 화면을 연 뒤 지난 시간을 뺀다.
 * 일시정지·완료(MET) 상태는 줄이지 않는다. 업무 외 시간 보정은 서버 재조회로 맞춘다(1분 폴링).
 */
export const liveRemainingMinutes = (sla: SrSla, fetchedAt: number, now: number): number => {
  if (sla.state === 'PAUSED' || sla.state === 'MET') return sla.remainingMinutes
  return sla.remainingMinutes - Math.floor(Math.max(0, now - fetchedAt) / 60_000)
}

/** 분 → '1일 3시간', '2시간 5분', '45분' (영업일 1일 = 8시간이 아니라 달력 시간 기준 표기) */
export const formatMinutes = (minutes: number): { days: number; hours: number; mins: number } => {
  const m = Math.abs(Math.trunc(minutes))
  return { days: Math.floor(m / 1440), hours: Math.floor((m % 1440) / 60), mins: m % 60 }
}

export const slaRemainingLabel = (minutes: number, t: (key: string, args?: Record<string, unknown>) => string): string => {
  const { days, hours, mins } = formatMinutes(minutes)
  const parts: string[] = []
  if (days) parts.push(t('sm.sla.unit.day', { n: days }))
  if (hours) parts.push(t('sm.sla.unit.hour', { n: hours }))
  if (!days && (mins || !hours)) parts.push(t('sm.sla.unit.min', { n: mins }))
  const span = parts.join(' ')
  return minutes < 0 ? t('sm.sla.overdueBy', { span }) : t('sm.sla.remaining', { span })
}

/** 표시용 상태 — 서버 상태를 쓰되, 화면에서 시간이 흘러 기한을 넘기면 BREACHED 로 바꿔 보여 준다 */
export const liveSlaState = (sla: SrSla, remaining: number): SlaState => {
  if (sla.state === 'MET' || sla.state === 'PAUSED') return sla.state
  if (remaining < 0) return 'BREACHED'
  return sla.state
}

/* ── 변경 전이 ─────────────────────────────────────────────── */
export const CHANGE_TRANSITIONS: Record<ChangeStatus, ChangeStatus[]> = {
  REQUESTED: ['REJECTED'],                 // 승인은 CAB 결정(/cab)으로만
  CAB_APPROVED: ['DEPLOYED', 'REJECTED'],
  DEPLOYED: ['CLOSED', 'FAILED', 'ROLLED_BACK'],
  FAILED: ['ROLLED_BACK', 'CLOSED'],
  ROLLED_BACK: ['CLOSED'],
  CLOSED: [],
  REJECTED: [],
}

/* ── 운영 자산 만료 ────────────────────────────────────────── */
/** D-day 단계: 지남·7일 이내·30일 이내·여유 */
export const expiryLevel = (a: Pick<OpsAsset, 'daysLeft'>): 'expired' | 'urgent' | 'soon' | 'ok' | 'none' => {
  if (a.daysLeft === null || a.daysLeft === undefined) return 'none'
  if (a.daysLeft < 0) return 'expired'
  if (a.daysLeft <= 7) return 'urgent'
  if (a.daysLeft <= 30) return 'soon'
  return 'ok'
}

export const daysLeftOf = (expiresAt: string | null, todayStr: string): number | null =>
  expiresAt ? diffDays(todayStr, expiresAt.slice(0, 10)) : null

/* ── 정기 작업 발생일 ──────────────────────────────────────── */
/** 규칙에 맞는 날짜 목록 (from~to, 포함). dow: 0=일 … 6=토. MONTHLY day 가 말일보다 크면 말일. */
export const routineDates = (rule: RoutineRule, from: string, to: string): string[] => {
  const out: string[] = []
  for (let d = from; d <= to; d = addDays(d, 1)) {
    if (rule.freq === 'DAILY') {
      const w = dayOfWeek(d)
      if (w !== 0 && w !== 6) out.push(d)
    } else if (rule.freq === 'WEEKLY') {
      if ((rule.dow ?? [1]).includes(dayOfWeek(d))) out.push(d)
    } else {
      const last = Number(monthEnd(d.slice(0, 7)).slice(8, 10))
      const target = Math.min(rule.day ?? 1, last)
      if (Number(d.slice(8, 10)) === target) out.push(d)
    }
  }
  return out
}

/** 월 달력 칸(일요일 시작, 앞뒤 빈칸 null) */
export const monthGrid = (ym: string): (string | null)[] => {
  const first = `${ym}-01`
  const lastDay = Number(monthEnd(ym).slice(8, 10))
  const lead = dayOfWeek(first)
  const cells: (string | null)[] = Array(lead).fill(null)
  for (let i = 0; i < lastDay; i++) cells.push(addDays(first, i))
  while (cells.length % 7) cells.push(null)
  return cells
}

export const shiftMonth = (ym: string, n: number): string => {
  const [y, m] = ym.split('-').map(Number)
  const d = new Date(Date.UTC(y, m - 1 + n, 1))
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}`
}
