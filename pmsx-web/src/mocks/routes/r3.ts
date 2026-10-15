// docs/13 R3 mock 경로 — SR·고객 포털·SLA·장애·문제·변경·정기 작업·운영 자산·KB·월간 보고·인수인계·영향도
import type { MenuNode, Project, SystemSummary } from '@/types/api'
import type {
  Change, ChangePayload, HandoverRequest, Incident, IncidentPayload, KbArticle, KbPayload, MonthlyReport, OpsAsset, OpsAssetPayload,
  PortalSrPayload, Problem, ProblemCandidate, ProblemPayload, RoutineJob, RoutineJobPayload, RoutineOccurrence, SimilarItem, SlaPolicy,
  SlaStatus, Sr, SrComment, SrHistory, SrPayload, SrSla, SrStatus,
} from '@/types/r3'
import { addDays, today } from '@/utils/date'
import { allowedSrTransitions, CHANGE_TRANSITIONS, daysLeftOf, routineDates, srTransitionNeedsComment } from '@/utils/sm'
import { MOCK_DATASETS } from '../fixtures/admin'
import { MOCK_PROJECTS } from '../fixtures/common'
import {
  CHANGES, IMPACT_TB_EMP, INCIDENTS, KB_ARTICLES, MONTHLY_SEP, OPS_ASSETS, PROBLEMS, R3_DATASETS, ROUTINE_JOBS, SCREEN_NAMES,
  SLA_POLICIES, SM_PEOPLE, SM_PRJ, SM_SYS, SR_SEEDS,
} from '../fixtures/r3'
import { err, mockId, paginate, tokenUser, type Route } from '../shared'

R3_DATASETS.forEach((d) => { if (!MOCK_DATASETS.some((x) => x.datasetId === d.datasetId)) MOCK_DATASETS.push(d) })

const MIN = 60_000
const nowIso = () => new Date().toISOString()
const CLOSED_LIKE: SrStatus[] = ['RESOLVED', 'CONFIRMED', 'CLOSED']
const USERS_BY_LOGIN: Record<string, { userId: string; name: string }> = {
  admin: SM_PEOPLE.admin, operator: SM_PEOPLE.op, customer: SM_PEOPLE.cust, analyst: { userId: 'u-analyst', name: '김분석' },
}
const ASSIGNEES: Record<string, { userId: string; name: string }> = {
  'u-op01': SM_PEOPLE.op, 'u-dev01': SM_PEOPLE.op2, 'u-pl': SM_PEOPLE.pl, 'u-admin': SM_PEOPLE.admin, 'u-analyst': { userId: 'u-analyst', name: '김분석' },
}

/* ─────────── SR 상태 (SLA 는 응답 때마다 계산) ─────────── */
interface SrState {
  sr: Omit<Sr, 'sla'>
  policy: SlaPolicy | null
  createdMs: number
  responseDueMs: number
  resolveDueMs: number
  respondedMs: number | null
  resolvedMs: number | null
  pausedSinceMs: number | null
  pausedMinutes: number
}

const policies: SlaPolicy[] = structuredClone(SLA_POLICIES)
const matchPolicy = (type: string, priority: string): SlaPolicy | null =>
  [...policies].sort((a, b) => a.order - b.order)
    .find((p) => (!p.types.length || p.types.includes(type as never)) && (!p.priorities.length || p.priorities.includes(priority))) ?? null

const slaOf = (s: SrState): SrSla | null => {
  if (!s.policy || s.sr.status === 'REJECTED') return null
  const now = Date.now()
  const at = s.resolvedMs ?? s.pausedSinceMs ?? now
  const remainingMinutes = Math.round((s.resolveDueMs - at) / MIN)
  const closed = CLOSED_LIKE.includes(s.sr.status)
  const soon = Math.max(30, s.policy.resolveMinutes * 0.2)
  const state: SrSla['state'] = closed ? (remainingMinutes >= 0 ? 'MET' : 'BREACHED')
    : s.sr.status === 'ON_HOLD' ? 'PAUSED'
      : remainingMinutes < 0 ? 'BREACHED' : remainingMinutes <= soon ? 'DUE_SOON' : 'OK'
  return {
    policyId: s.policy.policyId, policyName: s.policy.name,
    responseDue: new Date(s.responseDueMs).toISOString(), resolveDue: new Date(s.resolveDueMs).toISOString(),
    respondedAt: s.respondedMs ? new Date(s.respondedMs).toISOString() : null, resolvedAt: s.resolvedMs ? new Date(s.resolvedMs).toISOString() : null,
    pausedMinutes: s.pausedMinutes + (s.pausedSinceMs ? Math.round((now - s.pausedSinceMs) / MIN) : 0),
    responseBreached: (s.respondedMs ?? now) > s.responseDueMs,
    resolveBreached: remainingMinutes < 0,
    remainingMinutes, state,
  }
}

const bigrams = (s: string) => {
  const t = s.replace(/\s+/g, '')
  const out = new Set<string>()
  for (let i = 0; i < t.length - 1; i++) out.add(t.slice(i, i + 2))
  return out
}
const similarity = (a: string, b: string) => {
  const x = bigrams(a)
  const y = bigrams(b)
  if (!x.size || !y.size) return 0
  let inter = 0
  x.forEach((g) => { if (y.has(g)) inter++ })
  return inter / (x.size + y.size - inter)
}

const states: SrState[] = []
const comments: Record<string, SrComment[]> = {}
const history: Record<string, SrHistory[]> = {}
let srSeq = 0

const seedComments: Record<number, [string, boolean, keyof typeof SM_PEOPLE, number][]> = {
  1: [['저장 시 500 오류가 계속 납니다. 급합니다.', false, 'cust', 200], ['로그 확인 결과 EmpServiceImpl.saveEmp 에서 NullPointerException. 입사일 null 처리 누락.', true, 'op', 150], ['원인 파악했습니다. 오늘 중 조치 예정입니다.', false, 'op', 140]],
  9: [['발령 저장 후 TB_EMP.DEPT_CD 갱신 SQL 조건 오류였습니다. 수정 반영했습니다. 확인 부탁드립니다.', false, 'op', 60]],
  30: [['팝업 닫힘 처리 스크립트 수정 완료. 확인 부탁드립니다.', false, 'op', 30]],
  12: [['미래 입사일 검증 로직 추가 예정 (CHG-0001 과 함께 배포).', true, 'op', 100]],
}

