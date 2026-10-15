// docs/12 R2 mock 경로 — 일정·인력·산출물·추적성·품질·알림·대시보드
import type { Project } from '@/types/api'
import type {
  ActionItem, Assignment, AuditChecklistItem, ChangeRequest, ChecklistItem, Comment, CrStatus, Decision, Defect, DefectStatus, Deliverable,
  FieldHistory, GlobalSearchHit, HeatmapRow, HistoryEntry, Issue, Meeting, MemberScope, Methodology, MmPlanRow, MmSummaryRow, Notification,
  NotificationSettings, ProjectDashboard, ProjectPayload, ProjectProgress, QualityReport, Requirement, Risk, TestResultRow, TestRound,
  TestScenario, Timesheet, TraceLink, TraceRow, WbsItem, WeeklyReport, WeeklyReportSummary, WorkItem,
} from '@/types/r2'
import { NOTIFY_EVENTS } from '@/types/r2'
import { addDays, businessDaysBetween, diffDays, monthRange, weekRange, weekStart } from '@/utils/date'
import { allowedDefectTransitions, CR_TRANSITIONS } from '@/utils/quality'
import { hasDepCycle, renumber, rollup, treeOrder } from '@/utils/wbs'
import { MOCK_DATASETS } from '../fixtures/admin'
import { MOCK_PROJECTS } from '../fixtures/common'
import {
  AUDIT_CHECKLIST, BASELINES, CHANGE_REQUESTS, DECISIONS, DEFECTS, DELIVERABLES, ISSUES, MEETINGS, METHODOLOGIES, MOCK_TODAY, NOTIFICATIONS,
  PEOPLE, PORTFOLIO, PRJ, R2_DATASETS, REQUIREMENTS, RESOURCES, RISKS, TEST_ROUNDS, TEST_SCENARIOS, TRACE_LINKS, WBS, WEEKLY_PREV,
} from '../fixtures/r2'
import { err, mockId, paginate, tokenUser, type Route } from '../shared'

// R2 엑셀 데이터셋을 공통 레지스트리에 등록한다
R2_DATASETS.forEach((d) => { if (!MOCK_DATASETS.some((x) => x.datasetId === d.datasetId)) MOCK_DATASETS.push(d) })

// ─────────── mock 상태 (새로고침 시 초기화) ───────────
let wbs: WbsItem[] = rollup(renumber(structuredClone(WBS)))
const baselines = structuredClone(BASELINES)
const baselineSnap: Record<string, WbsItem[]> = { 'bl-1': structuredClone(wbs) }
const methodologies: Methodology[] = structuredClone(METHODOLOGIES)
const weekly: Record<string, WeeklyReport> = { 'wr-1': structuredClone(WEEKLY_PREV) }
const resources = structuredClone(RESOURCES)
const deliverables: Deliverable[] = structuredClone(DELIVERABLES)
const deliverableHistory: Record<string, HistoryEntry[]> = {
  'dl-01': [{ at: '2026-09-04T10:00:00+09:00', user: '관리자', action: 'REQUEST_REVIEW', comment: null }, { at: '2026-09-05T11:00:00+09:00', user: '최고객', action: 'APPROVE', comment: '승인합니다.' }],
  'dl-05': [{ at: '2026-10-13T15:00:00+09:00', user: '최고객', action: 'REJECT', comment: '보안 아키텍처 보완 필요' }],
}
const requirements: Requirement[] = structuredClone(REQUIREMENTS)
const reqHistory: Record<string, FieldHistory[]> = {
  'req-1': [{ at: '2026-10-02T10:00:00+09:00', user: '박관리', field: 'status', before: 'PROPOSED', after: 'ACCEPTED' }],
}
const traceLinks: TraceLink[] = structuredClone(TRACE_LINKS)
let auditChecklist: AuditChecklistItem[] = structuredClone(AUDIT_CHECKLIST)
const issues: Issue[] = structuredClone(ISSUES)
const risks: Risk[] = structuredClone(RISKS)
const decisions: Decision[] = structuredClone(DECISIONS)
const crs: ChangeRequest[] = structuredClone(CHANGE_REQUESTS)
const scenarios: TestScenario[] = structuredClone(TEST_SCENARIOS)
const rounds: TestRound[] = structuredClone(TEST_ROUNDS)
const results: Record<string, TestResultRow[]> = {
  'tr-1': [
    { caseId: 'tc-1-1', result: 'PASS', note: '' }, { caseId: 'tc-1-2', result: 'PASS', note: '' },
    { caseId: 'tc-1-3', result: 'FAIL', note: '미래 날짜 저장됨', defectId: 'def-1' }, { caseId: 'tc-1-4', result: 'PASS', note: '' },
  ],
  'tr-2': [{ caseId: 'tc-2-1', result: 'BLOCK', note: '발령 저장 오류로 진행 불가' }, { caseId: 'tc-3-1', result: 'FAIL', note: '' }, { caseId: 'tc-3-3', result: 'PASS', note: '' }],
}
const defects: Defect[] = structuredClone(DEFECTS)
const meetings: Meeting[] = structuredClone(MEETINGS)
const notifications: Notification[] = structuredClone(NOTIFICATIONS)
let notifySettings: NotificationSettings = {
  email: true, webhook: false,
  events: Object.fromEntries(NOTIFY_EVENTS.map((e) => [e, e !== 'MENTION'])) as NotificationSettings['events'],
}
const assignments: Assignment[] = []
const timesheets: Record<string, Timesheet> = {}
const checklists: Record<string, ChecklistItem[]> = {}
const scopes: Record<string, MemberScope> = {}
const counters = { issue: issues.length, risk: risks.length, cr: crs.length, defect: defects.length, req: requirements.length, tc: 30, meeting: meetings.length }

const MONTHS = monthRange('2026-09-01', '2027-08-31')
// 사람별 월 계획 MM (투입 곡선): 분석 인력은 앞쪽, 개발 인력은 가운데, QA·DBA 는 뒤쪽
const planFor = (userId: string, ym: string): number => {
  const i = MONTHS.indexOf(ym)
  if (i < 0) return 0
  const curve: Record<string, number[]> = {
    'u-admin': Array(12).fill(1), 'u-pm01': Array(12).fill(1), 'u-arch01': [1, 1, 1, 1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5],
    'u-pl': [0, 0.5, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0.5], 'u-analyst': [1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0.5, 0.5], 'u-analyst2': [0.5, 1, 1, 1, 0.5, 0, 0, 0, 0, 0, 0, 0],
    'u-dev01': [0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0.5, 0], 'u-dev02': [0, 0, 1, 1, 1, 1, 1, 1, 1, 0.5, 0, 0], 'u-dev03': [0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0.5, 0],
    'u-dba01': [0, 0.5, 1, 1, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 1, 1], 'u-qa01': [0, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 1, 1, 1, 1, 0.5], 'u-cust01': Array(12).fill(0),
  }
  return curve[userId]?.[i] ?? 0
}
let mmPlan: MmPlanRow[] = PEOPLE.filter((p) => p.roleId !== 'CUSTOMER').map((p) => ({
  userId: p.userId, name: p.name, grade: p.grade, roleId: p.roleId,
  months: Object.fromEntries(MONTHS.map((m) => [m, planFor(p.userId, m)])),
}))
// 다른 프로젝트 배정 (히트맵 과배정 재현)
const OTHER_PROJECT: Record<string, Record<string, number>> = {
  'u-pm01': { '2026-10': 40, '2026-11': 40, '2026-12': 20 },
  'u-arch01': { '2026-11': 50, '2026-12': 50, '2027-01': 30 },
  'u-dba01': { '2026-10': 30 },
}

// 샘플 작업일지
const seedTimesheet = (userId: string, ws: string, status: Timesheet['status'], userName: string): Timesheet => ({
  timesheetId: mockId('ts'), userId, userName, weekStart: ws, status, comment: null,
  entries: [0, 1, 2, 3, 4].flatMap((d) => [
    { date: addDays(ws, d), targetType: 'WBS' as const, targetId: 'w1-2-1', targetLabel: '1.2.1 인사시스템 As-Is 분석', hours: 6, note: null },
    { date: addDays(ws, d), targetType: 'ETC' as const, targetId: null, targetLabel: '주간회의·보고', hours: d === 0 ? 2 : 1, note: null },
  ]),
})
timesheets['u-admin|2026-10-12'] = { ...seedTimesheet('u-admin', '2026-10-12', 'DRAFT', '관리자'), entries: seedTimesheet('u-admin', '2026-10-12', 'DRAFT', '관리자').entries.slice(0, 6) }
timesheets['u-analyst|2026-10-05'] = seedTimesheet('u-analyst', '2026-10-05', 'SUBMITTED', '김분석')
timesheets['u-analyst2|2026-10-05'] = seedTimesheet('u-analyst2', '2026-10-05', 'SUBMITTED', '최분석')
timesheets['u-dba01|2026-10-05'] = seedTimesheet('u-dba01', '2026-10-05', 'SUBMITTED', '윤데이터')

