// docs/11 (R1) mock 경로 — 핫스팟 편집·연결 보정·메모·분석 진척·테이블/ERD·시스템 CRUD·이동 맵·호출 트리·회차 diff·산출물
import type {
  AnalysisAssign, AnalysisHistoryItem, AnalysisPatch, AnalysisRow, AnalysisStatus, CallTreeNode, CrudMatrix, EdgeCreate, EdgeInfo,
  EdgePatch, ElementPayload, ElementSummary, Lane, MenuNode, NodeDetail, NodeSummary, NodeType, NodeUsage, Note, Relation,
  RelationCreate, RunDiffDetail, RunSummary, ScreenDetail, SystemSummary, TableDetail, UserRef, Workflow, WorkflowStep,
} from '@/types/api'
import type { UserLookup } from '@/types/common'
import { MOCK_ADMIN_USERS } from '../fixtures/admin'
import {
  R1_DELIVERABLE_TYPES, R1_HISTORY, R1_NAV_LINKS, R1_NOTES, R1_OWNERS, R1_RELATIONS, R1_SCREEN_CRUD, R1_TABLE_USAGE, R1_TABLES,
  toErdTable, type MockTable,
} from '../fixtures/r1'
import { PY0101_WIREFRAME_SVG } from '../fixtures/wireframe'
import { MOCK_NEXT, err, mockId, paginate, tokenUser, walkMenu, type Route } from '../shared'

export interface AsisMockContext {
  systems: SystemSummary[]
  menuTree: Record<string, MenuNode[]>
  runs: Record<string, RunSummary[]>
  workflows: Record<string, Workflow>
  nodes: Record<string, NodeDetail>
  findScreen: (screenId: string) => ScreenDetail | null
}

const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
const PPTX_MIME = 'application/vnd.openxmlformats-officedocument.presentationml.presentation'
const STATUSES: AnalysisStatus[] = ['AUTO', 'WORKING', 'REVIEW', 'DONE']

// 결정적 의사난수 (화면마다 같은 값)
const hash = (s: string) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7)

const NODE_LANE: Record<NodeType, Lane> = { FN: 'FRONT', API: 'API', SQL: 'SQL', SP: 'SQL', TABLE: 'DB', SCREEN: 'UI' }
const LANE_TYPE: Record<Lane, NodeType> = { UI: 'SCREEN', FRONT: 'FN', API: 'API', SERVER: 'FN', SQL: 'SQL', DB: 'TABLE', POST: 'FN' }

