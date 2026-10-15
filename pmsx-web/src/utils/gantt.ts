// 간트 차트 좌표 계산 (SCR-PJ-001). SVG 그리기와 분리해 단위 테스트한다.
import { addDays, diffDays, monthRange, parseDay, weekRange } from '@/utils/date'
import type { DepType } from '@/types/r2'

export type GanttScale = 'week' | 'month'

/** 하루 폭(px) — 주 단위는 칸이 넓고, 월 단위는 좁다 */
export const DAY_WIDTH: Record<GanttScale, number> = { week: 24, month: 6 }

export interface GanttRange {
  start: string
  end: string
}

/** 항목들의 기간을 덮는 표시 범위. 앞뒤로 여유(padDays)를 둔다 */
export const ganttRange = (
  items: { planStart: string | null; planEnd: string | null; baselineStart?: string | null; baselineEnd?: string | null }[],
  fallback: string,
  padDays = 7,
): GanttRange => {
  const days = items.flatMap((i) => [i.planStart, i.planEnd, i.baselineStart ?? null, i.baselineEnd ?? null]).filter((d): d is string => !!d)
  if (!days.length) return { start: addDays(fallback, -padDays), end: addDays(fallback, 30) }
  const sorted = [...days].sort()
  return { start: addDays(sorted[0], -padDays), end: addDays(sorted[sorted.length - 1], padDays) }
}

/** 날짜 → x(px). 날짜 칸의 왼쪽 가장자리 */
export const dayToX = (day: string, range: GanttRange, scale: GanttScale): number =>
  diffDays(range.start, day) * DAY_WIDTH[scale]

/** x(px) → 날짜 (칸 안 어디든 그 날짜로 내림) */
export const xToDay = (x: number, range: GanttRange, scale: GanttScale): string =>
  addDays(range.start, Math.floor(x / DAY_WIDTH[scale]))

export const totalWidth = (range: GanttRange, scale: GanttScale): number =>
  (diffDays(range.start, range.end) + 1) * DAY_WIDTH[scale]

/** 막대 사각형 (끝 날짜 포함이므로 +1일) */
export const barRect = (start: string, end: string, range: GanttRange, scale: GanttScale) => {
  const x = dayToX(start, range, scale)
  const w = Math.max(DAY_WIDTH[scale], (diffDays(start, end) + 1) * DAY_WIDTH[scale])
  return { x, w }
}

export type DragMode = 'move' | 'start' | 'end'

/**
 * 드래그 결과 날짜. 이동량(dx px)을 날짜 칸 단위로 반올림한다.
 * - move: 기간 유지한 채 이동
 * - start/end: 한쪽만 조절, 시작 ≤ 끝 유지
 */
export const applyDrag = (
  mode: DragMode,
  start: string,
  end: string,
  dx: number,
  scale: GanttScale,
): { start: string; end: string } => {
  const days = Math.round(dx / DAY_WIDTH[scale])
  if (mode === 'move') return { start: addDays(start, days), end: addDays(end, days) }
  if (mode === 'start') {
    const s = addDays(start, days)
    return { start: diffDays(s, end) < 0 ? end : s, end }
  }
  const e = addDays(end, days)
  return { start, end: diffDays(start, e) < 0 ? start : e }
}

export interface ScaleTick {
  x: number
  w: number
  label: string
  /** 상단(월) 줄 여부 */
  major: boolean
}

/** 헤더 눈금: 상단은 월, 하단은 주(week 스케일) 또는 표시 없음(month 스케일) */
export const scaleTicks = (range: GanttRange, scale: GanttScale): ScaleTick[] => {
  const ticks: ScaleTick[] = []
  monthRange(range.start, range.end).forEach((ym) => {
    const first = `${ym}-01`
    const from = diffDays(range.start, first) < 0 ? range.start : first
    const [y, m] = ym.split('-').map(Number)
    const last = addDays(`${m === 12 ? y + 1 : y}-${String(m === 12 ? 1 : m + 1).padStart(2, '0')}-01`, -1)
    const to = diffDays(last, range.end) < 0 ? range.end : last
    const x = dayToX(from, range, scale)
    ticks.push({ x, w: (diffDays(from, to) + 1) * DAY_WIDTH[scale], label: `${y}.${String(m).padStart(2, '0')}`, major: true })
  })
  if (scale === 'week') {
    weekRange(range.start, range.end).forEach((ws) => {
      const from = diffDays(range.start, ws) < 0 ? range.start : ws
      ticks.push({ x: dayToX(from, range, scale), w: 7 * DAY_WIDTH[scale], label: `${ws.slice(5, 7)}/${ws.slice(8, 10)}`, major: false })
    })
  }
  return ticks
}

/** 선후행 화살표 경로 (직각 꺾은선). 타입별로 선행의 시작/끝 → 후행의 시작/끝 */
export const depPath = (
  type: DepType,
  pred: { x: number; w: number; y: number },
  succ: { x: number; w: number; y: number },
): string => {
  const fromX = type === 'SS' || type === 'SF' ? pred.x : pred.x + pred.w
  const toX = type === 'FF' || type === 'SF' ? succ.x + succ.w : succ.x
  const gap = 8
  const out = type === 'SS' || type === 'SF' ? fromX - gap : fromX + gap
  const inn = type === 'FF' || type === 'SF' ? toX + gap : toX - gap
  const midY = (pred.y + succ.y) / 2
  return `M${fromX},${pred.y} H${out} V${midY} H${inn} V${succ.y} H${toX}`
}

/** 오늘선 x (범위 밖이면 null) */
export const todayX = (day: string, range: GanttRange, scale: GanttScale): number | null => {
  if (parseDay(day) < parseDay(range.start) || parseDay(day) > parseDay(range.end)) return null
  return dayToX(day, range, scale) + DAY_WIDTH[scale] / 2
}
