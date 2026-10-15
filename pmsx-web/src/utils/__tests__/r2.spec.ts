import { describe, expect, it } from 'vitest'
import {
  addBusinessDays, addDays, businessDaysBetween, diffDays, isoWeek, monthEnd, monthRange, weekRange, weekStart,
} from '@/utils/date'
import { applyDrag, barRect, dayToX, depPath, ganttRange, scaleTicks, todayX, xToDay } from '@/utils/gantt'
import {
  allocLevel, allowedDefectTransitions, dailyTotals, overAllocated, riskLevel, sumMm,
} from '@/utils/quality'
import { depthMap, hasDepCycle, indent, isDelayed, moveSibling, outdent, renumber, rollup, treeOrder, visibleRows } from '@/utils/wbs'
import type { WbsItem } from '@/types/r2'

const wbs = (wbsId: string, parentId: string | null, order: number, extra: Partial<WbsItem> = {}): WbsItem => ({
  wbsId, parentId, order, wbsNo: '', name: wbsId, planStart: null, planEnd: null, actStart: null, actEnd: null,
  weight: 1, progress: 0, milestone: false, status: 'TODO', assignees: [], deliverableId: null, deps: [],
  critical: false, baselineStart: null, baselineEnd: null, ...extra,
})

describe('날짜·영업일', () => {
  it('더하기·차이', () => {
    expect(addDays('2026-12-30', 3)).toBe('2027-01-02')
    expect(diffDays('2026-02-27', '2026-03-02')).toBe(3)
  })

  it('영업일은 주말·공휴일을 뺀다', () => {
    const holidays = new Set(['2026-10-09']) // 한글날(금)
    // 2026-10-05(월) ~ 10-11(일): 평일 5일 - 공휴일 1일
    expect(businessDaysBetween('2026-10-05', '2026-10-11', holidays)).toBe(4)
    expect(addBusinessDays('2026-10-08', 1, holidays)).toBe('2026-10-12')
    expect(addBusinessDays('2026-10-12', -1, holidays)).toBe('2026-10-08')
    expect(businessDaysBetween('2026-10-11', '2026-10-05')).toBe(0)
  })

  it('주·월 범위와 ISO 주차', () => {
    expect(weekStart('2026-10-15')).toBe('2026-10-12')
    expect(weekStart('2026-10-18')).toBe('2026-10-12') // 일요일은 앞 주
    expect(isoWeek('2026-01-01')).toBe('2026-W01')
    expect(isoWeek('2027-01-01')).toBe('2026-W53')
    expect(monthRange('2026-11-15', '2027-02-01')).toEqual(['2026-11', '2026-12', '2027-01', '2027-02'])
    expect(monthEnd('2028-02')).toBe('2028-02-29')
    expect(weekRange('2026-10-15', '2026-10-27')).toEqual(['2026-10-12', '2026-10-19', '2026-10-26'])
  })
})

describe('간트 좌표', () => {
  const range = { start: '2026-11-01', end: '2026-12-31' }

  it('날짜 ↔ x 왕복', () => {
    expect(dayToX('2026-11-11', range, 'week')).toBe(240)
    expect(xToDay(240, range, 'week')).toBe('2026-11-11')
    expect(xToDay(263, range, 'week')).toBe('2026-11-11')
    expect(barRect('2026-11-02', '2026-11-04', range, 'month')).toEqual({ x: 6, w: 18 })
  })

  it('드래그는 날짜 칸 단위로 반올림하고 시작 ≤ 끝을 지킨다', () => {
    expect(applyDrag('move', '2026-11-02', '2026-11-06', 50, 'week')).toEqual({ start: '2026-11-04', end: '2026-11-08' })
    expect(applyDrag('end', '2026-11-02', '2026-11-06', -24 * 10, 'week')).toEqual({ start: '2026-11-02', end: '2026-11-02' })
    expect(applyDrag('start', '2026-11-02', '2026-11-06', 24 * 2, 'week')).toEqual({ start: '2026-11-04', end: '2026-11-06' })
  })

  it('표시 범위·눈금·오늘선', () => {
    const r = ganttRange([{ planStart: '2026-11-10', planEnd: '2026-11-20', baselineEnd: '2026-11-25' }], '2026-10-15', 7)
    expect(r).toEqual({ start: '2026-11-03', end: '2026-12-02' })
    const ticks = scaleTicks(r, 'week')
    expect(ticks.filter((t) => t.major).map((t) => t.label)).toEqual(['2026.11', '2026.12'])
    expect(ticks.some((t) => !t.major && t.label === '11/03')).toBe(false)
    expect(ticks.find((t) => !t.major)?.x).toBeLessThanOrEqual(0)
    expect(todayX('2027-01-01', r, 'week')).toBeNull()
    expect(todayX('2026-11-03', r, 'week')).toBe(12)
  })

  it('FS 화살표는 선행 끝에서 후행 시작으로 간다', () => {
    const d = depPath('FS', { x: 0, w: 100, y: 10 }, { x: 150, w: 50, y: 40 })
    expect(d.startsWith('M100,10')).toBe(true)
    expect(d.endsWith('H150')).toBe(true)
  })
})

