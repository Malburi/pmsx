// ERD(SCR-AS-006)·화면 이동 맵(SCR-AS-008) 그래프 변환 — API 응답 → Vue Flow 노드/엣지, elk 레이아웃 입력·결과 적용.
// Vue Flow·elkjs 런타임에 의존하지 않는 순수 함수만 둔다 (단위 테스트 대상).
import type { Erd, ErdTable, NavigationMap, Relation } from '@/types/api'

export interface FlowNode<T = unknown> {
  id: string
  type: string
  position: { x: number; y: number }
  data: T
  width?: number
  height?: number
}

export interface FlowEdge<T = unknown> {
  id: string
  source: string
  target: string
  type?: string
  label?: string
  animated?: boolean
  class?: string
  data: T
}

export interface ElkInput {
  id: string
  layoutOptions: Record<string, string>
  children: { id: string; width: number; height: number }[]
  edges: { id: string; sources: string[]; targets: string[] }[]
}

export interface ElkOutput {
  children?: { id: string; x?: number; y?: number }[]
}

/* ── ERD ─────────────────────────────────────────────────── */
export const ERD_NODE_WIDTH = 240
const ERD_HEADER_H = 46
const ERD_ROW_H = 21
/** 테이블 노드에 펼쳐 보이는 최대 컬럼 수 — 나머지는 "+N개" 로 접는다 */
export const ERD_MAX_COLUMNS = 12

export const erdNodeSize = (table: ErdTable) => {
  const rows = Math.min(table.columns.length, ERD_MAX_COLUMNS) + (table.columns.length > ERD_MAX_COLUMNS ? 1 : 0)
  return { width: ERD_NODE_WIDTH, height: ERD_HEADER_H + rows * ERD_ROW_H + 8 }
}

export interface ErdFilter {
  showInferred: boolean
  showRejected: boolean
}

export const relationVisible = (r: Relation, f: ErdFilter) => {
  if (r.decision === 'rejected') return f.showRejected
  if (r.kind === 'inferred' && r.decision !== 'confirmed') return f.showInferred
  return true
}

/** 관계 선 스타일 분류: FK·확정 = 실선, 추정(미확정) = 점선, 수동 = 보라 실선, 기각 = 흐린 점선 */
export const relationClass = (r: Relation) => {
  if (r.decision === 'rejected') return 'rel-rejected'
  if (r.kind === 'manual') return 'rel-manual'
  if (r.kind === 'fk' || r.decision === 'confirmed') return 'rel-solid'
  return 'rel-inferred'
}

export const relationLabel = (r: Relation) => {
  const cols = r.columns.map(([c, p]) => (c === p ? c : `${c}→${p}`)).join(', ')
  if (r.kind === 'inferred' && r.confidence !== null) return `${cols} · ${Math.round(r.confidence * 100)}%`
  return cols
}

export const erdToFlow = (erd: Erd, filter: ErdFilter) => {
  const ids = new Set(erd.tables.map((t) => t.tableId))
  const nodes: FlowNode<{ table: ErdTable }>[] = erd.tables.map((table) => ({
    id: table.tableId,
    type: 'table',
    position: { x: 0, y: 0 },
    data: { table },
    ...erdNodeSize(table),
  }))
  const edges: FlowEdge<{ relation: Relation }>[] = erd.relations
    // 한쪽 테이블이 화면에 없는 관계는 그리지 않는다 (주제영역 필터 등)
    .filter((r) => ids.has(r.childTableId) && ids.has(r.parentTableId) && relationVisible(r, filter))
    .map((relation) => ({
      id: relation.relationId,
      source: relation.childTableId,
      target: relation.parentTableId,
      type: 'smoothstep',
      label: relationLabel(relation),
      class: relationClass(relation),
      data: { relation },
    }))
  return { nodes, edges }
}

/* ── 화면 이동 맵 ────────────────────────────────────────── */
export const NAV_NODE_SIZE = { width: 200, height: 56 }

export const navToFlow = (map: NavigationMap) => {
  const ids = new Set(map.nodes.map((n) => n.screenId))
  const nodes: FlowNode<{ screen: NavigationMap['nodes'][number] }>[] = map.nodes.map((screen) => ({
    id: screen.screenId,
    type: 'screen',
    position: { x: 0, y: 0 },
    data: { screen },
    ...NAV_NODE_SIZE,
  }))
  const edges: FlowEdge<{ link: NavigationMap['links'][number] }>[] = map.links
    .filter((l) => ids.has(l.fromScreenId) && ids.has(l.toScreenId))
    .map((link, i) => ({
      id: `nav-${i}-${link.fromScreenId}-${link.toScreenId}`,
      source: link.fromScreenId,
      target: link.toScreenId,
      type: 'smoothstep',
      label: link.via ?? undefined,
      class: `nav-${link.kind}`,
      data: { link },
    }))
  return { nodes, edges }
}

/* ── elk 레이아웃 ────────────────────────────────────────── */
export const buildElkGraph = (
  nodes: FlowNode[],
  edges: FlowEdge[],
  direction: 'RIGHT' | 'DOWN' = 'RIGHT',
): ElkInput => ({
  id: 'root',
  layoutOptions: {
    'elk.algorithm': 'layered',
    'elk.direction': direction,
    'elk.layered.spacing.nodeNodeBetweenLayers': '90',
    'elk.spacing.nodeNode': '40',
    'elk.spacing.componentComponent': '60',
    'elk.edgeRouting': 'ORTHOGONAL',
    // 연결 없는 테이블도 격자로 모아 배치
    'elk.separateConnectedComponents': 'true',
  },
  children: nodes.map((n) => ({ id: n.id, width: n.width ?? 200, height: n.height ?? 60 })),
  edges: edges.map((e) => ({ id: e.id, sources: [e.source], targets: [e.target] })),
})

/** elk 결과 좌표를 노드에 반영 (새 배열을 돌려준다) */
export const applyLayout = <T>(nodes: FlowNode<T>[], out: ElkOutput): FlowNode<T>[] => {
  const pos = new Map((out.children ?? []).map((c) => [c.id, { x: c.x ?? 0, y: c.y ?? 0 }]))
  return nodes.map((n) => ({ ...n, position: pos.get(n.id) ?? n.position }))
}

/** 레이아웃 실패 시 대비: 단순 격자 배치 */
export const gridLayout = <T>(nodes: FlowNode<T>[], columns = 4, gapX = 60, gapY = 50): FlowNode<T>[] => {
  let y = 0
  const out: FlowNode<T>[] = []
  for (let i = 0; i < nodes.length; i += columns) {
    const row = nodes.slice(i, i + columns)
    const rowH = Math.max(...row.map((n) => n.height ?? 60))
    row.forEach((n, k) => out.push({ ...n, position: { x: k * ((n.width ?? 200) + gapX), y } }))
    y += rowH + gapY
  }
  return out
}