SR_SEEDS.forEach(([no, type, priority, title, status, who, screenId, elementId, remaining, ago, channel, byCustomer]) => {
  const now = Date.now()
  const createdMs = now - ago * MIN
  const policy = matchPolicy(type, priority)
  const assignee = who ? SM_PEOPLE[who] : null
  const srId = `sr-${no}`
  const closed = CLOSED_LIKE.includes(status)
  const resolvedMs = closed ? createdMs + Math.max(20, Math.round(ago * 0.4)) * MIN : null
  const resolveDueMs = closed ? (resolvedMs as number) + remaining * MIN : now + remaining * MIN
  const st: SrState = {
    sr: {
      srId, srNo: `SR-${String(no).padStart(4, '0')}`, type, priority, title,
      description: `${title}\n\n발생 화면: ${screenId ? SCREEN_NAMES[screenId] : '-'}\n재현 방법과 캡처를 첨부합니다.`,
      channel,
      requester: byCustomer
        ? { name: '최고객', org: '○○공사 인사팀', contact: '02-000-1234', userId: 'u-cust01' }
        : { name: '김담당', org: '○○공사 총무팀', contact: null, userId: null },
      status, assignee, systemId: screenId ? SM_SYS : null, screenId, elementId,
      screenName: screenId ? SCREEN_NAMES[screenId] ?? null : null,
      effortHours: closed || status === 'IN_PROGRESS' ? ((no * 7) % 13) + 1 : 0,
      createdAt: new Date(createdMs).toISOString(), updatedAt: new Date(now - Math.min(ago, 30) * MIN).toISOString(),
    },
    policy, createdMs,
    responseDueMs: createdMs + (policy?.responseMinutes ?? 240) * MIN,
    resolveDueMs,
    respondedMs: ['RECEIVED', 'CLASSIFIED'].includes(status) ? null : createdMs + 20 * MIN,
    resolvedMs,
    pausedSinceMs: status === 'ON_HOLD' ? now - 30 * MIN : null,
    pausedMinutes: 0,
  }
  states.push(st)
  comments[srId] = (seedComments[no] ?? []).map(([content, internal, by, minsAgo], i) => ({
    commentId: `c-${no}-${i}`, content, internal, author: SM_PEOPLE[by], createdAt: new Date(now - minsAgo * MIN).toISOString(),
  }))
  history[srId] = [{ at: new Date(createdMs).toISOString(), user: byCustomer ? '최고객' : '김담당', from: null, to: 'RECEIVED', comment: null }]
  if (status !== 'RECEIVED') history[srId].push({ at: new Date(createdMs + 20 * MIN).toISOString(), user: assignee?.name ?? '정운영', from: 'RECEIVED', to: status, comment: null })
  srSeq = Math.max(srSeq, no)
})

const toSr = (s: SrState, detail = false, forPortal = false): Sr => {
  const base: Sr = { ...s.sr, sla: slaOf(s) }
  if (!detail) return base
  const all = comments[s.sr.srId] ?? []
  return {
    ...base,
    comments: forPortal ? all.filter((c) => !c.internal) : all,
    history: history[s.sr.srId] ?? [],
    similar: forPortal ? [] : similarFor(s.sr.srId, `${s.sr.title}`),
  }
}

const kbs: KbArticle[] = structuredClone(KB_ARTICLES)
let kbSeq = kbs.length
const similarFor = (srId: string | null, text: string): SimilarItem[] => {
  const kbHits: SimilarItem[] = kbs.map((k) => ({ kind: 'KB' as const, id: k.kbId, no: k.kbNo, title: k.title, score: similarity(text, `${k.title} ${k.tags.join(' ')}`) }))
  const srHits: SimilarItem[] = states
    .filter((x) => x.sr.srId !== srId && ['CLOSED', 'CONFIRMED'].includes(x.sr.status))
    .map((x) => ({ kind: 'SR' as const, id: x.sr.srId, no: x.sr.srNo, title: x.sr.title, score: similarity(text, x.sr.title) }))
  return [...kbHits, ...srHits].filter((h) => h.score >= 0.12).sort((a, b) => b.score - a.score).slice(0, 5)
    .map((h) => ({ ...h, score: Math.round(h.score * 100) / 100 }))
}

const findSr = (id: string) => states.find((s) => s.sr.srId === id) ?? null
const touch = (s: SrState) => { s.sr.updatedAt = nowIso() }

/** 상태 전이 — SLA 일시정지·재개·응답·해결 시각을 함께 갱신한다 */
const applyTransition = (s: SrState, to: SrStatus, user: string, comment: string | null) => {
  const now = Date.now()
  const from = s.sr.status
  if (from === 'ON_HOLD' && s.pausedSinceMs) {
    const paused = Math.round((now - s.pausedSinceMs) / MIN)
    s.pausedMinutes += paused
    s.resolveDueMs += paused * MIN
    s.pausedSinceMs = null
  }
  if (to === 'ON_HOLD') s.pausedSinceMs = now
  if (to === 'IN_PROGRESS' && !s.respondedMs) s.respondedMs = now
  if (to === 'RESOLVED') s.resolvedMs = now
  if (from === 'RESOLVED' && to === 'IN_PROGRESS') s.resolvedMs = null
  s.sr.status = to
  touch(s)
  ;(history[s.sr.srId] ??= []).push({ at: nowIso(), user, from, to, comment })
}