export const createAsisR1Routes = (ctx: AsisMockContext): Route[] => {
  // ─────────── mock 상태 ───────────
  const owners = structuredClone(R1_OWNERS)
  const history: Record<string, AnalysisHistoryItem[]> = structuredClone(R1_HISTORY)
  const notes: Note[] = structuredClone(R1_NOTES)
  const tables: MockTable[] = structuredClone(R1_TABLES)
  let relations: Relation[] = structuredClone(R1_RELATIONS)
  const captureBlobs = new Map<string, Blob>()
  const updatedAt: Record<string, string> = {}

  const me = (config: Parameters<Route[2]>[0]['config']): UserRef => {
    const login = tokenUser(config) || 'admin'
    const u = MOCK_ADMIN_USERS.find((x) => x.loginId === login)
    return u ? { userId: u.userId, name: u.name } : { userId: 'u-admin', name: '관리자' }
  }

  const allScreens = (systemId: string) => {
    const out: { node: MenuNode; path: string[]; top: MenuNode }[] = []
    const sys = ctx.systems.find((s) => s.systemId === systemId)
    ;(ctx.menuTree[systemId] ?? []).forEach((top) => walkMenu([top], [sys?.name ?? ''], (n, path) => { if (n.screen) out.push({ node: n, path, top }) }))
    return out
  }

  const setTreeStatus = (screenId: string, status: AnalysisStatus) => {
    Object.values(ctx.menuTree).forEach((tree) => walkMenu(tree, [], (n) => { if (n.screen?.screenId === screenId) n.screen.status = status }))
  }

  const analysisRow = (n: MenuNode, path: string[]): AnalysisRow => {
    const s = n.screen as NonNullable<MenuNode['screen']>
    const h = hash(s.screenId)
    const detail = s.screenId === 'scr-hr0101' ? ctx.findScreen(s.screenId) : null
    return {
      screenId: s.screenId, screenCode: s.screenCode, name: n.name, menuPath: path, status: s.status,
      owner: owners[s.screenId]?.owner ?? null, reviewer: owners[s.screenId]?.reviewer ?? null,
      elementCount: detail ? detail.elements.length : 6 + (h % 14),
      unmatchedCount: detail ? detail.elements.filter((e) => e.matched === 'dynamic-only').length : (s.status === 'DONE' ? 0 : h % 3),
      updatedAt: updatedAt[s.screenId] ?? `2026-10-${String(1 + (h % 12)).padStart(2, '0')}T${String(9 + (h % 8)).padStart(2, '0')}:00:00+09:00`,
    }
  }

  // 워크플로우 단계 목록 전부 (요소·이벤트별)
  const allStepLists = () => Object.values(ctx.workflows).flatMap((wf) => wf.events.map((ev) => ({ wf, steps: ev.steps })))

  const findEdgeStep = (edgeId: string) => {
    for (const { wf, steps } of allStepLists()) {
      const i = steps.findIndex((s) => s.edge?.edgeId === edgeId)
      if (i >= 0) return { wf, steps, i }
    }
    return null
  }

  const edgeInfo = (step: WorkflowStep, fromId: string): EdgeInfo => ({
    edgeId: step.edge?.edgeId ?? '', fromId, toId: step.nodeId ?? '', kind: step.edge?.kind ?? 'calls', event: null,
    source: step.edge?.source ?? 'manual', confidence: step.edge?.confidence ?? 1, status: step.edge?.status ?? 'active',
    confirmed: !!step.edge?.confirmed, condition: step.edge?.condition ?? null, crud: step.crud,
  })

  // 하위 단계 범위 [i, end)
  const subtreeEnd = (steps: WorkflowStep[], i: number) => {
    let j = i + 1
    while (j < steps.length && steps[j].depth > steps[i].depth) j += 1
    return j
  }

  const elementLabel = (elementId: string) => {
    const screen = ctx.findScreen('scr-hr0101')
    const el = screen?.elements.find((e) => e.elementId === elementId)
    return el ? el.label : elementId
  }

  const fileOf = (nodeId: string | null) => (nodeId ? ctx.nodes[nodeId]?.file ?? null : null)

  const callTree = (nodeId: string, direction: 'down' | 'up', depth: number): CallTreeNode => {
    const self = ctx.nodes[nodeId]
    const seen = new Set<string>()
    let count = 0
    const occurrences = (id: string) => allStepLists().flatMap(({ wf, steps }) => steps.map((s, i) => ({ wf, steps, i, s })).filter((o) => o.s.nodeId === id))

    const down = (id: string, level: number): CallTreeNode[] => {
      const occ = occurrences(id)[0]
      if (!occ) return []
      const { steps, i } = occ
      const end = subtreeEnd(steps, i)
      const kids = steps.slice(i + 1, end).filter((s) => s.depth === steps[i].depth + 1)
      return kids.map((k) => {
        count += 1
        const kid: CallTreeNode = { nodeId: k.nodeId ?? `${id}-x`, nodeType: LANE_TYPE[k.lane], name: k.title, file: fileOf(k.nodeId), edgeKind: k.edge?.kind ?? null, truncated: false, children: [] }
        if (!k.nodeId || seen.has(k.nodeId)) return kid
        seen.add(k.nodeId)
        if (level >= depth || count > 200) { kid.truncated = down(k.nodeId, level + 1).length > 0; return kid }
        kid.children = down(k.nodeId, level + 1)
        return kid
      })
    }

    const up = (id: string, level: number): CallTreeNode[] => {
      const parents = new Map<string, CallTreeNode>()
      occurrences(id).forEach(({ wf, steps, i }) => {
        let p = i - 1
        while (p >= 0 && steps[p].depth >= steps[i].depth) p -= 1
        if (p < 0) return
        const ps = steps[p]
        const key = ps.nodeId ?? `el:${wf.elementId}`
        if (parents.has(key)) return
        const isElement = !ps.nodeId
        const node: CallTreeNode = {
          nodeId: ps.nodeId ?? wf.elementId, nodeType: isElement ? 'SCREEN' : LANE_TYPE[ps.lane],
          name: isElement ? `사원관리 › ${elementLabel(wf.elementId)}` : ps.title, file: fileOf(ps.nodeId),
          edgeKind: steps[i].edge?.kind ?? null, truncated: false, children: [],
        }
        if (ps.nodeId && !seen.has(ps.nodeId) && level < depth) {
          seen.add(ps.nodeId)
          node.children = up(ps.nodeId, level + 1)
        }
        parents.set(key, node)
      })
      return [...parents.values()]
    }

    const occ = occurrences(nodeId)[0]
    return {
      nodeId, nodeType: self?.nodeType ?? (occ ? LANE_TYPE[occ.s.lane] : 'FN'), name: self?.name ?? occ?.s.title ?? nodeId,
      file: self?.file ?? null, edgeKind: null, truncated: false,
      children: direction === 'down' ? down(nodeId, 1) : up(nodeId, 1),
    }
  }

  const usage = (nodeId: string): NodeUsage[] => {
    const out: NodeUsage[] = []
    allStepLists().forEach(({ wf, steps }) => {
      const i = steps.findIndex((s) => s.nodeId === nodeId)
      if (i < 0) return
      const path: string[] = [steps[i].title]
      let cur = i
      while (cur > 0) {
        let p = cur - 1
        while (p >= 0 && steps[p].depth >= steps[cur].depth) p -= 1
        if (p < 0) break
        path.unshift(steps[p].title)
        cur = p
      }
      if (!out.some((u) => u.elementId === wf.elementId)) {
        out.push({ screenId: 'scr-hr0101', screenName: '사원관리', elementId: wf.elementId, elementLabel: elementLabel(wf.elementId), path })
      }
    })
    return out
  }

  const nodeSummaries = (): NodeSummary[] => {
    const map = new Map<string, NodeSummary>()
    Object.values(ctx.nodes).forEach((n) => map.set(n.nodeId, { nodeId: n.nodeId, nodeType: n.nodeType, key: n.key, name: n.name, file: n.file }))
    allStepLists().forEach(({ steps }) => steps.forEach((s) => {
      if (s.nodeId && !map.has(s.nodeId)) map.set(s.nodeId, { nodeId: s.nodeId, nodeType: LANE_TYPE[s.lane], key: s.nodeId, name: s.title, file: null })
    }))
    tables.forEach((tb) => { if (!map.has(tb.tableId)) map.set(tb.tableId, { nodeId: tb.tableId, nodeType: 'TABLE', key: `tbl:${tb.schema}.${tb.name}`, name: tb.name, file: null }) })
    return [...map.values()]
  }

  const tableDetail = (tb: MockTable): TableDetail => {
    const used: TableDetail['usedBy'] = []
    Object.values(ctx.workflows).forEach((wf) => wf.events.forEach((ev) => ev.steps.forEach((s, i) => {
      if (s.nodeId !== tb.tableId) return
      let p = i - 1
      while (p >= 0 && ev.steps[p].depth >= s.depth) p -= 1
      used.push({ screenId: 'scr-hr0101', screenName: '사원관리', elementId: wf.elementId, elementLabel: elementLabel(wf.elementId), crud: s.crud ?? 'R', sqlName: p >= 0 ? ev.steps[p].title : '-' })
    })))
    ;(R1_TABLE_USAGE[tb.tableId] ?? []).forEach((u) => used.push({ ...u, elementId: null, elementLabel: null }))
    const rels = relations.filter((r) => r.childTableId === tb.tableId || r.parentTableId === tb.tableId)
    return {
      tableId: tb.tableId, schema: tb.schema, name: tb.name, comment: tb.comment, subjectArea: tb.subjectArea,
      columnCount: tb.columns.length, usedByScreens: new Set(used.map((u) => u.screenId)).size,
      columns: tb.columns, indexes: tb.indexes, relations: rels, usedBy: used,
    }
  }

  const summaryOf = (tb: MockTable) => {
    const { columns: _c, indexes: _i, relations: _r, usedBy: _u, ...summary } = tableDetail(tb)
    return summary
  }

  const crudForScreen = (screenId: string): Record<string, string> => {
    if (screenId !== 'scr-hr0101') return R1_SCREEN_CRUD[screenId] ?? {}
    const cells: Record<string, string> = {}
    Object.values(ctx.workflows).forEach((wf) => wf.events.forEach((ev) => ev.steps.filter((s) => s.lane === 'DB' && s.nodeId && s.edge?.status !== 'rejected').forEach((s) => {
      const prev = cells[s.nodeId as string] ?? ''
      cells[s.nodeId as string] = 'CRUD'.split('').filter((c) => prev.includes(c) || (s.crud ?? '').includes(c)).join('')
    })))
    return cells
  }

  const systemCrud = (systemId: string, by: string, menuId: string | undefined): CrudMatrix => {
    const screens = allScreens(systemId).filter((x) => !menuId || x.top.menuId === menuId)
    const rows: CrudMatrix['rows'] = []
    if (by === 'element') {
      screens.forEach(({ node, path }) => {
        const s = node.screen as NonNullable<MenuNode['screen']>
        if (s.screenId === 'scr-hr0101') {
          const detail = ctx.findScreen(s.screenId)
          detail?.elements.forEach((e) => {
            const wf = ctx.workflows[e.elementId]
            if (!wf) return
            const cells: Record<string, string> = {}
            wf.events.forEach((ev) => ev.steps.filter((st) => st.lane === 'DB' && st.nodeId).forEach((st) => {
              const prev = cells[st.nodeId as string] ?? ''
              cells[st.nodeId as string] = 'CRUD'.split('').filter((c) => prev.includes(c) || (st.crud ?? '').includes(c)).join('')
            }))
            if (Object.keys(cells).length) rows.push({ elementId: e.elementId, seqNo: e.seqNo, label: `${e.seqNo}. ${e.label}`, sub: `${node.name} ${s.screenCode}`, screenId: s.screenId, cells })
          })
          return
        }
        // 다른 화면은 조회·저장 버튼 두 개로 나눠 보여 준다
        const all = R1_SCREEN_CRUD[s.screenId] ?? {}
        const read = Object.fromEntries(Object.keys(all).map((k) => [k, 'R']))
        const write = Object.fromEntries(Object.entries(all).map(([k, v]) => [k, v.replace('R', '')]).filter(([, v]) => v))
        if (Object.keys(read).length) rows.push({ elementId: `el-${s.screenId}-btnSearch`, seqNo: 1, label: '1. 조회', sub: `${node.name} ${s.screenCode} · ${path.slice(1, -1).join(' › ')}`, screenId: s.screenId, cells: read })
        if (Object.keys(write).length) rows.push({ elementId: `el-${s.screenId}-btnSave`, seqNo: 2, label: '2. 저장', sub: `${node.name} ${s.screenCode}`, screenId: s.screenId, cells: write })
      })
    } else {
      screens.forEach(({ node, path }) => {
        const s = node.screen as NonNullable<MenuNode['screen']>
        rows.push({ elementId: s.screenId, seqNo: 0, label: `${node.name} (${s.screenCode})`, sub: path.slice(1, -1).join(' › '), cells: crudForScreen(s.screenId) })
      })
    }
    const used = new Set(rows.flatMap((r) => Object.keys(r.cells)))
    return { tables: tables.filter((tb) => used.has(tb.tableId)).map((tb) => ({ nodeId: tb.tableId, name: tb.name })), rows }
  }

  const runDiff = (runId: string): RunDiffDetail | null => {
    const all = Object.values(ctx.runs).flat()
    const run = all.find((r) => r.runId === runId)
    if (!run) return null
    const list = ctx.runs[run.systemId] ?? []
    const prev = list.find((r) => r.seq === run.seq - 1) ?? null
    const screens = allScreens(run.systemId)
    const pick = (n: number, offset: number) => screens.slice(offset, offset + n).map(({ node }) => ({
      id: node.screen?.screenId ?? null, key: `scr:HR:${node.screen?.screenCode}`, label: `${node.name} (${node.screen?.screenCode})`,
    }))
    const elementRefs = (n: number, prefix: string) => Array.from({ length: Math.min(n, 30) }, (_, i) => {
      const { node } = screens[(i * 3) % Math.max(1, screens.length)]
      return { id: null, key: `el:HR:${node.screen?.screenCode}#${prefix}${i + 1}`, label: `${node.name} › ${prefix}${i + 1}` }
    })
    const samples: RunDiffDetail['edges']['samples'] = [
      { from: 'fn:js:/js/hr/HR0105.js#fn_save', to: 'api:POST /hr/edu/save.do', kind: 'request', change: 'added' },
      { from: 'api:POST /hr/edu/save.do', to: 'fn:java:EduController#saveEdu', kind: 'handles', change: 'added' },
      { from: 'sql:EduMapper.insertEduHist', to: 'tbl:HR.TB_EDU_HIST', kind: 'accesses', change: 'added' },
      { from: 'fn:js:/js/hr/HR0101.js#fn_print', to: 'api:GET /hr/emp/print.do', kind: 'request', change: 'removed' },
    ].slice(0, run.diff.edgesAdded + run.diff.edgesRemoved > 0 ? 4 : 0) as RunDiffDetail['edges']['samples']
    return {
      runId, prevRunId: prev?.runId ?? null,
      screens: { added: pick(run.diff.screensAdded, Math.max(0, screens.length - run.diff.screensAdded)), removed: [] },
      elements: { added: elementRefs(run.diff.elementsAdded, 'btnNew'), removed: elementRefs(run.diff.elementsRemoved, 'btnOld') },
      edges: { added: run.diff.edgesAdded, removed: run.diff.edgesRemoved, samples },
    }
  }

  const newStep = (node: NodeSummary, payload: EdgeCreate, depth: number): WorkflowStep => ({
    nodeId: node.nodeId,
    lane: node.nodeType === 'FN' && /^[A-Z]\w*\./.test(node.name) ? 'SERVER' : NODE_LANE[node.nodeType],
    title: node.name, subtitle: node.file, depth,
    crud: node.nodeType === 'TABLE' ? (payload.crud ?? 'R') : null, columns: null, hasSource: !!ctx.nodes[node.nodeId]?.snippet,
    edge: { edgeId: mockId('edge'), kind: payload.kind, source: 'manual', confidence: 1, condition: payload.condition ?? null, evidence: null, confirmed: true, status: 'active' },
  })

  const resolveTarget = (payload: EdgeCreate): NodeSummary | null => {
    if (payload.to.nodeId) return nodeSummaries().find((n) => n.nodeId === payload.to.nodeId) ?? null
    const nn = payload.to.newNode
    if (!nn?.name?.trim()) return null
    const nodeId = mockId('nd')
    ctx.nodes[nodeId] = { nodeId, nodeType: nn.nodeType, key: `manual:${nn.name}`, name: nn.name, lang: null, file: nn.file ?? null, lineStart: null, lineEnd: null, snippet: null, usedBy: [] }
    return { nodeId, nodeType: nn.nodeType, key: `manual:${nn.name}`, name: nn.name, file: nn.file ?? null }
  }

  // 화면 캐시 — findScreen 으로 열 수 있는 화면들 (요소 편집 대상)
  const screensCache = (): ScreenDetail[] => {
    const out: ScreenDetail[] = []
    Object.values(ctx.menuTree).forEach((tree) => walkMenu(tree, [], (n) => {
      if (n.screen) { const s = ctx.findScreen(n.screen.screenId); if (s) out.push(s) }
    }))
    return out
  }

  return [
    /* ── 사용자 조회 (docs/10 v0.2) ─────────────────────── */
    ['get', /^\/api\/v1\/users\/lookup$/, ({ config }) => {
      const q = String(config.params?.q ?? '').trim().toLowerCase()
      const list: UserLookup[] = MOCK_ADMIN_USERS
        .filter((u) => !u.locked && (!q || `${u.name} ${u.loginId}`.toLowerCase().includes(q)))
        .slice(0, 20)
        .map((u) => ({ userId: u.userId, loginId: u.loginId, name: u.name, orgType: u.orgType }))
      return [200, list]
    }],

    /* ── 핫스팟 편집 ─────────────────────────────────────── */
    ['post', /^\/api\/v1\/screens\/([^/]+)\/elements$/, ({ params, body }) => {
      const screen = ctx.findScreen(params[0])
      if (!screen) return err(404, 'NOT_FOUND', '화면을 찾을 수 없습니다.')
      const p = body as ElementPayload
      if (screen.elements.some((e) => e.elementCode === p.elementCode)) return err(409, 'CONFLICT', '같은 요소 ID 가 이미 있습니다.')
      const el: ElementSummary = {
        elementId: mockId('el'), seqNo: Math.max(0, ...screen.elements.map((e) => e.seqNo)) + 1, elementCode: p.elementCode, label: p.label,
        type: p.type, box: p.box, binding: p.binding ?? null, matched: 'full', danger: false, events: [], manual: true, hidden: false,
      }
      screen.elements.push(el)
      return [201, el]
    }],
    ['put', /^\/api\/v1\/elements\/([^/]+)$/, ({ params, body }) => {
      for (const screen of Object.values(screensCache())) {
        const el = screen.elements.find((e) => e.elementId === params[0])
        if (el) { Object.assign(el, body, { manual: true }); return [200, el] }
      }
      return err(404, 'NOT_FOUND', '요소를 찾을 수 없습니다.')
    }],
    ['delete', /^\/api\/v1\/elements\/([^/]+)$/, ({ params }) => {
      for (const screen of Object.values(screensCache())) {
        const i = screen.elements.findIndex((e) => e.elementId === params[0])
        if (i < 0) continue
        if (screen.elements[i].manual) screen.elements.splice(i, 1)
        else screen.elements[i].hidden = true
        return [204, null]
      }
      return err(404, 'NOT_FOUND', '요소를 찾을 수 없습니다.')
    }],
    ['post', /^\/api\/v1\/elements\/([^/]+)\/restore$/, ({ params }) => {
      for (const screen of Object.values(screensCache())) {
        const el = screen.elements.find((e) => e.elementId === params[0])
        if (el) { el.hidden = false; return [200, el] }
      }
      return err(404, 'NOT_FOUND', '요소를 찾을 수 없습니다.')
    }],
    ['post', /^\/api\/v1\/screens\/([^/]+)\/captures$/, ({ params, body }) => {
      const screen = ctx.findScreen(params[0])
      const file = body instanceof FormData ? body.get('file') : null
      if (!screen || !(file instanceof Blob)) return err(400, 'VALIDATION', '캡처 이미지가 없습니다.')
      const captureId = mockId('cap')
      captureBlobs.set(captureId, file)
      const width = Number(body.get('width')) || screen.capture?.width || 1280
      const height = Number(body.get('height')) || screen.capture?.height || 800
      screen.capture = { captureId, width, height, collectedAt: new Date().toISOString(), runSeq: screen.capture?.runSeq ?? 0 }
      return [201, screen.capture]
    }],
    ['get', /^\/api\/v1\/captures\/([^/]+)\/image$/, ({ params }) => {
      if (params[0] === 'cap-py0101-wf') return [200, new Blob([PY0101_WIREFRAME_SVG], { type: 'image/svg+xml' })]
      const blob = captureBlobs.get(params[0])
      return blob ? [200, blob] : MOCK_NEXT
    }],

    /* ── 노드 검색·연결 보정 ─────────────────────────────── */
    ['get', /^\/api\/v1\/systems\/([^/]+)\/nodes$/, ({ config }) => {
      const q = String(config.params?.q ?? '').trim().toLowerCase()
      const type = config.params?.type as NodeType | undefined
      const list = nodeSummaries().filter((n) => (!type || n.nodeType === type) && (!q || `${n.name} ${n.file ?? ''} ${n.key}`.toLowerCase().includes(q)))
      return [200, paginate(list, config.params)]
    }],
    ['post', /^\/api\/v1\/edges$/, ({ body }) => {
      const p = body as EdgeCreate
      const target = resolveTarget(p)
      if (!target) return err(400, 'VALIDATION', '연결 대상을 지정하세요.')
      if (p.from.elementId) {
        const wf = ctx.workflows[p.from.elementId] ?? (ctx.workflows[p.from.elementId] = { elementId: p.from.elementId, events: [] })
        let ev = wf.events.find((e) => e.event === (p.event ?? 'click'))
        if (!ev) {
          ev = { event: p.event ?? 'click', steps: [{ nodeId: null, lane: 'UI', title: `[${elementLabel(p.from.elementId)}] ${p.event ?? 'click'}`, subtitle: null, depth: 0, crud: null, columns: null, hasSource: false, edge: null }] }
          wf.events.push(ev)
        }
        const step = newStep(target, p, 1)
        ev.steps.push(step)
        return [201, edgeInfo(step, p.from.elementId)]
      }
      let created: WorkflowStep | null = null
      allStepLists().forEach(({ steps }) => {
        const i = steps.findIndex((s) => s.nodeId === p.from.nodeId)
        if (i < 0) return
        const step = newStep(target, p, steps[i].depth + 1)
        steps.splice(subtreeEnd(steps, i), 0, step)
        created ??= step
      })
      return created ? [201, edgeInfo(created, p.from.nodeId ?? '')] : err(404, 'NOT_FOUND', '출발 노드를 찾을 수 없습니다.')
    }],
    ['patch', /^\/api\/v1\/edges\/([^/]+)$/, ({ params, body }) => {
      const hit = findEdgeStep(params[0])
      if (!hit) return err(404, 'NOT_FOUND', '연결을 찾을 수 없습니다.')
      const step = hit.steps[hit.i]
      const p = body as EdgePatch
      if (!step.edge) return err(404, 'NOT_FOUND', '연결을 찾을 수 없습니다.')
      if (p.confirmed !== undefined) step.edge.confirmed = p.confirmed
      if (p.status) step.edge.status = p.status
      if (p.condition !== undefined) step.edge.condition = p.condition || null
      if (p.crud !== undefined) step.crud = p.crud || null
      return [200, edgeInfo(step, hit.steps[Math.max(0, hit.i - 1)].nodeId ?? hit.wf.elementId)]
    }],
    ['delete', /^\/api\/v1\/edges\/([^/]+)$/, ({ params }) => {
      const hit = findEdgeStep(params[0])
      if (!hit) return err(404, 'NOT_FOUND', '연결을 찾을 수 없습니다.')
      if (hit.steps[hit.i].edge?.source !== 'manual') return err(400, 'VALIDATION', '수집된 연결은 삭제할 수 없습니다. 기각하세요.')
      hit.steps.splice(hit.i, subtreeEnd(hit.steps, hit.i) - hit.i)
      return [204, null]
    }],

    /* ── 메모 ────────────────────────────────────────────── */
    ['get', /^\/api\/v1\/notes$/, ({ config }) => {
      const { targetType, targetId } = config.params ?? {}
      return [200, notes.filter((n) => n.targetType === targetType && n.targetId === targetId).sort((a, b) => b.createdAt.localeCompare(a.createdAt))]
    }],
    ['post', /^\/api\/v1\/notes$/, ({ body, config }) => {
      if (!body?.content?.trim()) return err(400, 'VALIDATION', '내용을 입력하세요.', { content: '필수 입력 항목입니다.' })
      if (body.content.length > 20000) return err(400, 'VALIDATION', '메모는 20,000자까지 쓸 수 있습니다.')
      const now = new Date().toISOString()
      const note: Note = { noteId: mockId('note'), targetType: body.targetType, targetId: body.targetId, content: body.content, author: me(config), createdAt: now, updatedAt: now }
      notes.push(note)
      return [201, note]
    }],
    ['put', /^\/api\/v1\/notes\/([^/]+)$/, ({ params, body }) => {
      const note = notes.find((n) => n.noteId === params[0])
      if (!note) return err(404, 'NOT_FOUND', '메모를 찾을 수 없습니다.')
      note.content = body.content
      note.updatedAt = new Date().toISOString()
      return [200, note]
    }],
    ['delete', /^\/api\/v1\/notes\/([^/]+)$/, ({ params }) => {
      const i = notes.findIndex((n) => n.noteId === params[0])
      if (i < 0) return err(404, 'NOT_FOUND', '메모를 찾을 수 없습니다.')
      notes.splice(i, 1)
      return [204, null]
    }],

    /* ── 분석 상태·진척 ──────────────────────────────────── */
    ['patch', /^\/api\/v1\/screens\/([^/]+)\/analysis$/, ({ params, body, config }) => {
      const s = ctx.findScreen(params[0])
      if (!s) return err(404, 'NOT_FOUND', '화면을 찾을 수 없습니다.')
      const p = body as AnalysisPatch
      const before = s.analysis.status
      const o = owners[s.screenId] ?? (owners[s.screenId] = { owner: null, reviewer: null })
      if (p.ownerId !== undefined) o.owner = p.ownerId ? { userId: p.ownerId, name: MOCK_ADMIN_USERS.find((u) => u.userId === p.ownerId)?.name ?? p.ownerId } : null
      if (p.reviewerId !== undefined) o.reviewer = p.reviewerId ? { userId: p.reviewerId, name: MOCK_ADMIN_USERS.find((u) => u.userId === p.reviewerId)?.name ?? p.reviewerId } : null
      if (p.status) {
        s.analysis.status = p.status
        setTreeStatus(s.screenId, p.status)
        ;(history[s.screenId] ??= []).unshift({ at: new Date().toISOString(), user: me(config), fromStatus: before, toStatus: p.status, comment: p.comment ?? null })
      }
      s.analysis.owner = o.owner
      s.analysis.reviewer = o.reviewer
      s.analysis.updatedAt = new Date().toISOString()
      updatedAt[s.screenId] = s.analysis.updatedAt
      return [200, s.analysis]
    }],
    ['get', /^\/api\/v1\/screens\/([^/]+)\/analysis\/history$/, ({ params }) => [200, history[params[0]] ?? []]],
    ['post', /^\/api\/v1\/systems\/([^/]+)\/analysis\/assign$/, ({ body }) => {
      const p = body as AnalysisAssign
      const userRef = (id: string | null | undefined) => (id ? { userId: id, name: MOCK_ADMIN_USERS.find((u) => u.userId === id)?.name ?? id } : null)
      p.screenIds.forEach((id) => {
        const o = owners[id] ?? (owners[id] = { owner: null, reviewer: null })
        o.owner = userRef(p.ownerId)
        if (p.reviewerId !== undefined) o.reviewer = userRef(p.reviewerId)
        updatedAt[id] = new Date().toISOString()
        const detail = ctx.findScreen(id)
        if (detail) { detail.analysis.owner = o.owner; detail.analysis.reviewer = o.reviewer }
      })
      return [200, { updated: p.screenIds.length }]
    }],
    ['get', /^\/api\/v1\/systems\/([^/]+)\/analysis\/progress$/, ({ params }) => {
      const list = allScreens(params[0])
      const rows = list.map(({ node, path }) => analysisRow(node, path))
      const byStatus = Object.fromEntries(STATUSES.map((s) => [s, rows.filter((r) => r.status === s).length])) as Record<AnalysisStatus, number>
      const ownerMap = new Map<string, { owner: UserRef | null; total: number; byStatus: Record<string, number> }>()
      rows.forEach((r) => {
        const key = r.owner?.userId ?? '-'
        const cur = ownerMap.get(key) ?? { owner: r.owner, total: 0, byStatus: Object.fromEntries(STATUSES.map((s) => [s, 0])) }
        cur.total += 1
        cur.byStatus[r.status] += 1
        ownerMap.set(key, cur)
      })
      const byMenu = (ctx.menuTree[params[0]] ?? []).map((top) => {
        const mine = list.filter((x) => x.top.menuId === top.menuId)
        return { menuId: top.menuId, name: top.name, total: mine.length, done: mine.filter((x) => x.node.screen?.status === 'DONE').length }
      })
      const weak = allStepLists().reduce((n, { steps }) => n + steps.filter((s) => s.edge && !s.edge.confirmed && s.edge.confidence < 0.9).length, 0)
      return [200, {
        total: rows.length, byStatus, byOwner: [...ownerMap.values()].sort((a, b) => b.total - a.total), byMenu,
        unmatchedElements: rows.reduce((n, r) => n + r.unmatchedCount, 0), unconfirmedEdges: weak + 9,
      }]
    }],
    ['get', /^\/api\/v1\/systems\/([^/]+)\/analysis\/screens$/, ({ params, config }) => {
      const p = config.params ?? {}
      const q = String(p.q ?? '').trim().toLowerCase()
      const rows = allScreens(params[0])
        .filter(({ top }) => !p.menuId || top.menuId === p.menuId)
        .map(({ node, path }) => analysisRow(node, path))
        .filter((r) => (!p.status || r.status === p.status) && (!p.ownerId || r.owner?.userId === p.ownerId)
          && (!q || `${r.name} ${r.screenCode}`.toLowerCase().includes(q)))
      return [200, paginate(rows, p)]
    }],

    /* ── 테이블·ERD ──────────────────────────────────────── */
    ['get', /^\/api\/v1\/systems\/([^/]+)\/tables$/, ({ config }) => {
      const p = config.params ?? {}
      const q = String(p.q ?? '').trim().toLowerCase()
      const list = tables
        .filter((tb) => (!p.subjectArea || tb.subjectArea === p.subjectArea) && (!q || `${tb.name} ${tb.comment ?? ''}`.toLowerCase().includes(q)))
        .map(summaryOf)
      return [200, paginate(list, p)]
    }],
    ['get', /^\/api\/v1\/systems\/([^/]+)\/subject-areas$/, () => {
      const map = new Map<string, number>()
      tables.forEach((tb) => { if (tb.subjectArea) map.set(tb.subjectArea, (map.get(tb.subjectArea) ?? 0) + 1) })
      return [200, [...map].map(([name, tableCount]) => ({ name, tableCount }))]
    }],
    ['get', /^\/api\/v1\/tables\/([^/]+)$/, ({ params }) => {
      const tb = tables.find((x) => x.tableId === params[0])
      return tb ? [200, tableDetail(tb)] : err(404, 'NOT_FOUND', '테이블을 찾을 수 없습니다.')
    }],
    ['put', /^\/api\/v1\/tables\/([^/]+)$/, ({ params, body }) => {
      const tb = tables.find((x) => x.tableId === params[0])
      if (!tb) return err(404, 'NOT_FOUND', '테이블을 찾을 수 없습니다.')
      if (body.subjectArea !== undefined) tb.subjectArea = body.subjectArea || null
      if (body.comment !== undefined) tb.comment = body.comment || null
      return [200, summaryOf(tb)]
    }],
    ['get', /^\/api\/v1\/systems\/([^/]+)\/erd$/, ({ config }) => {
      const p = config.params ?? {}
      const ids = typeof p.tableIds === 'string' && p.tableIds ? new Set(p.tableIds.split(',')) : null
      let picked = tables.filter((tb) => (!p.subjectArea || tb.subjectArea === p.subjectArea))
      if (ids) {
        // 선택한 테이블 + 한 단계 관계 테이블까지 함께 보여 준다
        const near = new Set(ids)
        relations.forEach((r) => { if (ids.has(r.childTableId)) near.add(r.parentTableId); if (ids.has(r.parentTableId)) near.add(r.childTableId) })
        picked = tables.filter((tb) => near.has(tb.tableId))
      }
      const rels = relations.filter((r) => p.includeInferred === false || p.includeInferred === 'false' ? r.kind !== 'inferred' : true)
      return [200, { tables: picked.slice(0, 300).map(toErdTable), relations: rels }]
    }],
    ['patch', /^\/api\/v1\/relations\/([^/]+)$/, ({ params, body }) => {
      const r = relations.find((x) => x.relationId === params[0])
      if (!r) return err(404, 'NOT_FOUND', '관계를 찾을 수 없습니다.')
      r.decision = body.decision
      return [200, r]
    }],
    ['post', /^\/api\/v1\/relations$/, ({ body }) => {
      const p = body as RelationCreate
      const child = tables.find((x) => x.tableId === p.childTableId)
      const parent = tables.find((x) => x.tableId === p.parentTableId)
      if (!child || !parent || !p.columns?.length) return err(400, 'VALIDATION', '테이블과 컬럼 대응을 지정하세요.')
      const r: Relation = {
        relationId: mockId('rel'), childTableId: child.tableId, parentTableId: parent.tableId, childName: child.name, parentName: parent.name,
        columns: p.columns, kind: 'manual', evidenceCount: null, confidence: null, decision: 'confirmed',
      }
      relations = [...relations, r]
      return [201, r]
    }],

    /* ── 시스템 단위 뷰 ──────────────────────────────────── */
    ['get', /^\/api\/v1\/systems\/([^/]+)\/crud$/, ({ params, config }) =>
      [200, systemCrud(params[0], String(config.params?.by ?? 'screen'), config.params?.menuId)]],
    ['get', /^\/api\/v1\/systems\/([^/]+)\/navigation$/, ({ params }) => {
      const nodes = allScreens(params[0]).map(({ node }) => {
        const s = node.screen as NonNullable<MenuNode['screen']>
        return { screenId: s.screenId, screenCode: s.screenCode, name: node.name, kind: s.kind, status: s.status }
      })
      return [200, { nodes, links: R1_NAV_LINKS }]
    }],
    ['get', /^\/api\/v1\/nodes\/([^/]+)\/call-tree$/, ({ params, config }) => {
      const depth = Math.min(6, Math.max(1, Number(config.params?.depth ?? 3)))
      return [200, callTree(params[0], config.params?.direction === 'up' ? 'up' : 'down', depth)]
    }],
    ['get', /^\/api\/v1\/nodes\/([^/]+)\/usage$/, ({ params }) => [200, usage(params[0])]],
    ['get', /^\/api\/v1\/runs\/([^/]+)\/diff$/, ({ params }) => {
      const d = runDiff(params[0])
      return d ? [200, d] : err(404, 'NOT_FOUND', '회차를 찾을 수 없습니다.')
    }],

    /* ── 산출물 ──────────────────────────────────────────── */
    ['get', /^\/api\/v1\/systems\/([^/]+)\/deliverables$/, () => [200, R1_DELIVERABLE_TYPES]],
    ['get', /^\/api\/v1\/systems\/([^/]+)\/deliverables\/([^/]+)$/, ({ params, config }) => {
      const type = R1_DELIVERABLE_TYPES.find((d) => d.type === params[1])
      if (!type) return err(404, 'NOT_FOUND', '산출물 종류가 없습니다.')
      const format = String(config.params?.format ?? 'xlsx')
      if (!type.formats.includes(format as 'xlsx')) return err(400, 'VALIDATION', '지원하지 않는 형식입니다.')
      const ids = String(config.params?.screenIds ?? '').split(',').filter(Boolean)
      if (ids.length > 300) return err(400, 'VALIDATION', '최대 300화면까지 출력할 수 있습니다.')
      return [200, new Blob([`mock ${type.name} (${format})`], { type: format === 'pptx' ? PPTX_MIME : XLSX_MIME })]
    }],
  ]
}
