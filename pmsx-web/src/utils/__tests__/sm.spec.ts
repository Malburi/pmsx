import { describe, expect, it } from 'vitest'
import type { SrSla } from '@/types/r3'
import {
  allowedSrTransitions, expiryLevel, isSrTransitionAllowed, liveRemainingMinutes, liveSlaState, monthGrid, routineDates, shiftMonth,
  slaRemainingLabel, srTransitionNeedsAssignee, srTransitionNeedsComment,
} from '@/utils/sm'

const sla = (over: Partial<SrSla> = {}): SrSla => ({
  policyId: 'p', policyName: 'P3', responseDue: '2026-10-15T10:00:00+09:00', resolveDue: '2026-10-16T10:00:00+09:00',
  respondedAt: null, resolvedAt: null, pausedMinutes: 0, responseBreached: false, resolveBreached: false,
  remainingMinutes: 90, state: 'OK', ...over,
})

// 테스트용 번역 함수 — 키와 인자를 그대로 이어 붙인다
const t = (key: string, args?: Record<string, unknown>) => {
  const short = key.split('.').pop() as string
  if (short === 'day' || short === 'hour' || short === 'min') return `${args?.n}${short[0]}`
  return `${short}(${args?.span})`
}

describe('SR 상태 전이 (docs/13 §1)', () => {
  it('정해진 흐름과 반려만 허용한다', () => {
    expect(allowedSrTransitions('RECEIVED')).toEqual(['CLASSIFIED', 'REJECTED'])
    expect(allowedSrTransitions('IN_PROGRESS')).toEqual(['ON_HOLD', 'RESOLVED', 'REJECTED'])
    expect(allowedSrTransitions('RESOLVED')).toEqual(['CONFIRMED', 'IN_PROGRESS', 'REJECTED'])
    expect(isSrTransitionAllowed('RECEIVED', 'RESOLVED')).toBe(false)
    expect(isSrTransitionAllowed('ON_HOLD', 'IN_PROGRESS')).toBe(true)
  })

  it('종료·반려된 SR 은 더 바꿀 수 없다', () => {
    expect(allowedSrTransitions('CLOSED')).toEqual([])
    expect(allowedSrTransitions('REJECTED')).toEqual([])
  })

  it('반려·보류·재요청은 사유가, 배정은 담당자가 필요하다', () => {
    expect(srTransitionNeedsComment('IN_PROGRESS', 'REJECTED')).toBe(true)
    expect(srTransitionNeedsComment('IN_PROGRESS', 'ON_HOLD')).toBe(true)
    expect(srTransitionNeedsComment('RESOLVED', 'IN_PROGRESS')).toBe(true)
    expect(srTransitionNeedsComment('ON_HOLD', 'IN_PROGRESS')).toBe(false)
    expect(srTransitionNeedsAssignee('ASSIGNED')).toBe(true)
  })
})

describe('SLA 남은 시간 표시', () => {
  const fetchedAt = 1_000_000

  it('화면을 연 뒤 지난 시간만큼 줄인다', () => {
    expect(liveRemainingMinutes(sla(), fetchedAt, fetchedAt + 30 * 60_000)).toBe(60)
    expect(liveRemainingMinutes(sla(), fetchedAt, fetchedAt + 59_999)).toBe(90)
  })

  it('보류·완료 상태는 시간이 흘러도 그대로다', () => {
    expect(liveRemainingMinutes(sla({ state: 'PAUSED' }), fetchedAt, fetchedAt + 600 * 60_000)).toBe(90)
    expect(liveRemainingMinutes(sla({ state: 'MET', remainingMinutes: 12 }), fetchedAt, fetchedAt + 600 * 60_000)).toBe(12)
  })

  it('기한을 넘기면 위반으로 바꿔 보여 준다', () => {
    expect(liveSlaState(sla({ state: 'DUE_SOON' }), -1)).toBe('BREACHED')
    expect(liveSlaState(sla({ state: 'DUE_SOON' }), 5)).toBe('DUE_SOON')
    expect(liveSlaState(sla({ state: 'PAUSED' }), -10)).toBe('PAUSED')
  })

  it('남은 시간·초과 시간을 일·시간·분으로 표기한다', () => {
    expect(slaRemainingLabel(45, t)).toBe('remaining(45m)')
    expect(slaRemainingLabel(125, t)).toBe('remaining(2h 5m)')
    expect(slaRemainingLabel(120, t)).toBe('remaining(2h)')
    expect(slaRemainingLabel(1500, t)).toBe('remaining(1d 1h)')
    expect(slaRemainingLabel(-30, t)).toBe('overdueBy(30m)')
    expect(slaRemainingLabel(0, t)).toBe('remaining(0m)')
  })
})

describe('운영 자산·정기 작업', () => {
  it('만료 D-day 단계', () => {
    expect(expiryLevel({ daysLeft: null })).toBe('none')
    expect(expiryLevel({ daysLeft: -1 })).toBe('expired')
    expect(expiryLevel({ daysLeft: 7 })).toBe('urgent')
    expect(expiryLevel({ daysLeft: 30 })).toBe('soon')
    expect(expiryLevel({ daysLeft: 31 })).toBe('ok')
  })

  it('정기 작업 발생일 — 영업일·요일·월 말일 보정', () => {
    // 2026-10-12(월) ~ 2026-10-18(일)
    expect(routineDates({ freq: 'DAILY' }, '2026-10-12', '2026-10-18')).toHaveLength(5)
    expect(routineDates({ freq: 'WEEKLY', dow: [1, 3] }, '2026-10-12', '2026-10-18')).toEqual(['2026-10-12', '2026-10-14'])
    expect(routineDates({ freq: 'MONTHLY', day: 31 }, '2026-11-01', '2026-11-30')).toEqual(['2026-11-30'])
  })

  it('월 달력 칸은 일요일 시작 7의 배수', () => {
    const cells = monthGrid('2026-10')
    expect(cells.length % 7).toBe(0)
    expect(cells.indexOf('2026-10-01')).toBe(4) // 2026-10-01 은 목요일
    expect(shiftMonth('2026-12', 1)).toBe('2027-01')
    expect(shiftMonth('2026-01', -1)).toBe('2025-12')
  })
})