describe('WBS 계층 편집', () => {
  const base = renumber([wbs('a', null, 0), wbs('b', null, 1), wbs('c', null, 2), wbs('a1', 'a', 0)])

  it('트리 순서·번호', () => {
    expect(treeOrder(base).map((i) => `${i.wbsNo}:${i.wbsId}`)).toEqual(['1:a', '1.1:a1', '2:b', '3:c'])
    expect(depthMap(base).get('a1')).toBe(1)
  })

  it('들여쓰기는 위 형제의 마지막 자식, 내어쓰기는 부모 바로 뒤', () => {
    const ind = indent(base, 'b')
    expect(ind.find((i) => i.wbsId === 'b')).toMatchObject({ parentId: 'a', wbsNo: '1.2' })
    const out = outdent(ind, 'a1')
    expect(treeOrder(out).map((i) => i.wbsId)).toEqual(['a', 'b', 'a1', 'c'])
    expect(out.find((i) => i.wbsId === 'a1')?.wbsNo).toBe('2')
    expect(indent(base, 'a')).toBe(base) // 첫 항목은 들여쓸 수 없다
  })

  it('형제 순서 바꾸기·접기', () => {
    const moved = moveSibling(base, 'c', -1)
    expect(treeOrder(moved).map((i) => i.wbsId)).toEqual(['a', 'a1', 'c', 'b'])
    expect(visibleRows(base, new Set(['a'])).map((i) => i.wbsId)).toEqual(['a', 'b', 'c'])
  })

  it('선후행 순환 탐지', () => {
    expect(hasDepCycle([wbs('x', null, 0, { deps: [{ predId: 'y', type: 'FS', lagDays: 0 }] }), wbs('y', null, 1)])).toBe(false)
    expect(hasDepCycle([
      wbs('x', null, 0, { deps: [{ predId: 'y', type: 'FS', lagDays: 0 }] }),
      wbs('y', null, 1, { deps: [{ predId: 'x', type: 'SS', lagDays: 0 }] }),
    ])).toBe(true)
  })

  it('상위 집계 — 가중 평균 진척, 하위 기간', () => {
    const items = [
      wbs('p', null, 0),
      wbs('k1', 'p', 0, { weight: 3, progress: 100, planStart: '2026-11-02', planEnd: '2026-11-10' }),
      wbs('k2', 'p', 1, { weight: 1, progress: 0, planStart: '2026-11-05', planEnd: '2026-11-20' }),
    ]
    const p = rollup(items).find((i) => i.wbsId === 'p')
    expect(p).toMatchObject({ progress: 75, planStart: '2026-11-02', planEnd: '2026-11-20' })
    expect(isDelayed({ planEnd: '2026-11-10', status: 'IN_PROGRESS' }, '2026-11-11')).toBe(true)
    expect(isDelayed({ planEnd: '2026-11-10', status: 'DONE' }, '2026-11-11')).toBe(false)
  })
})

describe('품질·인력 규칙', () => {
  it('결함은 허용 전이만', () => {
    expect(allowedDefectTransitions('NEW')).toEqual(['ASSIGNED'])
    expect(allowedDefectTransitions('FIXED')).toEqual(['VERIFIED', 'REOPENED'])
    expect(allowedDefectTransitions('CLOSED')).toEqual([])
  })

  it('리스크 등급', () => {
    expect([1, 4, 9, 15, 25].map(riskLevel)).toEqual(['low', 'medium', 'high', 'critical', 'critical'])
  })

  it('히트맵 배정률 단계·과배정 기간', () => {
    expect([0, 30, 60, 95, 130].map(allocLevel)).toEqual(['none', 'low', 'normal', 'full', 'over'])
    expect(overAllocated({ '2026-12': 80, '2026-11': 130, '2027-01': 101 })).toEqual(['2026-11', '2027-01'])
    expect(sumMm({ a: 0.5, b: 1, c: 0.25 })).toBe(1.75)
  })

  it('작업일지 하루 합계', () => {
    expect(dailyTotals([{ date: 'd1', hours: 4 }, { date: 'd1', hours: 4.5 }, { date: 'd2', hours: 8 }])).toEqual({ d1: 8.5, d2: 8 })
  })
})