/* ─────────── 기타 상태 ─────────── */
const incidents: Incident[] = structuredClone(INCIDENTS)
const problems: Problem[] = structuredClone(PROBLEMS)
const changes: Change[] = structuredClone(CHANGES)
const jobs: RoutineJob[] = structuredClone(ROUTINE_JOBS)
const checks: Record<string, { done: boolean; doneBy: string | null; note: string | null }> = {}
const assets: Omit<OpsAsset, 'daysLeft'>[] = structuredClone(OPS_ASSETS)
const reports: Record<string, MonthlyReport> = { [MONTHLY_SEP.reportId]: structuredClone(MONTHLY_SEP) }
const counters = { inc: incidents.length, prb: problems.length, chg: changes.length }

const withDays = (a: Omit<OpsAsset, 'daysLeft'>): OpsAsset => ({ ...a, daysLeft: daysLeftOf(a.expiresAt, today()) })
const userOf = (cfg: Parameters<typeof tokenUser>[0]) => USERS_BY_LOGIN[tokenUser(cfg)] ?? SM_PEOPLE.admin

const occurrences = (from: string, to: string): RoutineOccurrence[] => {
  const td = today()
  return jobs.flatMap((j) => routineDates(j.rule, from, to).map((date) => {
    const key = `${j.jobId}|${date}`
    // 지난 날짜는 대부분 완료로 둔다(가끔 빠뜨린 날이 보이도록 날짜 기반으로 섞는다)
    const seeded = date < td ? { done: Number(date.slice(8)) % 9 !== 4, doneBy: j.owner?.name ?? null, note: null } : { done: false, doneBy: null, note: null }
    const c = checks[key] ?? seeded
    return { jobId: j.jobId, name: j.name, date, done: c.done, doneBy: c.doneBy, note: c.note }
  }))
}

const candidates = (): ProblemCandidate[] => {
  const out: ProblemCandidate[] = []
  const recent = states.filter((s) => Date.now() - s.createdMs <= 90 * 86_400_000)
  const group = (key: (s: SrState) => string | null) => recent.reduce<Record<string, SrState[]>>((acc, s) => {
    const k = key(s)
    if (k) (acc[k] ??= []).push(s)
    return acc
  }, {})
  const asRef = (list: SrState[]) => list.map((s) => ({ srId: s.sr.srId, srNo: s.sr.srNo, title: s.sr.title, createdAt: s.sr.createdAt }))
  Object.entries(group((s) => s.sr.elementId)).filter(([, l]) => l.length >= 3).forEach(([k, l]) => {
    out.push({ key: `el:${k}`, reason: 'SAME_ELEMENT', label: `${l[0].sr.screenName} › ${k.replace(/^el-[^-]+-/, '')}`, count: l.length, srs: asRef(l), incidents: [] })
  })
  Object.entries(group((s) => s.sr.screenId)).filter(([, l]) => l.length >= 3).forEach(([k, l]) => {
    out.push({
      key: `scr:${k}`, reason: 'SAME_SCREEN', label: SCREEN_NAMES[k] ?? k, count: l.length + incidents.filter((i) => i.title.includes(SCREEN_NAMES[k] ?? '#')).length,
      srs: asRef(l), incidents: incidents.filter((i) => i.title.includes(SCREEN_NAMES[k] ?? '#')).map((i) => ({ incidentId: i.incidentId, incidentNo: i.incidentNo, title: i.title })),
    })
  })
  return out.sort((a, b) => b.count - a.count)
}

const generateReport = (month: string): MonthlyReport => {
  const inMonth = states.filter((s) => s.sr.createdAt.slice(0, 7) === month || (month === today().slice(0, 7) && !CLOSED_LIKE.includes(s.sr.status)))
  const count = <K extends string>(f: (s: SrState) => K) => inMonth.reduce<Record<string, number>>((acc, s) => { const k = f(s); acc[k] = (acc[k] ?? 0) + 1; return acc }, {})
  const slaList = inMonth.map((s) => ({ s, sla: slaOf(s) })).filter((x) => x.sla)
  const breached = slaList.filter((x) => x.sla?.state === 'BREACHED')
  return {
    reportId: `mr-${month}`, month, summary: '', plan: '',
    sr: { total: inMonth.length, byType: count((s) => s.sr.type), byStatus: count((s) => s.sr.status), carriedOver: inMonth.filter((s) => s.sr.createdAt.slice(0, 7) < month).length },
    sla: {
      complianceRate: slaList.length ? Math.round(((slaList.length - breached.length) / slaList.length) * 1000) / 1000 : 1,
      responseRate: slaList.length ? Math.round((slaList.filter((x) => !x.sla?.responseBreached).length / slaList.length) * 1000) / 1000 : 1,
      breaches: breached.map((x) => ({ srNo: x.s.sr.srNo, title: x.s.sr.title, reason: null })),
    },
    incidents: incidents.filter((i) => i.startedAt.slice(0, 7) === month).map((i) => ({ incidentNo: i.incidentNo, title: i.title, grade: i.grade, durationMinutes: i.durationMinutes })),
    changes: changes.filter((c) => (c.deployedAt ?? c.scheduledAt ?? '').slice(0, 7) === month).map((c) => ({ changeNo: c.changeNo, title: c.title, deployedAt: c.deployedAt, status: c.status })),
    effort: Object.values(inMonth.reduce<Record<string, { userId: string; name: string; hours: number; mm: number }>>((acc, s) => {
      if (!s.sr.assignee) return acc
      const e = acc[s.sr.assignee.userId] ??= { userId: s.sr.assignee.userId, name: s.sr.assignee.name, hours: 0, mm: 0 }
      e.hours += s.sr.effortHours
      e.mm = Math.round((e.hours / 168) * 100) / 100
      return acc
    }, {})),
    topScreens: Object.entries(count((s) => s.sr.screenId ?? '')).filter(([k]) => k).sort((a, b) => b[1] - a[1]).slice(0, 5)
      .map(([screenId, srCount]) => ({ screenId, name: SCREEN_NAMES[screenId] ?? screenId, srCount })),
  }
}