const userRef = (userId: string | null | undefined) => {
  if (!userId) return null
  const p = PEOPLE.find((x) => x.userId === userId)
  return { userId, name: p?.name ?? userId }
}
const meName = (cfg: Parameters<Route[2]>[0]['config']) => {
  const login = tokenUser(cfg)
  return login === 'admin' ? '관리자' : login === 'analyst' ? '김분석' : login
}
const now = () => new Date().toISOString()
const pad4 = (n: number) => String(n).padStart(4, '0')

// ─────────── 진척 계산 (서버 로직 흉내) ───────────
const leaves = () => wbs.filter((i) => !wbs.some((c) => c.parentId === i.wbsId) && !i.milestone)
const totalWeight = () => leaves().reduce((s, i) => s + i.weight, 0) || 1
const plannedAt = (day: string) => {
  const ratio = (i: WbsItem) => {
    if (!i.planStart || !i.planEnd) return 0
    if (diffDays(day, i.planStart) > 0) return 0
    if (diffDays(i.planEnd, day) >= 0) return 1
    const all = businessDaysBetween(i.planStart, i.planEnd) || 1
    return businessDaysBetween(i.planStart, day) / all
  }
  return Math.round((leaves().reduce((s, i) => s + i.weight * ratio(i), 0) / totalWeight()) * 1000) / 10
}
const actualNow = () => Math.round((leaves().reduce((s, i) => s + i.weight * i.progress / 100, 0) / totalWeight()) * 1000) / 10

const progressOf = (): ProjectProgress => {
  const planned = plannedAt(MOCK_TODAY)
  const actual = actualNow()
  const sCurve = weekRange('2026-09-01', '2027-08-31').map((ws) => {
    const d = addDays(ws, 4)
    const p = plannedAt(d)
    const a = diffDays(d, MOCK_TODAY) >= 0 ? Math.round((planned ? (actual * p) / planned : 0) * 10) / 10 : null
    return { date: d, planned: p, actual: a }
  })
  sCurve.push({ date: MOCK_TODAY, planned, actual })
  sCurve.sort((a, b) => a.date.localeCompare(b.date))
  return {
    asOf: MOCK_TODAY, planned, actual, spi: planned ? Math.round((actual / planned) * 100) / 100 : null,
    byPhase: wbs.filter((i) => !i.parentId).map((ph) => {
      const kids = leaves().filter((l) => isUnder(l.wbsId, ph.wbsId))
      const w = kids.reduce((s, k) => s + k.weight, 0) || 1
      const pl = kids.reduce((s, k) => s + k.weight * (plannedAt(MOCK_TODAY) ? ratioAt(k, MOCK_TODAY) : 0), 0) / w
      return { wbsId: ph.wbsId, name: ph.name, planned: Math.round(pl * 1000) / 10, actual: ph.progress }
    }),
    sCurve,
  }
}
const ratioAt = (i: WbsItem, day: string) => {
  if (!i.planStart || !i.planEnd || diffDays(day, i.planStart) > 0) return 0
  if (diffDays(i.planEnd, day) >= 0) return 1
  return businessDaysBetween(i.planStart, day) / (businessDaysBetween(i.planStart, i.planEnd) || 1)
}
const isUnder = (id: string, ancestor: string): boolean => {
  let cur = wbs.find((i) => i.wbsId === id)
  let guard = 0
  while (cur?.parentId && guard++ < 20) {
    if (cur.parentId === ancestor) return true
    cur = wbs.find((i) => i.wbsId === cur?.parentId)
  }
  return false
}

const withBaseline = (items: WbsItem[]): WbsItem[] => {
  const latest = baselines[baselines.length - 1]
  const snap = latest ? baselineSnap[latest.baselineId] ?? [] : []
  return items.map((i) => {
    const b = snap.find((s) => s.wbsId === i.wbsId)
    return { ...i, baselineStart: b?.planStart ?? null, baselineEnd: b?.planEnd ?? null }
  })
}

// ─────────── 추적성·품질 집계 ───────────
const lastResultOf = (caseId: string): string | null => {
  for (const r of [...rounds].reverse()) {
    const hit = results[r.roundId]?.find((x) => x.caseId === caseId)
    if (hit && hit.result !== 'NOT_RUN') return hit.result
  }
  return null
}
const allCases = () => scenarios.flatMap((s) => s.cases)

const traceMatrix = (): TraceRow[] => requirements.map((r) => {
  const links = traceLinks.filter((l) => (l.fromType === 'REQ' && l.fromId === r.reqId) || (l.toType === 'REQ' && l.toId === r.reqId))
  const other = (l: TraceLink) => (l.fromType === 'REQ' && l.fromId === r.reqId ? { type: l.toType, id: l.toId, label: l.toLabel } : { type: l.fromType, id: l.fromId, label: l.fromLabel })
  const screens = links.map(other).filter((o) => o.type === 'SCREEN').map(({ id, label }) => ({ id, label }))
  const tests = allCases().filter((c) => c.reqIds.includes(r.reqId)).map((c) => ({ id: c.caseId, label: `${c.tcNo} ${c.title}`, lastResult: lastResultOf(c.caseId) }))
  const caseIds = new Set(tests.map((t) => t.id))
  const screenIds = new Set(screens.map((s) => s.id))
  const defs = defects.filter((d) => (d.caseId && caseIds.has(d.caseId)) || (d.screenId && screenIds.has(d.screenId)))
    .map((d) => ({ id: d.defectId, label: `${d.defectNo} ${d.title}`, status: d.status }))
  const crList = crs.filter((c) => c.reqIds.includes(r.reqId)).map((c) => ({ id: c.crId, label: `${c.crNo} ${c.title}` }))
  const gaps: TraceRow['gaps'] = []
  if (!screens.length && r.status !== 'DROPPED') gaps.push('NO_SCREEN')
  if (!tests.length && r.status !== 'DROPPED') gaps.push('NO_TEST')
  if (tests.some((t) => t.lastResult === 'FAIL')) gaps.push('TEST_FAILED')
  if (defs.some((d) => d.status !== 'CLOSED')) gaps.push('OPEN_DEFECT')
  return { req: { reqId: r.reqId, reqNo: r.reqNo, title: r.title, status: r.status }, screens, tests, defects: defs, crs: crList, gaps }
})

const qualityReport = (): QualityReport => {
  const days = Array.from({ length: 21 }, (_, i) => addDays(MOCK_TODAY, i - 20))
  const trend = days.map((d) => {
    const opened = defects.filter((x) => x.createdAt.slice(0, 10) === d).length
    const closed = defects.filter((x) => x.status === 'CLOSED' && x.createdAt.slice(0, 10) < d && (Number(x.defectId.split('-')[1]) % 3 === 0) && d === addDays(x.createdAt.slice(0, 10), 3)).length
    const open = defects.filter((x) => x.createdAt.slice(0, 10) <= d && !(x.status === 'CLOSED' && addDays(x.createdAt.slice(0, 10), 3) <= d)).length
    return { date: d, opened, closed, open }
  })
  const count = (key: 'severity' | 'status') => defects.reduce<Record<string, number>>((a, d) => { a[d[key]] = (a[d[key]] ?? 0) + 1; return a }, {})
  const screenCount = defects.filter((d) => d.screenId).reduce<Record<string, number>>((a, d) => { a[d.screenId as string] = (a[d.screenId as string] ?? 0) + 1; return a }, {})
  const SCREEN_NAMES: Record<string, string> = { 'scr-hr0101': '사원관리', 'scr-hr0103': '발령관리', 'scr-hr0104': '부서관리', 'scr-hr0202': '근태승인' }
  return {
    defectTrend: trend,
    bySeverity: count('severity'),
    byStatus: count('status'),
    reopenRate: Math.round((defects.filter((d) => d.status === 'REOPENED').length / (defects.length || 1)) * 100) / 100,
    testProgress: rounds.map((r) => {
      const list = results[r.roundId] ?? []
      const cases = allCases().filter((c) => scenarios.find((s) => s.cases.includes(c))?.level === r.level)
      const total = Math.max(cases.length, list.length)
      const c = (res: string) => list.filter((x) => x.result === res).length
      return { roundId: r.roundId, name: r.name, total, pass: c('PASS'), fail: c('FAIL'), block: c('BLOCK'), notRun: total - c('PASS') - c('FAIL') - c('BLOCK') }
    }),
    topScreens: Object.entries(screenCount).sort((a, b) => b[1] - a[1]).slice(0, 10).map(([screenId, n]) => ({ screenId, name: SCREEN_NAMES[screenId] ?? screenId, defects: n })),
  }
}

