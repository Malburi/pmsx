// 날짜 계산 순수 함수 — 모든 날짜는 'YYYY-MM-DD' 문자열(로컬 날짜)로 다룬다.
// 시간대 영향을 받지 않도록 내부 계산은 UTC 자정 기준으로 한다.

const DAY_MS = 86_400_000
const pad = (n: number) => String(n).padStart(2, '0')

/** 'YYYY-MM-DD' → UTC 자정 epoch(ms) */
export const parseDay = (s: string): number => {
  const [y, m, d] = s.split('-').map(Number)
  return Date.UTC(y, (m || 1) - 1, d || 1)
}

/** epoch(ms) → 'YYYY-MM-DD' */
export const formatDay = (ms: number): string => {
  const d = new Date(ms)
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}`
}

/** 오늘 (로컬 날짜) */
export const today = (now = new Date()): string =>
  `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`

export const addDays = (s: string, n: number): string => formatDay(parseDay(s) + n * DAY_MS)

/** b - a (일) */
export const diffDays = (a: string, b: string): number => Math.round((parseDay(b) - parseDay(a)) / DAY_MS)

/** 0=일 … 6=토 */
export const dayOfWeek = (s: string): number => new Date(parseDay(s)).getUTCDay()

export const isWeekend = (s: string): boolean => {
  const d = dayOfWeek(s)
  return d === 0 || d === 6
}

/** 영업일 여부 (주말·공휴일 제외) */
export const isBusinessDay = (s: string, holidays: ReadonlySet<string> = new Set()): boolean =>
  !isWeekend(s) && !holidays.has(s)

/** a~b(양끝 포함) 영업일 수. b < a 면 0 */
export const businessDaysBetween = (a: string, b: string, holidays: ReadonlySet<string> = new Set()): number => {
  if (diffDays(a, b) < 0) return 0
  let n = 0
  for (let d = a; diffDays(d, b) >= 0; d = addDays(d, 1)) if (isBusinessDay(d, holidays)) n++
  return n
}

/** s 에서 n 영업일 뒤(음수면 앞). s 자신은 세지 않는다 */
export const addBusinessDays = (s: string, n: number, holidays: ReadonlySet<string> = new Set()): string => {
  const step = n >= 0 ? 1 : -1
  let left = Math.abs(n)
  let d = s
  while (left > 0) {
    d = addDays(d, step)
    if (isBusinessDay(d, holidays)) left--
  }
  return d
}

/** 해당 주 월요일 */
export const weekStart = (s: string): string => {
  const dow = dayOfWeek(s)
  return addDays(s, dow === 0 ? -6 : 1 - dow)
}

/** ISO 주차 라벨 'YYYY-Www' (목요일이 속한 해 기준) */
export const isoWeek = (s: string): string => {
  const thursday = addDays(weekStart(s), 3)
  const year = Number(thursday.slice(0, 4))
  const firstThursday = addDays(weekStart(`${year}-01-04`), 3)
  const week = Math.floor(diffDays(firstThursday, thursday) / 7) + 1
  return `${year}-W${pad(week)}`
}

/** 'YYYY-MM' 목록 (양끝 포함) */
export const monthRange = (from: string, to: string): string[] => {
  const out: string[] = []
  let [y, m] = from.slice(0, 7).split('-').map(Number)
  const [ty, tm] = to.slice(0, 7).split('-').map(Number)
  while (y < ty || (y === ty && m <= tm)) {
    out.push(`${y}-${pad(m)}`)
    m++
    if (m > 12) { m = 1; y++ }
  }
  return out
}

/** 'YYYY-MM' 의 말일 */
export const monthEnd = (ym: string): string => {
  const [y, m] = ym.split('-').map(Number)
  return formatDay(Date.UTC(y, m, 0))
}

/** 주 시작일(월) 목록 (from 이 속한 주 ~ to 가 속한 주) */
export const weekRange = (from: string, to: string): string[] => {
  const out: string[] = []
  for (let d = weekStart(from); diffDays(d, to) >= 0; d = addDays(d, 7)) out.push(d)
  return out
}

/** 'MM/DD' */
export const shortDay = (s: string | null | undefined): string => (s ? `${s.slice(5, 7)}/${s.slice(8, 10)}` : '-')