const P = '^\\/api\\/v1'
const re = (s: string) => new RegExp(`${P}${s}$`)
const nextNo = (prefix: string, n: number) => `${prefix}-${String(n).padStart(4, '0')}`

export const createR3Routes = (ctx: { systems: SystemSummary[]; menuTree: Record<string, MenuNode[]> }): Route[] => [
  /* ── SR ── */
  ['get', re('\\/projects\\/([^/]+)\\/srs'), ({ config }) => {
    const p = config.params ?? {}
    const q = String(p.q ?? '').trim().toLowerCase()
    const list = states
      .filter((s) => !p.status || s.sr.status === p.status)
      .filter((s) => !p.type || s.sr.type === p.type)
      .filter((s) => !p.priority || s.sr.priority === p.priority)
      .filter((s) => !p.assigneeId || s.sr.assignee?.userId === p.assigneeId)
      .filter((s) => !p.screenId || s.sr.screenId === p.screenId)
      .filter((s) => !q || `${s.sr.srNo} ${s.sr.title} ${s.sr.requester.name}`.toLowerCase().includes(q))
      .filter((s) => !p.slaState || slaOf(s)?.state === p.slaState)
      .sort((a, b) => b.createdMs - a.createdMs)
      .map((s) => toSr(s))
    return [200, paginate(list, p)]
  }],
  ['post', re('\\/projects\\/([^/]+)\\/srs'), ({ body, config }) => {
    const p = body as SrPayload
    if (!p.title?.trim()) return err(400, 'VALIDATION', '제목을 입력하세요.', { title: '필수 항목입니다.' })
    const no = ++srSeq
    const now = Date.now()
    const policy = matchPolicy(p.type, p.priority)
    const assignee = p.assigneeId ? ASSIGNEES[p.assigneeId] ?? { userId: p.assigneeId, name: p.assigneeId } : null
    const st: SrState = {
      sr: {
        srId: `sr-${no}`, srNo: nextNo('SR', no), type: p.type, priority: p.priority, title: p.title, description: p.description,
        channel: p.channel ?? 'MANUAL', requester: { ...(p.requester ?? { name: userOf(config).name, org: null, contact: null }), userId: null },
        status: assignee ? 'ASSIGNED' : 'RECEIVED', assignee, systemId: p.systemId, screenId: p.screenId, elementId: p.elementId,
        screenName: p.screenId ? SCREEN_NAMES[p.screenId] ?? p.screenId : null, effortHours: 0, createdAt: nowIso(), updatedAt: nowIso(),
      },
      policy, createdMs: now, responseDueMs: now + (policy?.responseMinutes ?? 240) * MIN, resolveDueMs: now + (policy?.resolveMinutes ?? 1440) * MIN,
      respondedMs: null, resolvedMs: null, pausedSinceMs: null, pausedMinutes: 0,
    }
    states.push(st)
    comments[st.sr.srId] = []
    history[st.sr.srId] = [{ at: nowIso(), user: userOf(config).name, from: null, to: st.sr.status, comment: null }]
    return [201, toSr(st, true)]
  }],
  ['get', re('\\/srs\\/([^/]+)'), ({ params }) => {
    const s = findSr(params[0])
    return s ? [200, toSr(s, true)] : err(404, 'NOT_FOUND', 'SR 을 찾을 수 없습니다.')
  }],
  ['put', re('\\/srs\\/([^/]+)'), ({ params, body }) => {
    const s = findSr(params[0])
    if (!s) return err(404, 'NOT_FOUND', 'SR 을 찾을 수 없습니다.')
    const p = body as SrPayload
    Object.assign(s.sr, {
      type: p.type, priority: p.priority, title: p.title, description: p.description,
      requester: { ...s.sr.requester, ...(p.requester ?? {}) },
      systemId: p.systemId, screenId: p.screenId, elementId: p.elementId, screenName: p.screenId ? SCREEN_NAMES[p.screenId] ?? p.screenId : null,
      assignee: p.assigneeId ? ASSIGNEES[p.assigneeId] ?? { userId: p.assigneeId, name: p.assigneeId } : s.sr.assignee,
    })
    touch(s)
    return [200, toSr(s, true)]
  }],
  ['post', re('\\/srs\\/([^/]+)\\/transition'), ({ params, body, config }) => {
    const s = findSr(params[0])
    if (!s) return err(404, 'NOT_FOUND', 'SR 을 찾을 수 없습니다.')
    const p = body as { to: SrStatus; comment?: string; assigneeId?: string }
    if (!allowedSrTransitions(s.sr.status).includes(p.to)) return err(409, 'CONFLICT', `${s.sr.status} → ${p.to} 로 바꿀 수 없습니다.`)
    if (srTransitionNeedsComment(s.sr.status, p.to) && !p.comment?.trim()) return err(400, 'VALIDATION', '사유를 입력하세요.', { comment: '필수 항목입니다.' })
    if (p.to === 'ASSIGNED') {
      if (!p.assigneeId) return err(400, 'VALIDATION', '담당자를 지정하세요.', { assigneeId: '필수 항목입니다.' })
      s.sr.assignee = ASSIGNEES[p.assigneeId] ?? { userId: p.assigneeId, name: p.assigneeId }
    }
    const user = userOf(config).name
    applyTransition(s, p.to, user, p.comment?.trim() || null)
    if (p.comment?.trim()) comments[s.sr.srId].push({ commentId: mockId('c'), content: p.comment.trim(), internal: p.to === 'REJECTED' ? false : true, author: userOf(config), createdAt: nowIso() })
    return [200, toSr(s, true)]
  }],
  ['post', re('\\/srs\\/([^/]+)\\/comments'), ({ params, body, config }) => {
    const s = findSr(params[0])
    if (!s) return err(404, 'NOT_FOUND', 'SR 을 찾을 수 없습니다.')
    const c: SrComment = { commentId: mockId('c'), content: String(body?.content ?? ''), internal: !!body?.internal, author: userOf(config), createdAt: nowIso() }
    comments[s.sr.srId].push(c)
    if (!c.internal && !s.respondedMs) s.respondedMs = Date.now()
    touch(s)
    return [201, c]
  }],
  ['post', re('\\/srs\\/([^/]+)\\/to-kb'), ({ params }) => {
    const s = findSr(params[0])
    if (!s) return err(404, 'NOT_FOUND', 'SR 을 찾을 수 없습니다.')
    const lastPublic = [...(comments[s.sr.srId] ?? [])].reverse().find((c) => !c.internal)
    const kb: KbArticle = {
      kbId: mockId('kb'), kbNo: nextNo('KB', ++kbSeq), title: s.sr.title,
      content: `## 증상\n${s.sr.description}\n\n## 조치\n${lastPublic?.content ?? '(조치 내용을 적어 주세요)'}`,
      tags: [s.sr.screenName ?? '', s.sr.type === 'INCIDENT' ? '장애' : ''].filter(Boolean), systemId: s.sr.systemId, screenId: s.sr.screenId, sourceSrId: s.sr.srId, updatedAt: nowIso(),
    }
    kbs.unshift(kb)
    return [201, kb]
  }],

  /* ── 고객 포털 ── */
  ['get', re('\\/portal\\/projects'), () => [200, MOCK_PROJECTS.filter((p) => p.projectId === SM_PRJ)]],
  ['get', re('\\/portal\\/projects\\/([^/]+)\\/srs'), ({ config }) => {
    const me = userOf(config).userId
    const p = config.params ?? {}
    const q = String(p.q ?? '').trim().toLowerCase()
    const inProgress: SrStatus[] = ['CLASSIFIED', 'ASSIGNED', 'IN_PROGRESS', 'ON_HOLD']
    const list = states
      .filter((s) => s.sr.requester.userId === me)
      .filter((s) => !p.status || s.sr.status === p.status || (p.status === 'IN_PROGRESS' && inProgress.includes(s.sr.status)))
      .filter((s) => !q || `${s.sr.srNo} ${s.sr.title}`.toLowerCase().includes(q))
      .sort((a, b) => b.createdMs - a.createdMs)
      .map((s) => toSr(s))
    return [200, paginate(list, p)]
  }],
  ['post', re('\\/portal\\/projects\\/([^/]+)\\/srs'), ({ body, config }) => {
    const p = body as PortalSrPayload
    if (!p.title?.trim() || !p.description?.trim()) return err(400, 'VALIDATION', '제목과 내용을 입력하세요.')
    const me = userOf(config)
    const no = ++srSeq
    const now = Date.now()
    const policy = matchPolicy(p.type, 'P3')
    const st: SrState = {
      sr: {
        srId: `sr-${no}`, srNo: nextNo('SR', no), type: p.type, priority: 'P3', title: p.title, description: p.description, channel: 'PORTAL',
        requester: { name: me.name, org: '○○공사', contact: null, userId: me.userId }, status: 'RECEIVED', assignee: null,
        systemId: p.systemId, screenId: p.screenId, elementId: p.elementId, screenName: p.screenId ? SCREEN_NAMES[p.screenId] ?? p.screenId : null,
        effortHours: 0, createdAt: nowIso(), updatedAt: nowIso(),
      },
      policy, createdMs: now, responseDueMs: now + (policy?.responseMinutes ?? 240) * MIN, resolveDueMs: now + (policy?.resolveMinutes ?? 1440) * MIN,
      respondedMs: null, resolvedMs: null, pausedSinceMs: null, pausedMinutes: 0,
    }
    states.push(st)
    comments[st.sr.srId] = []
    history[st.sr.srId] = [{ at: nowIso(), user: me.name, from: null, to: 'RECEIVED', comment: null }]
    return [201, toSr(st, true, true)]
  }],
  ['get', re('\\/portal\\/srs\\/([^/]+)'), ({ params, config }) => {
    const s = findSr(params[0])
    if (!s || s.sr.requester.userId !== userOf(config).userId) return err(404, 'NOT_FOUND', '요청을 찾을 수 없습니다.')
    return [200, toSr(s, true, true)]
  }],
  ['post', re('\\/portal\\/srs\\/([^/]+)\\/comments'), ({ params, body, config }) => {
    const s = findSr(params[0])
    if (!s) return err(404, 'NOT_FOUND', '요청을 찾을 수 없습니다.')
    const c: SrComment = { commentId: mockId('c'), content: String(body?.content ?? ''), internal: false, author: userOf(config), createdAt: nowIso() }
    comments[s.sr.srId].push(c)
    return [201, c]
  }],
  ['post', re('\\/portal\\/srs\\/([^/]+)\\/confirm'), ({ params, config }) => {
    const s = findSr(params[0])
    if (!s) return err(404, 'NOT_FOUND', '요청을 찾을 수 없습니다.')
    if (s.sr.status !== 'RESOLVED') return err(409, 'CONFLICT', '처리 완료된 요청만 확인할 수 있습니다.')
    applyTransition(s, 'CONFIRMED', userOf(config).name, null)
    return [200, toSr(s, true, true)]
  }],
  ['post', re('\\/portal\\/srs\\/([^/]+)\\/reopen'), ({ params, body, config }) => {
    const s = findSr(params[0])
    if (!s) return err(404, 'NOT_FOUND', '요청을 찾을 수 없습니다.')
    if (s.sr.status !== 'RESOLVED') return err(409, 'CONFLICT', '처리 완료된 요청만 재요청할 수 있습니다.')
    const reason = String(body?.reason ?? '').trim()
    if (!reason) return err(400, 'VALIDATION', '사유를 입력하세요.')
    applyTransition(s, 'IN_PROGRESS', userOf(config).name, reason)
    comments[s.sr.srId].push({ commentId: mockId('c'), content: `[재요청] ${reason}`, internal: false, author: userOf(config), createdAt: nowIso() })
    return [200, toSr(s, true, true)]
  }],

  /* ── SLA ── */
  ['get', re('\\/projects\\/([^/]+)\\/sla-policies'), () => [200, [...policies].sort((a, b) => a.order - b.order)]],
  ['post', re('\\/projects\\/([^/]+)\\/sla-policies'), ({ body }) => {
    const p = { ...(body as Omit<SlaPolicy, 'policyId'>), policyId: mockId('sla') }
    policies.push(p)
    return [201, p]
  }],
  ['put', re('\\/sla-policies\\/([^/]+)'), ({ params, body }) => {
    const i = policies.findIndex((p) => p.policyId === params[0])
    if (i < 0) return err(404, 'NOT_FOUND', '정책을 찾을 수 없습니다.')
    policies[i] = { ...(body as SlaPolicy), policyId: params[0] }
    return [200, policies[i]]
  }],
  ['delete', re('\\/sla-policies\\/([^/]+)'), ({ params }) => {
    const i = policies.findIndex((p) => p.policyId === params[0])
    if (i >= 0) policies.splice(i, 1)
    return [204, null]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/sla\\/status'), ({ config }) => {
    const month = String(config.params?.month ?? today().slice(0, 7))
    const inMonth = states.filter((s) => s.sr.createdAt.slice(0, 7) === month && s.policy && s.sr.status !== 'REJECTED')
    const slas = inMonth.map((s) => ({ s, sla: slaOf(s) as SrSla }))
    const breached = slas.filter((x) => x.sla.state === 'BREACHED' || x.sla.resolveBreached)
    const byType = {} as SlaStatus['byType']
    slas.forEach(({ s, sla }) => {
      const b = byType[s.sr.type] ??= { total: 0, met: 0 }
      b.total++
      if (!sla.resolveBreached) b.met++
    })
    const status: SlaStatus = {
      complianceRate: slas.length ? (slas.length - breached.length) / slas.length : 1,
      responseRate: slas.length ? slas.filter((x) => !x.sla.responseBreached).length / slas.length : 1,
      total: slas.length, met: slas.length - breached.length, breached: breached.length,
      dueSoon: states.filter((s) => slaOf(s)?.state === 'DUE_SOON').map((s) => toSr(s)),
      breachedList: breached.map((x) => toSr(x.s)),
      byType,
    }
    return [200, status]
  }],

  /* ── 장애 ── */
  ['get', re('\\/projects\\/([^/]+)\\/incidents'), () => [200, [...incidents].sort((a, b) => b.startedAt.localeCompare(a.startedAt))]],
  ['get', re('\\/incidents\\/([^/]+)'), ({ params }) => {
    const i = incidents.find((x) => x.incidentId === params[0])
    return i ? [200, i] : err(404, 'NOT_FOUND', '장애를 찾을 수 없습니다.')
  }],
  ['post', re('\\/projects\\/([^/]+)\\/incidents'), ({ body }) => {
    const p = body as IncidentPayload
    const inc: Incident = { ...p, timeline: p.timeline ?? [], incidentId: mockId('inc'), incidentNo: nextNo('INC', ++counters.inc), durationMinutes: null }
    if (inc.recoveredAt) inc.durationMinutes = Math.round((Date.parse(inc.recoveredAt) - Date.parse(inc.startedAt)) / MIN)
    incidents.push(inc)
    return [201, inc]
  }],
  ['put', re('\\/incidents\\/([^/]+)'), ({ params, body }) => {
    const i = incidents.find((x) => x.incidentId === params[0])
    if (!i) return err(404, 'NOT_FOUND', '장애를 찾을 수 없습니다.')
    Object.assign(i, body as IncidentPayload, { timeline: i.timeline })
    i.durationMinutes = i.recoveredAt ? Math.round((Date.parse(i.recoveredAt) - Date.parse(i.startedAt)) / MIN) : null
    return [200, i]
  }],
  ['post', re('\\/incidents\\/([^/]+)\\/timeline'), ({ params, body }) => {
    const i = incidents.find((x) => x.incidentId === params[0])
    if (!i) return err(404, 'NOT_FOUND', '장애를 찾을 수 없습니다.')
    i.timeline.push({ at: String(body?.at ?? nowIso()), content: String(body?.content ?? '') })
    return [200, i]
  }],

  /* ── 문제 ── */
  ['get', re('\\/projects\\/([^/]+)\\/problems\\/candidates'), () => [200, candidates()]],
  ['get', re('\\/projects\\/([^/]+)\\/problems'), () => [200, problems]],
  ['post', re('\\/projects\\/([^/]+)\\/problems'), ({ body }) => {
    const p: Problem = { ...(body as ProblemPayload), problemId: mockId('prb'), problemNo: nextNo('PRB', ++counters.prb) }
    problems.unshift(p)
    return [201, p]
  }],
  ['put', re('\\/problems\\/([^/]+)'), ({ params, body }) => {
    const p = problems.find((x) => x.problemId === params[0])
    if (!p) return err(404, 'NOT_FOUND', '문제를 찾을 수 없습니다.')
    Object.assign(p, body as ProblemPayload)
    return [200, p]
  }],

  /* ── 변경·배포 ── */
  ['get', re('\\/projects\\/([^/]+)\\/changes\\/calendar'), ({ config }) => {
    const from = String(config.params?.from ?? '')
    const to = String(config.params?.to ?? '9999')
    return [200, changes.filter((c) => { const d = (c.deployedAt ?? c.scheduledAt ?? '').slice(0, 10); return d >= from && d <= to })]
  }],
  ['get', re('\\/projects\\/([^/]+)\\/changes'), () => [200, [...changes].sort((a, b) => (b.scheduledAt ?? '').localeCompare(a.scheduledAt ?? ''))]],
  ['post', re('\\/projects\\/([^/]+)\\/changes'), ({ body }) => {
    const c: Change = { ...(body as ChangePayload), changeId: mockId('chg'), changeNo: nextNo('CHG', ++counters.chg), status: 'REQUESTED', cab: [], deployedAt: null }
    changes.unshift(c)
    return [201, c]
  }],
  ['put', re('\\/changes\\/([^/]+)'), ({ params, body }) => {
    const c = changes.find((x) => x.changeId === params[0])
    if (!c) return err(404, 'NOT_FOUND', '변경을 찾을 수 없습니다.')
    Object.assign(c, body as ChangePayload)
    return [200, c]
  }],
  ['post', re('\\/changes\\/([^/]+)\\/cab'), ({ params, body, config }) => {
    const c = changes.find((x) => x.changeId === params[0])
    if (!c) return err(404, 'NOT_FOUND', '변경을 찾을 수 없습니다.')
    if (c.status !== 'REQUESTED') return err(409, 'CONFLICT', '요청 상태에서만 CAB 결정을 할 수 있습니다.')
    const me = userOf(config)
    c.cab = c.cab.filter((x) => x.userId !== me.userId)
    c.cab.push({ userId: me.userId, name: me.name, decision: String(body?.decision), comment: body?.comment || null, at: nowIso() })
    if (body?.decision === 'REJECT') c.status = 'REJECTED'
    else if (c.cab.filter((x) => x.decision === 'APPROVE').length >= 2) c.status = 'CAB_APPROVED'
    return [200, c]
  }],
  ['post', re('\\/changes\\/([^/]+)\\/transition'), ({ params, body }) => {
    const c = changes.find((x) => x.changeId === params[0])
    if (!c) return err(404, 'NOT_FOUND', '변경을 찾을 수 없습니다.')
    const to = body?.to as Change['status']
    if (!CHANGE_TRANSITIONS[c.status].includes(to)) return err(409, 'CONFLICT', '허용되지 않는 전이입니다.')
    c.status = to
    if (to === 'DEPLOYED') c.deployedAt = nowIso()
    return [200, c]
  }],

  /* ── 정기 작업 ── */
  ['get', re('\\/projects\\/([^/]+)\\/routine-jobs\\/occurrences'), ({ config }) =>
    [200, occurrences(String(config.params?.from ?? today()), String(config.params?.to ?? addDays(today(), 30)))]],
  ['get', re('\\/projects\\/([^/]+)\\/routine-jobs'), () => [200, jobs]],
  ['post', re('\\/projects\\/([^/]+)\\/routine-jobs'), ({ body }) => {
    const p = body as RoutineJobPayload
    const j: RoutineJob = { jobId: mockId('rj'), name: p.name, rule: p.rule, checklist: p.checklist, owner: p.ownerId ? ASSIGNEES[p.ownerId] ?? { userId: p.ownerId, name: p.ownerId } : null }
    jobs.push(j)
    return [201, j]
  }],
  ['put', re('\\/routine-jobs\\/([^/]+)'), ({ params, body }) => {
    const j = jobs.find((x) => x.jobId === params[0])
    if (!j) return err(404, 'NOT_FOUND', '정기 작업을 찾을 수 없습니다.')
    const p = body as RoutineJobPayload
    Object.assign(j, { name: p.name, rule: p.rule, checklist: p.checklist, owner: p.ownerId ? ASSIGNEES[p.ownerId] ?? { userId: p.ownerId, name: p.ownerId } : null })
    return [200, j]
  }],
  ['delete', re('\\/routine-jobs\\/([^/]+)'), ({ params }) => {
    const i = jobs.findIndex((x) => x.jobId === params[0])
    if (i >= 0) jobs.splice(i, 1)
    return [204, null]
  }],
  ['post', re('\\/routine-jobs\\/([^/]+)\\/check'), ({ params, body, config }) => {
    const j = jobs.find((x) => x.jobId === params[0])
    if (!j) return err(404, 'NOT_FOUND', '정기 작업을 찾을 수 없습니다.')
    const date = String(body?.date)
    checks[`${j.jobId}|${date}`] = { done: !!body?.done, doneBy: body?.done ? userOf(config).name : null, note: body?.note ?? null }
    return [200, { jobId: j.jobId, name: j.name, date, ...checks[`${j.jobId}|${date}`] }]
  }],

  /* ── 운영 자산 ── */
  ['get', re('\\/projects\\/([^/]+)\\/ops-assets'), () => [200, assets.map(withDays)]],
  ['post', re('\\/projects\\/([^/]+)\\/ops-assets'), ({ body }) => {
    const a = { ...(body as OpsAssetPayload), assetId: mockId('oa') }
    assets.push(a)
    return [201, withDays(a)]
  }],
  ['put', re('\\/ops-assets\\/([^/]+)'), ({ params, body }) => {
    const a = assets.find((x) => x.assetId === params[0])
    if (!a) return err(404, 'NOT_FOUND', '자산을 찾을 수 없습니다.')
    Object.assign(a, body as OpsAssetPayload)
    return [200, withDays(a)]
  }],
  ['delete', re('\\/ops-assets\\/([^/]+)'), ({ params }) => {
    const i = assets.findIndex((x) => x.assetId === params[0])
    if (i >= 0) assets.splice(i, 1)
    return [204, null]
  }],

  /* ── KB ── */
  ['get', re('\\/projects\\/([^/]+)\\/kb\\/similar'), ({ config }) => [200, similarFor(null, String(config.params?.text ?? ''))]],
  ['get', re('\\/projects\\/([^/]+)\\/kb'), ({ config }) => {
    const q = String(config.params?.q ?? '').trim().toLowerCase()
    return [200, paginate(kbs.filter((k) => !q || `${k.title} ${k.content} ${k.tags.join(' ')}`.toLowerCase().includes(q)), config.params)]
  }],
  ['get', re('\\/kb\\/([^/]+)'), ({ params }) => {
    const k = kbs.find((x) => x.kbId === params[0])
    return k ? [200, k] : err(404, 'NOT_FOUND', 'KB 를 찾을 수 없습니다.')
  }],
  ['post', re('\\/projects\\/([^/]+)\\/kb'), ({ body }) => {
    const k: KbArticle = { ...(body as KbPayload), kbId: mockId('kb'), kbNo: nextNo('KB', ++kbSeq), updatedAt: nowIso() }
    kbs.unshift(k)
    return [201, k]
  }],
  ['put', re('\\/kb\\/([^/]+)'), ({ params, body }) => {
    const k = kbs.find((x) => x.kbId === params[0])
    if (!k) return err(404, 'NOT_FOUND', 'KB 를 찾을 수 없습니다.')
    Object.assign(k, body as KbPayload, { updatedAt: nowIso() })
    return [200, k]
  }],
  ['delete', re('\\/kb\\/([^/]+)'), ({ params }) => {
    const i = kbs.findIndex((x) => x.kbId === params[0])
    if (i >= 0) kbs.splice(i, 1)
    return [204, null]
  }],

  /* ── 월간 운영보고 ── */
  ['get', re('\\/projects\\/([^/]+)\\/monthly-reports'), () =>
    [200, Object.values(reports).sort((a, b) => b.month.localeCompare(a.month)).map((r) => ({ reportId: r.reportId, month: r.month, updatedAt: nowIso() }))]],
  ['post', re('\\/projects\\/([^/]+)\\/monthly-reports\\/generate'), ({ body }) => {
    const month = String(body?.month ?? '')
    if (!/^\d{4}-\d{2}$/.test(month)) return err(400, 'VALIDATION', '대상 월 형식이 올바르지 않습니다.')
    if (reports[`mr-${month}`]) return err(409, 'CONFLICT', '이미 그 달의 보고서가 있습니다.')
    reports[`mr-${month}`] = generateReport(month)
    return [201, reports[`mr-${month}`]]
  }],
  ['get', re('\\/monthly-reports\\/([^/]+)'), ({ params }) => {
    const r = reports[params[0]]
    return r ? [200, r] : err(404, 'NOT_FOUND', '보고서를 찾을 수 없습니다.')
  }],
  ['put', re('\\/monthly-reports\\/([^/]+)'), ({ params, body }) => {
    const r = reports[params[0]]
    if (!r) return err(404, 'NOT_FOUND', '보고서를 찾을 수 없습니다.')
    Object.assign(r, { summary: String(body?.summary ?? ''), plan: String(body?.plan ?? '') })
    return [200, r]
  }],

  /* ── 영향도 분석 ── */
  ['get', re('\\/systems\\/([^/]+)\\/impact'), ({ config }) => {
    const targetId = String(config.params?.targetId ?? '')
    if (!targetId) return err(400, 'VALIDATION', '대상을 지정하세요.')
    const column = config.params?.column ? String(config.params.column) : null
    const name = targetId.replace(/^nd-(tbl|fn|api|sql|sp)-/, '').toUpperCase().replace(/-/g, '_')
    const isTable = targetId.startsWith('nd-tbl')
    const base = structuredClone(IMPACT_TB_EMP)
    // 컬럼을 고르면 그 컬럼을 쓰는 경로만 남긴 것처럼 줄인다
    if (column) base.screens = base.screens.slice(0, 2).map((s) => ({ ...s, elements: s.elements.filter((e) => (e.crud ?? '').match(/[CU]/) || column === 'EMP_NO') }))
    const result = {
      ...base,
      target: { nodeId: targetId, nodeType: isTable ? 'TABLE' : targetId.startsWith('nd-api') ? 'API' : targetId.startsWith('nd-sql') ? 'SQL' : 'FN', name: isTable ? `TB_${name.replace(/^TB_/, '')}` : name, column },
      summary: { ...base.summary, screens: base.screens.length, elements: base.screens.reduce((n, s) => n + s.elements.length, 0) },
    }
    return [200, result]
  }],

  /* ── SI → SM 인수인계 ── */
  ['post', re('\\/projects\\/([^/]+)\\/handover\\/preview'), ({ body }) => {
    const p = body as HandoverRequest
    const sel = ctx.systems.filter((s) => p.systemIds.includes(s.systemId))
    if (!sel.length) return err(400, 'VALIDATION', '시스템을 하나 이상 고르세요.')
    return [200, {
      systems: sel.map((s) => ({ systemId: s.systemId, name: s.name, screens: s.screenCount, elements: s.screenCount * 9, edges: s.screenCount * 41 })),
      members: p.memberUserIds.length,
      traceLinks: p.include.traceLinks ? 38 : 0,
      notes: p.include.notes ? 17 : 0,
      deliverables: p.include.deliverables ? 12 : 0,
    }]
  }],
  ['post', re('\\/projects\\/([^/]+)\\/handover'), ({ params, body }) => {
    const p = body as HandoverRequest
    const sel = ctx.systems.filter((s) => p.systemIds.includes(s.systemId))
    if (!sel.length) return err(400, 'VALIDATION', '시스템을 하나 이상 고르세요.')
    let smProjectId: string
    if ('projectId' in p.target) {
      smProjectId = p.target.projectId
    } else {
      const np = p.target.newProject
      const created: Project = {
        projectId: mockId('prj'), type: 'SM', name: np.name, customer: np.customer, startDate: np.startDate, endDate: np.endDate,
        contractMm: 0, pmUserId: np.pmUserId, pmName: ASSIGNEES[np.pmUserId]?.name ?? np.pmUserId, originProjectId: params[0],
      }
      MOCK_PROJECTS.push(created)
      smProjectId = created.projectId
    }
    sel.forEach((s) => {
      const copyId = mockId('sys')
      ctx.systems.push({ ...structuredClone(s), systemId: copyId, projectId: smProjectId })
      ctx.menuTree[copyId] = structuredClone(ctx.menuTree[s.systemId] ?? [])
    })
    const screens = sel.reduce((n, s) => n + s.screenCount, 0)
    return [201, {
      smProjectId,
      copied: {
        systems: sel.length, screens, elements: screens * 9, edges: screens * 41, members: p.memberUserIds.length,
        traceLinks: p.include.traceLinks ? 38 : 0, notes: p.include.notes ? 17 : 0, deliverables: p.include.deliverables ? 12 : 0,
        srs: p.include.openDefectsAsSr ? 3 : 0,
      },
    }]
  }],
]