const dashboard = (): ProjectDashboard => {
  const progress = progressOf()
  const month = MOCK_TODAY.slice(0, 7)
  return {
    progress,
    openIssues: issues.filter((i) => i.status === 'OPEN' || i.status === 'IN_PROGRESS').length,
    overdueIssues: issues.filter((i) => (i.status === 'OPEN' || i.status === 'IN_PROGRESS') && i.dueDate && i.dueDate < MOCK_TODAY).length,
    risksTop: [...risks].filter((r) => r.status !== 'CLOSED').sort((a, b) => b.score - a.score).slice(0, 5),
    defects: { open: defects.filter((d) => d.status !== 'CLOSED').length, critical: defects.filter((d) => d.status !== 'CLOSED' && d.severity === 'CRITICAL').length },
    mm: { planned: mmPlan.reduce((s, r) => s + (r.months[month] ?? 0), 0), actual: Math.round(mmPlan.reduce((s, r) => s + (r.months[month] ?? 0), 0) * 0.92 * 10) / 10 },
    deliverables: deliverables.reduce<Record<string, number>>((a, d) => { a[d.status] = (a[d.status] ?? 0) + 1; return a }, {}),
    analysis: { done: 7, total: 21 },
    milestones: wbs.filter((i) => i.milestone).map((i) => ({ name: i.name, planEnd: i.planEnd ?? '', status: i.status })),
  }
}

const heatmap = (from: string, to: string, unit: 'month' | 'week'): HeatmapRow[] => mmPlan.map((r) => {
  const other = OTHER_PROJECT[r.userId] ?? {}
  if (unit === 'month') {
    return {
      userId: r.userId, name: r.name,
      cells: Object.fromEntries(monthRange(from, to).map((m) => [m, Math.round((r.months[m] ?? 0) * 100 + (other[m] ?? 0))])),
    }
  }
  return {
    userId: r.userId, name: r.name,
    cells: Object.fromEntries(weekRange(from, to).map((ws) => {
      const m = ws.slice(0, 7)
      const extra = assignments.filter((a) => a.userId === r.userId).reduce((s, a) => s + a.allocPct, 0)
      return [ws, Math.round((r.months[m] ?? 0) * 100 + (other[m] ?? 0) + extra)]
    })),
  }
})

const unassigned = (type?: string): WorkItem[] => {
  const out: WorkItem[] = []
  if (!type || type === 'WBS') {
    leaves().filter((i) => !i.assignees.length && !assignments.some((a) => a.targetType === 'WBS' && a.targetId === i.wbsId)).forEach((i) => {
      const parent = wbs.find((p) => p.wbsId === i.parentId)
      out.push({ type: 'WBS', id: i.wbsId, label: `${i.wbsNo} ${i.name}`, path: parent?.name ?? '', planStart: i.planStart, planEnd: i.planEnd })
    })
  }
  if (!type || type === 'SCREEN') {
    [['scr-hr0101p01', 'HR0101P01 사원검색 (팝업)'], ['scr-hr0202', 'HR0202 근태승인'], ['scr-hr0301', 'HR0301 교육과정관리'], ['scr-hr0302', 'HR0302 수강신청']]
      .filter(([id]) => !assignments.some((a) => a.targetType === 'SCREEN' && a.targetId === id))
      .forEach(([id, label]) => out.push({ type: 'SCREEN', id, label, path: '인사시스템 As-Is 분석', planStart: '2026-10-12', planEnd: '2026-10-23' }))
  }
  return out
}

const search = (q: string): GlobalSearchHit[] => {
  const needle = q.trim().toLowerCase()
  if (!needle) return []
  const hit = (type: GlobalSearchHit['type'], id: string, label: string, sub: string): GlobalSearchHit => ({ type, id, label, sub, projectId: PRJ })
  const has = (...s: (string | null)[]) => s.some((x) => x?.toLowerCase().includes(needle))
  return [
    ...requirements.filter((r) => has(r.reqNo, r.title)).map((r) => hit('REQ', r.reqId, `${r.reqNo} ${r.title}`, '요구사항')),
    ...wbs.filter((w) => has(w.wbsNo, w.name)).map((w) => hit('WBS', w.wbsId, `${w.wbsNo} ${w.name}`, 'WBS')),
    ...issues.filter((i) => has(i.issueNo, i.title)).map((i) => hit('ISSUE', i.issueId, `${i.issueNo} ${i.title}`, '이슈')),
    ...defects.filter((d) => has(d.defectNo, d.title)).map((d) => hit('DEFECT', d.defectId, `${d.defectNo} ${d.title}`, '결함')),
    ...crs.filter((c) => has(c.crNo, c.title)).map((c) => hit('CR', c.crId, `${c.crNo} ${c.title}`, '변경요청')),
    ...deliverables.filter((d) => has(d.name)).map((d) => hit('DELIVERABLE', d.deliverableId, d.name, `산출물 · ${d.phase}`)),
    ...meetings.filter((m) => has(m.meetingNo, m.title)).map((m) => hit('MEETING', m.meetingId, `${m.meetingNo} ${m.title}`, '회의록')),
    ...allCases().filter((c) => has(c.tcNo, c.title)).map((c) => hit('TC', c.caseId, `${c.tcNo} ${c.title}`, '테스트 케이스')),
  ].slice(0, 50)
}

const weeklyDraft = (ws: string): WeeklyReport => {
  const we = addDays(ws, 4)
  const nextWs = addDays(ws, 7)
  const nextWe = addDays(ws, 11)
  const overlaps = (i: WbsItem, a: string, b: string) => !!i.planStart && !!i.planEnd && i.planStart <= b && i.planEnd >= a
  const p = progressOf()
  return {
    reportId: mockId('wr'), projectId: PRJ, weekStart: ws, status: 'DRAFT', summary: '',
    progress: { planned: plannedAt(we), actual: p.actual, prevActual: weekly['wr-1']?.progress.actual ?? 0 },
    thisWeek: leaves().filter((i) => overlaps(i, ws, we)).map((i) => ({ wbsNo: i.wbsNo, name: i.name, assignees: i.assignees.map((a) => a.name).join(', '), progress: i.progress, done: i.progress === 100, note: '' })),
    nextWeek: leaves().filter((i) => overlaps(i, nextWs, nextWe) && i.progress < 100).map((i) => ({ wbsNo: i.wbsNo, name: i.name, assignees: i.assignees.map((a) => a.name).join(', '), planEnd: i.planEnd ?? '', note: '' })),
    issues: issues.filter((i) => i.status === 'OPEN' || i.status === 'IN_PROGRESS').map((i) => ({ no: i.issueNo, title: i.title, status: i.status, owner: i.owner?.name ?? '', dueDate: i.dueDate })),
    risks: risks.filter((r) => r.status !== 'CLOSED' && r.score >= 9).map((r) => ({ no: r.riskNo, title: r.title, score: r.score, response: r.response })),
    mm: { planned: Math.round(mmPlan.reduce((s, r) => s + (r.months[ws.slice(0, 7)] ?? 0), 0) / 4.3 * 10) / 10, actual: 2.4 },
    analysis: { done: 7, total: 21 },
  }
}

const findCase = (caseId: string) => {
  for (const s of scenarios) {
    const idx = s.cases.findIndex((c) => c.caseId === caseId)
    if (idx >= 0) return { s, idx }
  }
  return null
}

const pushNotification = (event: Notification['event'], title: string, body: string, link: string | null) => {
  notifications.unshift({ notificationId: mockId('ntf'), event, title, body, link, read: false, createdAt: now() })
}

const P = '^\\/api\\/v1'
const re = (s: string) => new RegExp(`${P}${s}$`)

