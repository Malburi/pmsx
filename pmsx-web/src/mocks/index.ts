// VITE_USE_MOCK=true 일 때 axios adapter 로 동작하는 mock 서버.
// 응답 형태는 docs/08_API명세_R0.md 를 그대로 따른다 (envelope 없음, 오류는 {code, message}).
import { AxiosError, AxiosHeaders, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import type {
  AnalysisPatch, CrudMatrix, MenuNode, RunSummary, ScreenDetail, SearchHit, SystemCreate, SystemSummary,
} from '@/types/api'
import { HR0101_CAPTURE_SVG } from './fixtures/capture'
import {
  MOCK_MENU_TREE, MOCK_PROJECTS, MOCK_REPORT_HTML, MOCK_RUNS, MOCK_SYSTEMS, MOCK_USERS, genericScreen,
} from './fixtures/common'
import { HR0101_DETAIL, HR0101_NODES, HR0101_WORKFLOWS } from './fixtures/hr0101'
import { R1_HR_MENU } from './fixtures/r1'
import { PY0101_DETAIL, PY_MENU } from './fixtures/wireframe'
import { createAsisR1Routes } from './routes/asisR1'
import { commonRoutes, MOCK_LICENSE_INVALID_KEY } from './routes/common'
import { r2Routes } from './routes/r2'
import { createR3Routes } from './routes/r3'
import { err, walkMenu, type MockResult, type Route } from './shared'

const LATENCY_MS = 120
const LOGGED_OUT_KEY = 'pmsx-mock-logged-out'

// mock 상태 (새로고침 시 초기화)
const systems: SystemSummary[] = structuredClone(MOCK_SYSTEMS)
const runs: Record<string, RunSummary[]> = structuredClone(MOCK_RUNS)
// R1: 인사시스템 메뉴를 21화면으로 확장 (분석 진척·CRUD·이동 맵 확인용)
// 급여시스템(Nexacro)은 와이어프레임 캡처 화면 1개
const menuTree: Record<string, MenuNode[]> = {
  ...structuredClone(MOCK_MENU_TREE), 'sys-hr': structuredClone(R1_HR_MENU), 'sys-py': structuredClone(PY_MENU),
  // R3: SM 프로젝트 운영 시스템 — 인사시스템 메뉴·화면 ID 를 그대로 쓴다(인수인계 복사본)
  'sys-hr-op': structuredClone(R1_HR_MENU),
}
const screens: Record<string, ScreenDetail> = {
  [HR0101_DETAIL.screenId]: structuredClone(HR0101_DETAIL),
  [PY0101_DETAIL.screenId]: structuredClone(PY0101_DETAIL),
}
// 연결 보정(엣지 확정·추가)이 반영되도록 워크플로우·노드도 변경 가능한 복사본을 쓴다
const workflows = structuredClone(HR0101_WORKFLOWS)
const nodes = structuredClone(HR0101_NODES)

const findScreen = (screenId: string): ScreenDetail | null => {
  if (screens[screenId]) return screens[screenId]
  let found: ScreenDetail | null = null
  Object.entries(menuTree).forEach(([systemId, tree]) => {
    const sys = systems.find((s) => s.systemId === systemId)
    walkMenu(tree, [sys?.name ?? ''], (n, path) => {
      if (n.screen?.screenId === screenId) {
        found = genericScreen(screenId, n.screen.screenCode, n.name, path, n.screen.status, n.screen.kind)
      }
    })
  })
  if (found) screens[screenId] = found
  return found
}

const crudOf = (screenId: string): CrudMatrix => {
  const screen = findScreen(screenId)
  const tables = new Map<string, string>()
  const rows: CrudMatrix['rows'] = []
  screen?.elements.forEach((e) => {
    const wf = workflows[e.elementId]
    if (!wf) return
    const cells: Record<string, string> = {}
    wf.events.forEach((ev) => ev.steps.filter((s) => s.lane === 'DB' && s.nodeId).forEach((s) => {
      tables.set(s.nodeId as string, s.title)
      const prev = cells[s.nodeId as string] ?? ''
      cells[s.nodeId as string] = 'CRUD'.split('').filter((c) => prev.includes(c) || (s.crud ?? '').includes(c)).join('')
    }))
    if (Object.keys(cells).length) rows.push({ elementId: e.elementId, seqNo: e.seqNo, label: e.label, cells })
  })
  return { tables: [...tables].map(([nodeId, name]) => ({ nodeId, name })), rows }
}

const search = (systemId: string, q: string): SearchHit[] => {
  const needle = q.trim().toLowerCase()
  if (!needle) return []
  const hits: SearchHit[] = []
  const sys = systems.find((s) => s.systemId === systemId)
  walkMenu(menuTree[systemId] ?? [], [sys?.name ?? ''], (n, path) => {
    if (n.screen && (n.name + n.screen.screenCode).toLowerCase().includes(needle)) {
      hits.push({ kind: 'SCREEN', id: n.screen.screenId, label: `${n.name} (${n.screen.screenCode})`, sub: path.join(' › '), screenId: n.screen.screenId, elementId: null })
    }
  })
  if (systemId === 'sys-hr') {
    HR0101_DETAIL.elements.forEach((e) => {
      const text = `${e.label} ${e.elementCode} ${e.events.map((v) => v.handler).join(' ')}`.toLowerCase()
      if (text.includes(needle)) {
        hits.push({ kind: 'ELEMENT', id: e.elementId, label: `${e.label} (${e.elementCode})`, sub: '사원관리 HR0101', screenId: HR0101_DETAIL.screenId, elementId: e.elementId })
      }
    })
    Object.values(nodes).forEach((nd) => {
      if (nd.name.toLowerCase().includes(needle)) {
        const first = nd.usedBy[0]
        hits.push({ kind: 'NODE', id: nd.nodeId, label: nd.name, sub: nd.file ?? nd.key, screenId: first?.screenId ?? null, elementId: first?.elementId ?? null })
      }
    })
  }
  return hits.slice(0, 50)
}

const routes: Route[] = [
  // R1 경로를 먼저 둔다 — 분석 상태 PATCH·캡처 이미지는 R1 이 기본 동작을 대신한다
  ...createAsisR1Routes({ systems, menuTree, runs, workflows, nodes, findScreen: (id) => findScreen(id) }),
  ['post', /^\/api\/v1\/auth\/login$/, ({ body }) => {
    if (body?.loginId === 'locked01') return err(423, 'AUTH_LOCKED', '계정이 잠겼습니다.')
    if (sessionStorage.getItem(MOCK_LICENSE_INVALID_KEY) && body?.loginId !== 'admin') {
      return err(403, 'LICENSE_INVALID', '라이선스가 유효하지 않습니다.')
    }
    const u = MOCK_USERS[body?.loginId]
    if (!u || u.password !== body?.password) return err(401, 'AUTH_INVALID', '아이디 또는 비밀번호가 올바르지 않습니다.')
    sessionStorage.removeItem(LOGGED_OUT_KEY)
    const { password: _pw, ...user } = u
    return [200, { accessToken: `mock-token-${u.loginId}`, user }]
  }],
  // mock 모드는 화면 확인 편의를 위해 로그아웃 전까지 자동 로그인 상태로 둔다.
  ['post', /^\/api\/v1\/auth\/refresh$/, () =>
    sessionStorage.getItem(LOGGED_OUT_KEY) ? err(401, 'AUTH_EXPIRED', '세션이 만료되었습니다.') : [200, { accessToken: 'mock-token-admin' }]],
  ['post', /^\/api\/v1\/auth\/logout$/, () => { sessionStorage.setItem(LOGGED_OUT_KEY, '1'); return [204, null] }],
  ['get', /^\/api\/v1\/auth\/me$/, ({ config }) => {
    const token = String(config.headers?.Authorization ?? '').replace('Bearer mock-token-', '')
    const { password: _pw, ...user } = MOCK_USERS[token] ?? MOCK_USERS.admin
    return [200, user]
  }],
  ['get', /^\/api\/v1\/projects$/, () => [200, MOCK_PROJECTS]],
  ['get', /^\/api\/v1\/projects\/([^/]+)\/systems$/, ({ params }) => [200, systems.filter((s) => s.projectId === params[0])]],
  ['post', /^\/api\/v1\/projects\/([^/]+)\/systems$/, ({ params, body }) => {
    const p = body as SystemCreate
    if (systems.some((s) => s.projectId === params[0] && s.code === p.code)) return err(409, 'CONFLICT', '이미 등록된 시스템 코드입니다.')
    const created: SystemSummary = { ...p, systemId: `sys-${p.code.toLowerCase()}`, projectId: params[0], screenCount: 0, doneCount: 0, lastRun: null }
    systems.push(created)
    runs[created.systemId] = []
    menuTree[created.systemId] = []
    return [201, created]
  }],
  ['get', /^\/api\/v1\/systems\/([^/]+)$/, ({ params }) => {
    const s = systems.find((x) => x.systemId === params[0])
    return s ? [200, s] : err(404, 'NOT_FOUND', '시스템을 찾을 수 없습니다.')
  }],
  ['get', /^\/api\/v1\/systems\/([^/]+)\/runs$/, ({ params }) => [200, runs[params[0]] ?? []]],
  ['post', /^\/api\/v1\/systems\/([^/]+)\/runs$/, ({ params, body }) => {
    const file = (body as FormData)?.get?.('file') as File | null
    if (!file || !file.name.toLowerCase().endsWith('.zip')) return err(400, 'PACKAGE_INVALID', 'zip 패키지가 아닙니다.')
    const list = runs[params[0]] ?? (runs[params[0]] = [])
    const seq = (list[0]?.seq ?? 0) + 1
    const now = new Date().toISOString()
    const run: RunSummary = {
      runId: `run-${params[0]}-${seq}`, systemId: params[0], seq, collectedAt: now, uploadedAt: now,
      stats: { menus: 9, screens: 7, captured: 7, elements: 86, edges: 420, blockedRequests: 5, unmatchedElements: 2 },
      diff: { screensAdded: 0, screensRemoved: 0, elementsAdded: 2, elementsRemoved: 0, edgesAdded: 8, edgesRemoved: 0 },
    }
    list.unshift(run)
    const sys = systems.find((s) => s.systemId === params[0])
    if (sys) sys.lastRun = { runId: run.runId, seq, collectedAt: now }
    return [201, run]
  }],
  ['get', /^\/api\/v1\/runs\/([^/]+)\/report$/, () => [200, new Blob([MOCK_REPORT_HTML], { type: 'text/html' })]],
  ['get', /^\/api\/v1\/systems\/([^/]+)\/menu-tree$/, ({ params }) => [200, menuTree[params[0]] ?? []]],
  ['get', /^\/api\/v1\/systems\/([^/]+)\/search$/, ({ params, config }) => [200, search(params[0], String(config.params?.q ?? ''))]],
  ['get', /^\/api\/v1\/screens\/([^/]+)\/crud$/, ({ params }) => [200, crudOf(params[0])]],
  ['patch', /^\/api\/v1\/screens\/([^/]+)\/analysis$/, ({ params, body }) => {
    const s = findScreen(params[0])
    if (!s) return err(404, 'NOT_FOUND', '화면을 찾을 수 없습니다.')
    const p = body as AnalysisPatch
    if (p.status) s.analysis.status = p.status
    s.analysis.updatedAt = new Date().toISOString()
    Object.values(menuTree).forEach((tree) => walkMenu(tree, [], (n) => {
      if (n.screen?.screenId === s.screenId && p.status) n.screen.status = p.status
    }))
    return [200, s.analysis]
  }],
  ['get', /^\/api\/v1\/screens\/([^/]+)$/, ({ params }) => {
    const s = findScreen(params[0])
    return s ? [200, s] : err(404, 'NOT_FOUND', '화면을 찾을 수 없습니다.')
  }],
  ['get', /^\/api\/v1\/captures\/([^/]+)\/image$/, () => [200, new Blob([HR0101_CAPTURE_SVG], { type: 'image/svg+xml' })]],
  ['get', /^\/api\/v1\/elements\/([^/]+)\/workflow$/, ({ params }) =>
    [200, workflows[params[0]] ?? { elementId: params[0], events: [] }]],
  ['get', /^\/api\/v1\/nodes\/([^/]+)$/, ({ params }) => {
    const n = nodes[params[0]]
    return n ? [200, n] : [200, { nodeId: params[0], nodeType: 'FN', key: params[0], name: params[0], lang: null, file: null, lineStart: null, lineEnd: null, snippet: null, usedBy: [] }]
  }],
  ...createR3Routes({ systems, menuTree }),
  ...r2Routes,
  ...commonRoutes,
]

const parseBody = (data: unknown) => {
  if (typeof data === 'string') {
    try { return JSON.parse(data) } catch { return data }
  }
  return data
}

export const mockAdapter = async (config: InternalAxiosRequestConfig): Promise<AxiosResponse> => {
  await new Promise((r) => setTimeout(r, LATENCY_MS))
  const method = (config.method ?? 'get').toLowerCase()
  const path = (config.url ?? '').replace(/^https?:\/\/[^/]+/, '').split('?')[0]
  // 일치하는 경로를 차례로 시도한다 — 핸들러가 MOCK_NEXT([-1]) 를 주면 다음 경로로 넘긴다
  let result: MockResult | null = null
  for (const route of routes.filter(([m, re]) => m === method && re.test(path))) {
    const r = await route[2]({ params: (path.match(route[1]) ?? []).slice(1), config, body: parseBody(config.data) })
    if (r[0] !== -1) { result = r; break }
  }
  const [status, data] = result ?? err(404, 'NOT_FOUND', `mock 경로 없음: ${method.toUpperCase()} ${path}`)
  // 실제 네트워크처럼 매 응답을 복사본으로 준다 (mock 상태 객체를 화면이 직접 공유하지 않게)
  const body = data instanceof Blob || data === null ? data : structuredClone(data)
  const response: AxiosResponse = { data: body, status, statusText: String(status), headers: new AxiosHeaders(), config }
  if (status >= 400) {
    throw new AxiosError(`mock ${status}`, String(status), config, null, response)
  }
  return response
}