export const r2Routes: Route[] = [
  /* ── 프로젝트·방법론 ── */
  ['post', re('\\/projects'), ({ body }) => {
    const p = body as ProjectPayload
    const created: Project = {
      projectId: mockId('prj'), type: p.type, name: p.name, customer: p.customer, startDate: p.startDate, endDate: p.endDate,
      contractMm: p.contractMm, pmUserId: p.pmUserId, pmName: PEOPLE.find((x) => x.userId === p.pmUserId)?.name ?? p.pmUserId,
    }
    MOCK_PROJECTS.push(created)
    return [201, created]
  }],
  ['put', re('\\/projects\\/([^/]+)'), ({ params, body }) => {
    const target = MOCK_PROJECTS.find((x) => x.projectId === params[0])
    if (!target) return err(404, 'NOT_FOUND', '프로젝트를 찾을 수 없습니다.')
    const p = body as ProjectPayload
    Object.assign(target, {
      type: p.type, name: p.name, customer: p.customer, startDate: p.startDate, endDate: p.endDate,
      contractMm: p.contractMm, pmUserId: p.pmUserId, pmName: PEOPLE.find((x) => x.userId === p.pmUserId)?.name ?? p.pmUserId,
    })
    return [200, target]
  }],
  ['get', re('\\/methodologies'), () => [200, methodologies]],
  ['post', re('\\/methodologies'), ({ body }) => {
    const m = { ...(body as Omit<Methodology, 'methodologyId'>), methodologyId: mockId('mtd') }
    methodologies.push(m)
    return [201, m]
  }],
  ['put', re('\\/methodologies\\/([^/]+)'), ({ params, body }) => {
    const i = methodologies.findIndex((m) => m.methodologyId === params[0])
    if (i < 0) return err(404, 'NOT_FOUND', '방법론을 찾을 수 없습니다.')
    methodologies[i] = { ...(body as Methodology), methodologyId: params[0] }
    return [200, methodologies[i]]
  }],
  ['delete', re('\\/methodologies\\/([^/]+)'), ({ params }) => {
    const i = methodologies.findIndex((m) => m.methodologyId === params[0])
    if (i >= 0) methodologies.splice(i, 1)
    return [204, null]
  }],

  /* ── WBS ── */
  ['get', re('\\/projects\\/([^/]+)\\/wbs'), () => [200, withBaseline(treeOrder(wbs))]],
  ['put', re('\\/projects\\/([^/]+)\\/wbs'), ({ body }) => {
    const items = (body as WbsItem[]).map((i) => ({ ...i, wbsId: i.wbsId || mockId('w') }))
    // 화면 임시 ID → 서버 ID 로 바뀐 경우 선후행·부모 참조도 함께 바꾼다
    const idMap = new Map((body as WbsItem[]).map((i, idx) => [i.wbsId, items[idx].wbsId]))
    const fixed = items.map((i) => ({ ...i, parentId: i.parentId ? idMap.get(i.parentId) ?? i.parentId : null, deps: i.deps.map((d) => ({ ...d, predId: idMap.get(d.predId) ?? d.predId })) }))
    if (hasDepCycle(fixed)) return err(400, 'WBS_CYCLE', '선후행 관계에 순환이 있습니다.')
    wbs = rollup(renumber(fixed))
    return [200, withBaseline(treeOrder(wbs))]
  }],
  ['patch', re('\\/wbs\\/([^/]+)'), ({ params, body }) => {
    const i = wbs.findIndex((w) => w.wbsId === params[0])
    if (i < 0) return err(404, 'NOT_FOUND', 'WBS 항목을 찾을 수 없습니다.')
    const { assigneeIds, ...rest } = body as Partial<WbsItem> & { assigneeIds?: string[] }
    wbs[i] = { ...wbs[i], ...rest, ...(assigneeIds ? { assignees: assigneeIds.map((id) => userRef(id) as { userId: string; name: string }) } : {}) }
    if (rest.status === 'DONE') wbs[i].progress = 100
    wbs = rollup(wbs)
    return [200, withBaseline(wbs).find((w) => w.wbsId === params[0])]
  }],
  ['post', re('\\/projects\\/([^/]+)\\/wbs\\/generate'), ({ body }) => {
    const { methodologyId, startDate, replace } = body as { methodologyId: string; startDate: string; replace: boolean }
    const m = methodologies.find((x) => x.methodologyId === methodologyId)
    if (!m) return err(404, 'NOT_FOUND', '방법론을 찾을 수 없습니다.')
    const out: WbsItem[] = replace ? [] : [...wbs]
    let cursor = startDate
    const blank = (wbsId: string, parentId: string | null, name: string, order: number, s: string, e: string, weight: number): WbsItem => ({
      wbsId, parentId, wbsNo: '', name, order, planStart: s, planEnd: e, actStart: null, actEnd: null, weight, progress: 0, milestone: false,
      status: 'TODO', assignees: [], deliverableId: null, deps: [], critical: false, baselineStart: null, baselineEnd: null,
    })
    const base = out.filter((i) => !i.parentId).length
    m.phases.forEach((ph, pi) => {
      const pid = mockId('w')
      const phaseStart = cursor
      out.push(blank(pid, null, ph.name, base + pi, phaseStart, phaseStart, 1))
      ph.tasks.forEach((tk, ti) => {
        const end = addDays(cursor, Math.max(0, Math.round(tk.durationDays * 1.4) - 1))
        out.push(blank(mockId('w'), pid, tk.name, ti, cursor, end, tk.weight))
        cursor = addDays(end, 1)
      })
    })
    wbs = rollup(renumber(out))
    return [200, withBaseline(treeOrder(wbs))]
  }],
  ['post', re('\\/projects\\/([^/]+)\\/wbs\\/import-msproject'), ({ body }) => {
    const file = body instanceof FormData ? body.get('file') : null
    if (!(file instanceof File) || !/\.xml$/i.test(file.name)) return err(400, 'VALIDATION', 'MS Project XML(.xml) 파일만 가져올 수 있습니다.')
    return [200, withBaseline(treeOrder(wbs))]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/baselines'), () => [200, baselines]],
  ['post', re('\\/projects\\/([^/]+)\\/baselines'), ({ body, config }) => {
    const b = { baselineId: mockId('bl'), name: String((body as { name: string }).name), createdAt: now(), createdBy: meName(config) }
    baselines.push(b)
    baselineSnap[b.baselineId] = structuredClone(wbs)
    return [201, b]
  }],
  ['get', re('\\/baselines\\/([^/]+)\\/compare'), ({ params }) => {
    const snap = baselineSnap[params[0]]
    if (!snap) return err(404, 'NOT_FOUND', '베이스라인을 찾을 수 없습니다.')
    return [200, treeOrder(wbs).map((i) => {
      const b = snap.find((s) => s.wbsId === i.wbsId)
      const v = (x: string | null, y: string | null | undefined) => (x && y ? diffDays(y, x) : null)
      return { wbsId: i.wbsId, wbsNo: i.wbsNo, name: i.name, planStart: i.planStart, planEnd: i.planEnd, baseStart: b?.planStart ?? null, baseEnd: b?.planEnd ?? null, startVarDays: v(i.planStart, b?.planStart), endVarDays: v(i.planEnd, b?.planEnd) }
    })]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/progress'), () => [200, progressOf()]],

  /* ── 주간보고 ── */
  ['get', re('\\/projects\\/([^/]+)\\/weekly-reports'), () =>
    [200, Object.values(weekly).sort((a, b) => b.weekStart.localeCompare(a.weekStart)).map((w): WeeklyReportSummary => ({ reportId: w.reportId, weekStart: w.weekStart, status: w.status, updatedAt: '2026-10-09T17:00:00+09:00' }))]],
  ['post', re('\\/projects\\/([^/]+)\\/weekly-reports\\/generate'), ({ body }) => {
    const ws = weekStart(String((body as { weekStart: string }).weekStart))
    if (Object.values(weekly).some((w) => w.weekStart === ws)) return err(409, 'CONFLICT', '이미 해당 주 보고서가 있습니다.')
    const r = weeklyDraft(ws)
    weekly[r.reportId] = r
    return [201, r]
  }],
  ['get', re('\\/weekly-reports\\/([^/]+)'), ({ params }) => (weekly[params[0]] ? [200, weekly[params[0]]] : err(404, 'NOT_FOUND', '주간보고를 찾을 수 없습니다.'))],
  ['put', re('\\/weekly-reports\\/([^/]+)'), ({ params, body }) => {
    if (!weekly[params[0]]) return err(404, 'NOT_FOUND', '주간보고를 찾을 수 없습니다.')
    if (weekly[params[0]].status === 'SUBMITTED') return err(409, 'CONFLICT', '제출된 보고서는 수정할 수 없습니다.')
    weekly[params[0]] = { ...(body as WeeklyReport), reportId: params[0], status: 'DRAFT' }
    return [200, weekly[params[0]]]
  }],
  ['post', re('\\/weekly-reports\\/([^/]+)\\/submit'), ({ params }) => {
    if (!weekly[params[0]]) return err(404, 'NOT_FOUND', '주간보고를 찾을 수 없습니다.')
    weekly[params[0]].status = 'SUBMITTED'
    return [200, weekly[params[0]]]
  }],

  /* ── 인력 ── */
  ['get', re('\\/resources'), ({ config }) => {
    const q = String(config.params?.q ?? '').toLowerCase()
    const skill = String(config.params?.skill ?? '').toLowerCase()
    const list = resources.filter((r) => (!q || `${r.name} ${r.company}`.toLowerCase().includes(q))
      && (!skill || r.skills.some((s) => s.skill.toLowerCase().includes(skill)))
      && (!config.params?.orgType || r.orgType === config.params.orgType)
      && (!config.params?.grade || r.grade === config.params.grade))
    return [200, paginate(list, config.params)]
  }],
  ['put', re('\\/resources\\/([^/]+)'), ({ params, body }) => {
    const r = resources.find((x) => x.userId === params[0])
    if (!r) return err(404, 'NOT_FOUND', '인력을 찾을 수 없습니다.')
    Object.assign(r, body)
    return [200, r]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/mm-plan'), () => [200, mmPlan]],
  ['put', re('\\/projects\\/([^/]+)\\/mm-plan'), ({ body }) => {
    const rows = body as MmPlanRow[]
    if (rows.some((r) => Object.values(r.months).some((v) => v < 0 || v > 1.5))) return err(400, 'VALIDATION', '월 MM 은 0 ~ 1.5 사이로 입력하세요.')
    mmPlan = rows
    return [200, mmPlan]
  }],
  ['get', re('\\/resources\\/heatmap'), ({ config }) =>
    [200, heatmap(String(config.params?.from), String(config.params?.to), config.params?.unit === 'week' ? 'week' : 'month')]],
  ['get', re('\\/projects\\/([^/]+)\\/work-items\\/unassigned'), ({ config }) => [200, unassigned(config.params?.type)]],
  ['post', re('\\/assignments'), ({ body }) => {
    const p = body as { targetType: string; targetId: string; userId: string; allocPct: number }
    const curr = heatmap('2026-10-12', '2026-10-18', 'week').find((h) => h.userId === p.userId)
    const total = (curr ? Object.values(curr.cells)[0] : 0) + p.allocPct
    const a: Assignment = { assignmentId: mockId('as'), ...p, warning: total > 100 ? `2026-W42 배정률 ${total}%` : null }
    assignments.push(a)
    if (p.targetType === 'WBS') {
      const w = wbs.find((x) => x.wbsId === p.targetId)
      if (w && !w.assignees.some((x) => x.userId === p.userId)) w.assignees.push(userRef(p.userId) as { userId: string; name: string })
    }
    pushNotification('ASSIGNED', '작업이 배정되었습니다', `${p.targetType} ${p.targetId}`, null)
    return [201, a]
  }],
  ['delete', re('\\/assignments\\/([^/]+)'), ({ params }) => {
    const i = assignments.findIndex((a) => a.assignmentId === params[0])
    if (i >= 0) assignments.splice(i, 1)
    return [204, null]
  }],
  ['get', re('\\/timesheets'), ({ config }) => {
    const key = `${config.params?.userId}|${config.params?.weekStart}`
    return [200, timesheets[key] ?? { timesheetId: null, userId: config.params?.userId, weekStart: config.params?.weekStart, status: 'DRAFT', comment: null, entries: [] }]
  }],
  ['put', re('\\/timesheets'), ({ body }) => {
    const ts = body as Timesheet
    const key = `${ts.userId}|${ts.weekStart}`
    const prev = timesheets[key]
    if (prev && (prev.status === 'SUBMITTED' || prev.status === 'APPROVED')) return err(409, 'CONFLICT', '제출·승인된 작업일지는 수정할 수 없습니다.')
    if (ts.entries.some((e) => e.hours < 0 || e.hours > 24)) return err(400, 'VALIDATION', '작업 시간은 0 ~ 24 사이로 입력하세요.')
    timesheets[key] = { ...ts, timesheetId: prev?.timesheetId ?? mockId('ts'), status: prev?.status === 'REJECTED' ? 'REJECTED' : 'DRAFT' }
    return [200, timesheets[key]]
  }],
  ['post', re('\\/timesheets\\/([^/]+)\\/(submit|approve|reject)'), ({ params, body }) => {
    const ts = Object.values(timesheets).find((x) => x.timesheetId === params[0])
    if (!ts) return err(404, 'NOT_FOUND', '작업일지를 찾을 수 없습니다.')
    const action = params[1]
    if (action === 'submit') {
      if (!ts.entries.length) return err(400, 'VALIDATION', '입력된 작업이 없습니다.')
      ts.status = 'SUBMITTED'
    } else if (ts.status !== 'SUBMITTED') {
      return err(409, 'CONFLICT', '제출된 작업일지만 승인·반려할 수 있습니다.')
    } else {
      ts.status = action === 'approve' ? 'APPROVED' : 'REJECTED'
      ts.comment = (body as { comment?: string })?.comment ?? null
      if (action === 'reject') pushNotification('TIMESHEET_REJECTED', '작업일지 반려', `${ts.weekStart} 주 작업일지가 반려되었습니다.`, '/resource/timesheet')
    }
    return [200, ts]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/timesheets\\/pending'), () => [200, Object.values(timesheets).filter((t) => t.status === 'SUBMITTED')]],
  ['get', re('\\/projects\\/([^/]+)\\/mm-summary'), ({ config }) => {
    const months = monthRange(String(config.params?.from ?? '2026-09'), String(config.params?.to ?? '2027-08'))
    return [200, mmPlan.map((r): MmSummaryRow => {
      const p = PEOPLE.find((x) => x.userId === r.userId)
      return {
        userId: r.userId, name: r.name, company: p?.company ?? '', orgType: p?.orgType ?? '', grade: r.grade,
        plan: Object.fromEntries(months.map((m) => [m, r.months[m] ?? 0])),
        actual: Object.fromEntries(months.filter((m) => m <= MOCK_TODAY.slice(0, 7)).map((m) => [m, Math.round((r.months[m] ?? 0) * (m === MOCK_TODAY.slice(0, 7) ? 0.45 : 0.95 + (r.userId.length % 3) * 0.03) * 100) / 100])),
      }
    })]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/members\\/([^/]+)\\/checklist'), ({ params }) => {
    const key = `${params[0]}|${params[1]}`
    checklists[key] ??= ['보안서약서 제출', '출입증 발급', '개발 계정 발급', '장비(PC) 지급', '개인정보 보호 교육 이수'].map((item, i) => ({ item, done: i < 2, doneAt: i < 2 ? '2026-09-02T10:00:00+09:00' : null }))
    return [200, checklists[key]]
  }],
  ['put', re('\\/projects\\/([^/]+)\\/members\\/([^/]+)\\/checklist'), ({ params, body }) => {
    checklists[`${params[0]}|${params[1]}`] = (body as ChecklistItem[]).map((c) => ({ ...c, doneAt: c.done ? c.doneAt ?? now() : null }))
    return [200, checklists[`${params[0]}|${params[1]}`]]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/members\\/([^/]+)\\/scope'), ({ params }) =>
    [200, scopes[`${params[0]}|${params[1]}`] ?? { systemIds: [], menuIds: [] }]],
  ['put', re('\\/projects\\/([^/]+)\\/members\\/([^/]+)\\/scope'), ({ params, body }) => {
    scopes[`${params[0]}|${params[1]}`] = body as MemberScope
    return [200, body]
  }],

  /* ── 산출물 대장 (프로젝트) — As-Is 산출물 출력 경로(/systems/.../deliverables)와 구분 ── */
  ['get', re('\\/projects\\/([^/]+)\\/deliverables'), ({ config }) =>
    [200, deliverables.filter((d) => (!config.params?.phase || d.phase === config.params.phase) && (!config.params?.status || d.status === config.params.status))]],
  ['post', re('\\/projects\\/([^/]+)\\/deliverables'), ({ body }) => {
    const p = body as { phase: string; name: string; ownerId: string | null; dueDate: string | null; customerApproval: boolean; wbsId: string | null }
    const d: Deliverable = { deliverableId: mockId('dl'), phase: p.phase, name: p.name, owner: userRef(p.ownerId), dueDate: p.dueDate, status: 'DRAFT', customerApproval: p.customerApproval, wbsId: p.wbsId, fileCount: 0, updatedAt: now() }
    deliverables.push(d)
    return [201, d]
  }],
  ['put', re('\\/deliverables\\/([^/]+)'), ({ params, body }) => {
    const d = deliverables.find((x) => x.deliverableId === params[0])
    if (!d) return err(404, 'NOT_FOUND', '산출물을 찾을 수 없습니다.')
    const p = body as { phase: string; name: string; ownerId: string | null; dueDate: string | null; customerApproval: boolean; wbsId: string | null }
    Object.assign(d, { phase: p.phase, name: p.name, owner: userRef(p.ownerId), dueDate: p.dueDate, customerApproval: p.customerApproval, wbsId: p.wbsId, updatedAt: now() })
    return [200, d]
  }],
  ['delete', re('\\/deliverables\\/([^/]+)'), ({ params }) => {
    const i = deliverables.findIndex((x) => x.deliverableId === params[0])
    if (i >= 0) deliverables.splice(i, 1)
    return [204, null]
  }],
  ['post', re('\\/deliverables\\/([^/]+)\\/(request-review|approve|reject)'), ({ params, body, config }) => {
    const d = deliverables.find((x) => x.deliverableId === params[0])
    if (!d) return err(404, 'NOT_FOUND', '산출물을 찾을 수 없습니다.')
    const comment = (body as { comment?: string })?.comment ?? null
    const action = params[1]
    if (action === 'request-review') {
      if (d.status === 'APPROVED') return err(409, 'CONFLICT', '이미 승인된 산출물입니다.')
      d.status = 'REVIEW'
    } else {
      if (d.status !== 'REVIEW') return err(409, 'CONFLICT', '검토 중인 산출물만 승인·반려할 수 있습니다.')
      if (action === 'reject' && !comment) return err(400, 'VALIDATION', '반려 사유를 입력하세요.', { comment: '반려 사유를 입력하세요.' })
      d.status = action === 'approve' ? 'APPROVED' : 'REJECTED'
      if (action === 'approve' && d.wbsId) {
        const w = wbs.find((x) => x.wbsId === d.wbsId)
        if (w) { w.progress = 100; w.status = 'DONE'; wbs = rollup(wbs) }
      }
    }
    d.updatedAt = now();
    (deliverableHistory[d.deliverableId] ??= []).push({ at: now(), user: meName(config), action: action.toUpperCase().replace('-', '_'), comment })
    return [200, d]
  }],
  ['get', re('\\/deliverables\\/([^/]+)\\/history'), ({ params }) => [200, deliverableHistory[params[0]] ?? []]],

  /* ── 요구사항·추적 ── */
  ['get', re('\\/projects\\/([^/]+)\\/requirements'), ({ config }) => {
    const q = String(config.params?.q ?? '').toLowerCase()
    const list = requirements.filter((r) => (!q || `${r.reqNo} ${r.title} ${r.category}`.toLowerCase().includes(q)) && (!config.params?.status || r.status === config.params.status))
    return [200, paginate(list, config.params)]
  }],
  ['post', re('\\/projects\\/([^/]+)\\/requirements'), ({ body }) => {
    counters.req++
    const r: Requirement = { ...(body as Requirement), reqId: mockId('req'), reqNo: `REQ-${pad4(counters.req)}`, updatedAt: now() }
    requirements.push(r)
    return [201, r]
  }],
  ['put', re('\\/requirements\\/([^/]+)'), ({ params, body, config }) => {
    const r = requirements.find((x) => x.reqId === params[0])
    if (!r) return err(404, 'NOT_FOUND', '요구사항을 찾을 수 없습니다.')
    const next = body as Requirement
    const hist = (reqHistory[r.reqId] ??= [])
    ;(['title', 'status', 'priority', 'category', 'source'] as const).forEach((f) => {
      if (String(r[f] ?? '') !== String(next[f] ?? '')) hist.unshift({ at: now(), user: meName(config), field: f, before: (r[f] as string) ?? null, after: (next[f] as string) ?? null })
    })
    Object.assign(r, { ...next, reqId: r.reqId, reqNo: r.reqNo, updatedAt: now() })
    return [200, r]
  }],
  ['delete', re('\\/requirements\\/([^/]+)'), ({ params }) => {
    const i = requirements.findIndex((x) => x.reqId === params[0])
    if (i >= 0) requirements.splice(i, 1)
    return [204, null]
  }],
  ['get', re('\\/requirements\\/([^/]+)\\/history'), ({ params }) => [200, reqHistory[params[0]] ?? []]],
  ['get', re('\\/trace-links'), ({ config }) => {
    const { type, id } = config.params ?? {}
    return [200, traceLinks.filter((l) => (l.fromType === type && l.fromId === id) || (l.toType === type && l.toId === id))]
  }],
  ['post', re('\\/trace-links'), ({ body }) => {
    const p = body as TraceLink
    if (traceLinks.some((l) => l.fromType === p.fromType && l.fromId === p.fromId && l.toType === p.toType && l.toId === p.toId)) return err(409, 'CONFLICT', '이미 연결되어 있습니다.')
    const label = (type: string, id: string) => {
      if (type === 'REQ') { const r = requirements.find((x) => x.reqId === id); return r ? `${r.reqNo} ${r.title}` : id }
      if (type === 'TC') { const c = allCases().find((x) => x.caseId === id); return c ? `${c.tcNo} ${c.title}` : id }
      if (type === 'DEFECT') { const d = defects.find((x) => x.defectId === id); return d ? `${d.defectNo} ${d.title}` : id }
      if (type === 'CR') { const c = crs.find((x) => x.crId === id); return c ? `${c.crNo} ${c.title}` : id }
      if (type === 'ISSUE') { const c = issues.find((x) => x.issueId === id); return c ? `${c.issueNo} ${c.title}` : id }
      if (type === 'WBS') { const w = wbs.find((x) => x.wbsId === id); return w ? `${w.wbsNo} ${w.name}` : id }
      if (type === 'SCREEN') return id.replace('scr-', '').toUpperCase()
      return id
    }
    const l: TraceLink = { linkId: mockId('lnk'), fromType: p.fromType, fromId: p.fromId, fromLabel: label(p.fromType, p.fromId), toType: p.toType, toId: p.toId, toLabel: label(p.toType, p.toId), linkType: p.linkType ?? null }
    traceLinks.push(l)
    return [201, l]
  }],
  ['delete', re('\\/trace-links\\/([^/]+)'), ({ params }) => {
    const i = traceLinks.findIndex((x) => x.linkId === params[0])
    if (i >= 0) traceLinks.splice(i, 1)
    return [204, null]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/trace-matrix'), () => [200, traceMatrix()]],
  ['get', re('\\/projects\\/([^/]+)\\/audit-checklist'), () => [200, auditChecklist]],
  ['put', re('\\/projects\\/([^/]+)\\/audit-checklist'), ({ body }) => { auditChecklist = body as AuditChecklistItem[]; return [200, auditChecklist] }],
  ['get', re('\\/elements\\/([^/]+)\\/links'), ({ params }) => [200, {
    links: traceLinks.filter((l) => (l.fromType === 'ELEMENT' && l.fromId === params[0]) || (l.toType === 'ELEMENT' && l.toId === params[0])),
    defects: defects.filter((d) => d.elementId === params[0]).map(({ defectId, defectNo, title, status, severity }) => ({ defectId, defectNo, title, status, severity })),
    issues: issues.filter((i) => i.elementId === params[0]).map(({ issueId, issueNo, title, status, priority }) => ({ issueId, issueNo, title, status, priority })),
  }]],

  /* ── 이슈 ── */
  ['get', re('\\/projects\\/([^/]+)\\/issues'), ({ config }) => {
    const q = String(config.params?.q ?? '').toLowerCase()
    const list = issues.filter((i) => (!config.params?.status || i.status === config.params.status) && (!q || `${i.issueNo} ${i.title}`.toLowerCase().includes(q)))
      .map(({ comments: _c, ...rest }) => rest)
    return [200, paginate(list, config.params)]
  }],
  ['get', re('\\/issues\\/([^/]+)'), ({ params }) => {
    const i = issues.find((x) => x.issueId === params[0])
    return i ? [200, i] : err(404, 'NOT_FOUND', '이슈를 찾을 수 없습니다.')
  }],
  ['post', re('\\/projects\\/([^/]+)\\/issues'), ({ body }) => {
    const p = body as { title: string; description: string; priority: Issue['priority']; ownerId: string | null; dueDate: string | null; elementId?: string | null }
    if (!p.title?.trim()) return err(400, 'VALIDATION', '제목을 입력하세요.', { title: '제목을 입력하세요.' })
    counters.issue++
    const i: Issue = {
      issueId: mockId('iss'), issueNo: `ISS-${pad4(counters.issue)}`, title: p.title, description: p.description ?? '', priority: p.priority, status: 'OPEN',
      owner: userRef(p.ownerId), dueDate: p.dueDate, escalation: 0, elementId: p.elementId ?? null, createdAt: now(), updatedAt: now(), comments: [],
    }
    issues.unshift(i)
    return [201, i]
  }],
  ['put', re('\\/issues\\/([^/]+)'), ({ params, body }) => {
    const i = issues.find((x) => x.issueId === params[0])
    if (!i) return err(404, 'NOT_FOUND', '이슈를 찾을 수 없습니다.')
    const p = body as { title: string; description: string; priority: Issue['priority']; status?: Issue['status']; ownerId: string | null; dueDate: string | null }
    Object.assign(i, { title: p.title, description: p.description, priority: p.priority, status: p.status ?? i.status, owner: userRef(p.ownerId), dueDate: p.dueDate, updatedAt: now() })
    return [200, i]
  }],
  ['delete', re('\\/issues\\/([^/]+)'), ({ params }) => {
    const idx = issues.findIndex((x) => x.issueId === params[0])
    if (idx >= 0) issues.splice(idx, 1)
    return [204, null]
  }],
  ['post', re('\\/issues\\/([^/]+)\\/comments'), ({ params, body, config }) => {
    const i = issues.find((x) => x.issueId === params[0])
    if (!i) return err(404, 'NOT_FOUND', '이슈를 찾을 수 없습니다.')
    const c: Comment = { commentId: mockId('cm'), content: String((body as { content: string }).content), author: { userId: `u-${tokenUser(config)}`, name: meName(config) }, createdAt: now() }
    ;(i.comments ??= []).push(c)
    return [201, c]
  }],
  ['post', re('\\/issues\\/([^/]+)\\/escalate'), ({ params }) => {
    const i = issues.find((x) => x.issueId === params[0])
    if (!i) return err(404, 'NOT_FOUND', '이슈를 찾을 수 없습니다.')
    if (i.escalation >= 2) return err(409, 'CONFLICT', '이미 최고 단계로 에스컬레이션되었습니다.')
    i.escalation = (i.escalation + 1) as Issue['escalation']
    pushNotification('ISSUE_ESCALATED', '이슈 에스컬레이션', `${i.issueNo} ${i.title} — ${i.escalation}단계`, `/quality/issues?id=${i.issueId}`)
    return [200, i]
  }],

  /* ── 리스크·의사결정 ── */
  ['get', re('\\/projects\\/([^/]+)\\/risks'), () => [200, risks]],
  ['post', re('\\/projects\\/([^/]+)\\/risks'), ({ body }) => {
    counters.risk++
    const p = body as Risk & { ownerId: string | null }
    const r: Risk = { ...p, riskId: mockId('rsk'), riskNo: `RSK-${pad4(counters.risk)}`, score: p.probability * p.impact, owner: userRef(p.ownerId) }
    delete (r as Partial<typeof p>).ownerId
    risks.push(r)
    return [201, r]
  }],
  ['put', re('\\/risks\\/([^/]+)'), ({ params, body }) => {
    const r = risks.find((x) => x.riskId === params[0])
    if (!r) return err(404, 'NOT_FOUND', '리스크를 찾을 수 없습니다.')
    const p = body as Risk & { ownerId: string | null }
    Object.assign(r, { title: p.title, description: p.description, probability: p.probability, impact: p.impact, score: p.probability * p.impact, response: p.response, status: p.status, owner: userRef(p.ownerId), dueDate: p.dueDate })
    return [200, r]
  }],
  ['delete', re('\\/risks\\/([^/]+)'), ({ params }) => {
    const i = risks.findIndex((x) => x.riskId === params[0])
    if (i >= 0) risks.splice(i, 1)
    return [204, null]
  }],
  ['post', re('\\/risks\\/([^/]+)\\/to-issue'), ({ params }) => {
    const r = risks.find((x) => x.riskId === params[0])
    if (!r) return err(404, 'NOT_FOUND', '리스크를 찾을 수 없습니다.')
    r.status = 'OCCURRED'
    counters.issue++
    const i: Issue = {
      issueId: mockId('iss'), issueNo: `ISS-${pad4(counters.issue)}`, title: `[리스크 발생] ${r.title}`, description: r.description, priority: r.score >= 15 ? 'H' : 'M',
      status: 'OPEN', owner: r.owner, dueDate: r.dueDate, escalation: 0, elementId: null, createdAt: now(), updatedAt: now(), comments: [],
    }
    issues.unshift(i)
    return [201, i]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/decisions'), () => [200, decisions]],
  ['post', re('\\/projects\\/([^/]+)\\/decisions'), ({ body }) => { const d = { ...(body as Decision), decisionId: mockId('dec') }; decisions.unshift(d); return [201, d] }],
  ['put', re('\\/decisions\\/([^/]+)'), ({ params, body }) => {
    const i = decisions.findIndex((x) => x.decisionId === params[0])
    if (i < 0) return err(404, 'NOT_FOUND', '의사결정을 찾을 수 없습니다.')
    decisions[i] = { ...(body as Decision), decisionId: params[0] }
    return [200, decisions[i]]
  }],
  ['delete', re('\\/decisions\\/([^/]+)'), ({ params }) => {
    const i = decisions.findIndex((x) => x.decisionId === params[0])
    if (i >= 0) decisions.splice(i, 1)
    return [204, null]
  }],

  /* ── CR ── */
  ['get', re('\\/projects\\/([^/]+)\\/change-requests'), () => [200, crs]],
  ['post', re('\\/projects\\/([^/]+)\\/change-requests'), ({ body }) => {
    counters.cr++
    const c: ChangeRequest = { ...(body as ChangeRequest), crId: mockId('cr'), crNo: `CR-${pad4(counters.cr)}`, status: 'REQUESTED', updatedAt: now() }
    crs.unshift(c)
    return [201, c]
  }],
  ['put', re('\\/change-requests\\/([^/]+)'), ({ params, body }) => {
    const c = crs.find((x) => x.crId === params[0])
    if (!c) return err(404, 'NOT_FOUND', '변경요청을 찾을 수 없습니다.')
    Object.assign(c, { ...(body as ChangeRequest), crId: c.crId, crNo: c.crNo, status: c.status, updatedAt: now() })
    return [200, c]
  }],
  ['post', re('\\/change-requests\\/([^/]+)\\/transition'), ({ params, body }) => {
    const c = crs.find((x) => x.crId === params[0])
    if (!c) return err(404, 'NOT_FOUND', '변경요청을 찾을 수 없습니다.')
    const to = (body as { to: CrStatus }).to
    if (!CR_TRANSITIONS[c.status].includes(to)) return err(409, 'CONFLICT', `${c.status} → ${to} 전이는 허용되지 않습니다.`)
    c.status = to
    c.updatedAt = now()
    return [200, c]
  }],

  /* ── 테스트 ── */
  ['get', re('\\/projects\\/([^/]+)\\/test-scenarios'), () => [200, scenarios.map((s) => ({ ...s, cases: s.cases.map((c) => ({ ...c, lastResult: lastResultOf(c.caseId) })) }))]],
  ['post', re('\\/projects\\/([^/]+)\\/test-scenarios'), ({ body }) => {
    const s: TestScenario = { scenarioId: mockId('ts'), ...(body as { level: TestScenario['level']; name: string }), cases: [] }
    scenarios.push(s)
    return [201, s]
  }],
  ['put', re('\\/test-scenarios\\/([^/]+)'), ({ params, body }) => {
    const s = scenarios.find((x) => x.scenarioId === params[0])
    if (!s) return err(404, 'NOT_FOUND', '시나리오를 찾을 수 없습니다.')
    Object.assign(s, body)
    return [200, s]
  }],
  ['delete', re('\\/test-scenarios\\/([^/]+)'), ({ params }) => {
    const i = scenarios.findIndex((x) => x.scenarioId === params[0])
    if (i >= 0) scenarios.splice(i, 1)
    return [204, null]
  }],
  ['post', re('\\/test-scenarios\\/([^/]+)\\/cases'), ({ params, body }) => {
    const s = scenarios.find((x) => x.scenarioId === params[0])
    if (!s) return err(404, 'NOT_FOUND', '시나리오를 찾을 수 없습니다.')
    counters.tc++
    const c = { ...(body as object), caseId: mockId('tc'), tcNo: `TC-${pad4(counters.tc)}`, lastResult: null } as TestScenario['cases'][number]
    s.cases.push(c)
    return [201, c]
  }],
  ['put', re('\\/test-cases\\/([^/]+)'), ({ params, body }) => {
    const hit = findCase(params[0])
    if (!hit) return err(404, 'NOT_FOUND', '테스트 케이스를 찾을 수 없습니다.')
    hit.s.cases[hit.idx] = { ...hit.s.cases[hit.idx], ...(body as object) }
    return [200, hit.s.cases[hit.idx]]
  }],
  ['delete', re('\\/test-cases\\/([^/]+)'), ({ params }) => {
    const hit = findCase(params[0])
    if (hit) hit.s.cases.splice(hit.idx, 1)
    return [204, null]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/test-rounds'), () => [200, rounds]],
  ['post', re('\\/projects\\/([^/]+)\\/test-rounds'), ({ body }) => {
    const r: TestRound = { roundId: mockId('tr'), ...(body as { name: string; level: TestRound['level'] }), createdAt: now() }
    rounds.push(r)
    results[r.roundId] = []
    return [201, r]
  }],
  ['get', re('\\/test-rounds\\/([^/]+)\\/results'), ({ params }) => [200, results[params[0]] ?? []]],
  ['put', re('\\/test-rounds\\/([^/]+)\\/results'), ({ params, body }) => { results[params[0]] = body as TestResultRow[]; return [200, results[params[0]]] }],

  /* ── 결함 ── */
  ['get', re('\\/projects\\/([^/]+)\\/defects'), ({ config }) => {
    const { status, severity, screenId } = config.params ?? {}
    const q = String(config.params?.q ?? '').toLowerCase()
    const list = defects.filter((d) => (!status || d.status === status) && (!severity || d.severity === severity) && (!screenId || d.screenId === screenId)
      && (!q || `${d.defectNo} ${d.title}`.toLowerCase().includes(q)))
    return [200, paginate(list, config.params)]
  }],
  ['get', re('\\/defects\\/([^/]+)'), ({ params }) => {
    const d = defects.find((x) => x.defectId === params[0])
    return d ? [200, d] : err(404, 'NOT_FOUND', '결함을 찾을 수 없습니다.')
  }],
  ['post', re('\\/projects\\/([^/]+)\\/defects'), ({ body, config }) => {
    const p = body as { title: string; description: string; severity: Defect['severity']; priority: Defect['priority']; assigneeId: string | null; caseId?: string | null; screenId?: string | null; elementId?: string | null }
    if (!p.title?.trim()) return err(400, 'VALIDATION', '제목을 입력하세요.', { title: '제목을 입력하세요.' })
    counters.defect++
    const d: Defect = {
      defectId: mockId('def'), defectNo: `DEF-${pad4(counters.defect)}`, title: p.title, description: p.description ?? '', severity: p.severity, priority: p.priority,
      status: p.assigneeId ? 'ASSIGNED' : 'NEW', assignee: userRef(p.assigneeId), reporter: { userId: `u-${tokenUser(config)}`, name: meName(config) },
      caseId: p.caseId ?? null, screenId: p.screenId ?? null, elementId: p.elementId ?? null, createdAt: now(), updatedAt: now(),
    }
    defects.unshift(d)
    if (p.assigneeId) pushNotification('DEFECT_ASSIGNED', '결함이 배정되었습니다', `${d.defectNo} ${d.title}`, `/quality/defects?id=${d.defectId}`)
    return [201, d]
  }],
  ['put', re('\\/defects\\/([^/]+)'), ({ params, body }) => {
    const d = defects.find((x) => x.defectId === params[0])
    if (!d) return err(404, 'NOT_FOUND', '결함을 찾을 수 없습니다.')
    const p = body as { title: string; description: string; severity: Defect['severity']; priority: Defect['priority']; assigneeId: string | null }
    Object.assign(d, { title: p.title, description: p.description, severity: p.severity, priority: p.priority, assignee: userRef(p.assigneeId), updatedAt: now() })
    return [200, d]
  }],
  ['post', re('\\/defects\\/([^/]+)\\/transition'), ({ params, body }) => {
    const d = defects.find((x) => x.defectId === params[0])
    if (!d) return err(404, 'NOT_FOUND', '결함을 찾을 수 없습니다.')
    const to = (body as { to: DefectStatus }).to
    if (!allowedDefectTransitions(d.status).includes(to)) return err(409, 'CONFLICT', `${d.status} → ${to} 전이는 허용되지 않습니다.`)
    d.status = to
    d.updatedAt = now()
    return [200, d]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/quality-report'), () => [200, qualityReport()]],

  /* ── 회의록 ── */
  ['get', re('\\/projects\\/([^/]+)\\/meetings'), () => [200, meetings]],
  ['get', re('\\/meetings\\/([^/]+)'), ({ params }) => {
    const m = meetings.find((x) => x.meetingId === params[0])
    return m ? [200, m] : err(404, 'NOT_FOUND', '회의록을 찾을 수 없습니다.')
  }],
  ['post', re('\\/projects\\/([^/]+)\\/meetings'), ({ body }) => {
    const p = body as { title: string; at: string; attendees: string; content: string; actionItems: { content: string; ownerId: string | null; dueDate: string | null; done: boolean }[] }
    counters.meeting++
    const m: Meeting = {
      meetingId: mockId('mtg'), meetingNo: `MTG-${pad4(counters.meeting)}`, title: p.title, at: p.at, attendees: p.attendees, content: p.content,
      actionItems: p.actionItems.map((a) => ({ itemId: mockId('ai'), content: a.content, owner: userRef(a.ownerId), dueDate: a.dueDate, done: a.done })),
    }
    meetings.unshift(m)
    return [201, m]
  }],
  ['put', re('\\/meetings\\/([^/]+)'), ({ params, body }) => {
    const m = meetings.find((x) => x.meetingId === params[0])
    if (!m) return err(404, 'NOT_FOUND', '회의록을 찾을 수 없습니다.')
    const p = body as { title: string; at: string; attendees: string; content: string; actionItems: { itemId?: string; content: string; ownerId: string | null; dueDate: string | null; done: boolean }[] }
    Object.assign(m, {
      title: p.title, at: p.at, attendees: p.attendees, content: p.content,
      actionItems: p.actionItems.map((a): ActionItem => ({ itemId: a.itemId ?? mockId('ai'), content: a.content, owner: userRef(a.ownerId), dueDate: a.dueDate, done: a.done })),
    })
    return [200, m]
  }],
  ['delete', re('\\/meetings\\/([^/]+)'), ({ params }) => {
    const i = meetings.findIndex((x) => x.meetingId === params[0])
    if (i >= 0) meetings.splice(i, 1)
    return [204, null]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/action-items'), ({ config }) => {
    const open = String(config.params?.open ?? 'true') === 'true'
    return [200, meetings.flatMap((m) => m.actionItems.map((a) => ({ ...a, meetingId: m.meetingId, meetingNo: m.meetingNo }))).filter((a) => !open || !a.done)]
  }],
  ['patch', re('\\/action-items\\/([^/]+)'), ({ params, body }) => {
    for (const m of meetings) {
      const a = m.actionItems.find((x) => x.itemId === params[0])
      if (a) {
        const p = body as { done?: boolean; ownerId?: string | null; dueDate?: string | null }
        if (p.done !== undefined) a.done = p.done
        if (p.ownerId !== undefined) a.owner = userRef(p.ownerId)
        if (p.dueDate !== undefined) a.dueDate = p.dueDate
        return [200, a]
      }
    }
    return err(404, 'NOT_FOUND', '액션아이템을 찾을 수 없습니다.')
  }],

  /* ── 알림·대시보드·검색 ── */
  ['get', re('\\/notifications'), ({ config }) => {
    const unread = String(config.params?.unread ?? '') === 'true'
    return [200, paginate(notifications.filter((n) => !unread || !n.read), config.params)]
  }],
  ['post', re('\\/notifications\\/read-all'), () => { notifications.forEach((n) => { n.read = true }); return [204, null] }],
  ['post', re('\\/notifications\\/([^/]+)\\/read'), ({ params }) => {
    const n = notifications.find((x) => x.notificationId === params[0])
    if (n) n.read = true
    return [204, null]
  }],
  ['get', re('\\/notifications\\/settings'), () => [200, notifySettings]],
  ['put', re('\\/notifications\\/settings'), ({ body }) => { notifySettings = body as NotificationSettings; return [200, notifySettings] }],
  ['get', re('\\/projects\\/([^/]+)\\/dashboard'), ({ params }) => (params[0] === PRJ ? [200, dashboard()]
    // SM 프로젝트(prj-sm-01): SR·SLA 위젯 포함 (R3 서버와 같은 형태)
    : params[0] === 'prj-sm-01' ? [200, { ...dashboard(), analysis: null, sm: { openSrs: 21, dueSoon: 6, breached: 2, complianceRate: 93.4, openIncidents: 1 } }]
    : err(404, 'NOT_FOUND', '프로젝트를 찾을 수 없습니다.'))],
  ['get', re('\\/dashboard\\/portfolio'), () => {
    const d = dashboard()
    const rows = PORTFOLIO.map((r) => (r.projectId === PRJ ? {
      ...r, planned: d.progress.planned, actual: d.progress.actual, spi: d.progress.spi, openIssues: d.openIssues, criticalDefects: d.defects.critical,
      health: (d.progress.spi ?? 1) < 0.9 || d.defects.critical > 0 ? 'RED' as const : (d.progress.spi ?? 1) < 0.95 || d.overdueIssues > 0 ? 'YELLOW' as const : 'GREEN' as const,
    } : r))
    return [200, rows]
  }],
  ['get', re('\\/search'), ({ config }) => [200, search(String(config.params?.q ?? ''))]],
]
