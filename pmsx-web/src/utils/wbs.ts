// WBS 계층 편집 순수 함수 (SCR-PJ-001). 입력 배열은 바꾸지 않고 새 배열을 돌려준다.
import { diffDays } from '@/utils/date'
import type { WbsItem } from '@/types/r2'

type Tree = Pick<WbsItem, 'wbsId' | 'parentId' | 'order'>

const childrenOf = <T extends Tree>(items: T[], parentId: string | null) =>
  items.filter((i) => i.parentId === parentId).sort((a, b) => a.order - b.order)

/** 깊이 우선(트리 순서)으로 정렬한 평탄 목록 */
export const treeOrder = <T extends Tree>(items: T[]): T[] => {
  const out: T[] = []
  const known = new Set(items.map((i) => i.wbsId))
  const walk = (parentId: string | null) => childrenOf(items, parentId).forEach((c) => { out.push(c); walk(c.wbsId) })
  walk(null)
  // 부모를 잃은 항목(데이터 오류)도 버리지 않고 뒤에 붙인다
  items.filter((i) => i.parentId && !known.has(i.parentId)).forEach((i) => { if (!out.includes(i)) out.push(i) })
  return out
}

export const depthMap = (items: Tree[]): Map<string, number> => {
  const byId = new Map(items.map((i) => [i.wbsId, i]))
  const memo = new Map<string, number>()
  const depth = (id: string, guard = 0): number => {
    if (memo.has(id)) return memo.get(id) as number
    const p = byId.get(id)?.parentId
    const d = p && byId.has(p) && guard < 50 ? depth(p, guard + 1) + 1 : 0
    memo.set(id, d)
    return d
  }
  items.forEach((i) => depth(i.wbsId))
  return memo
}

export const hasChildren = (items: Tree[], id: string) => items.some((i) => i.parentId === id)

/** 같은 부모 안 순서를 0..n 으로 다시 매기고 WBS 번호(1, 1.1, 1.1.2)를 다시 붙인다 */
export const renumber = <T extends Tree & { wbsNo: string }>(items: T[]): T[] => {
  const out: T[] = []
  const walk = (parentId: string | null, prefix: string) => {
    childrenOf(items, parentId).forEach((c, idx) => {
      const no = prefix ? `${prefix}.${idx + 1}` : String(idx + 1)
      out.push({ ...c, order: idx, wbsNo: no })
      walk(c.wbsId, no)
    })
  }
  walk(null, '')
  return out
}

/** 들여쓰기: 바로 위 형제의 마지막 자식이 된다 */
export const indent = <T extends Tree & { wbsNo: string }>(items: T[], id: string): T[] => {
  const me = items.find((i) => i.wbsId === id)
  if (!me) return items
  const siblings = childrenOf(items, me.parentId)
  const idx = siblings.findIndex((s) => s.wbsId === id)
  if (idx <= 0) return items
  const newParent = siblings[idx - 1]
  const lastOrder = Math.max(-1, ...childrenOf(items, newParent.wbsId).map((c) => c.order))
  return renumber(items.map((i) => (i.wbsId === id ? { ...i, parentId: newParent.wbsId, order: lastOrder + 1 } : i)))
}

/** 내어쓰기: 부모의 형제가 되어 부모 바로 뒤에 놓인다 */
export const outdent = <T extends Tree & { wbsNo: string }>(items: T[], id: string): T[] => {
  const me = items.find((i) => i.wbsId === id)
  const parent = me?.parentId ? items.find((i) => i.wbsId === me.parentId) : null
  if (!me || !parent) return items
  const shifted = items.map((i) => {
    if (i.wbsId === id) return { ...i, parentId: parent.parentId, order: parent.order + 0.5 }
    return i
  })
  return renumber(shifted)
}

/** 형제 사이에서 위(-1)/아래(+1)로 */
export const moveSibling = <T extends Tree & { wbsNo: string }>(items: T[], id: string, dir: -1 | 1): T[] => {
  const me = items.find((i) => i.wbsId === id)
  if (!me) return items
  const siblings = childrenOf(items, me.parentId)
  const idx = siblings.findIndex((s) => s.wbsId === id)
  const other = siblings[idx + dir]
  if (!other) return items
  return renumber(items.map((i) => {
    if (i.wbsId === id) return { ...i, order: other.order }
    if (i.wbsId === other.wbsId) return { ...i, order: me.order }
    return i
  }))
}

/** 항목과 모든 하위 항목 ID */
export const subtreeIds = (items: Tree[], id: string): Set<string> => {
  const out = new Set<string>([id])
  let grew = true
  while (grew) {
    grew = false
    items.forEach((i) => { if (i.parentId && out.has(i.parentId) && !out.has(i.wbsId)) { out.add(i.wbsId); grew = true } })
  }
  return out
}

/** 접힌 항목의 하위는 숨긴다 */
export const visibleRows = <T extends Tree>(items: T[], collapsed: ReadonlySet<string>): T[] => {
  const hidden = new Set<string>()
  collapsed.forEach((id) => subtreeIds(items, id).forEach((s) => { if (s !== id) hidden.add(s) }))
  return treeOrder(items).filter((i) => !hidden.has(i.wbsId))
}

/** 선후행 순환 여부 (자기 자신 포함) */
export const hasDepCycle = (items: Pick<WbsItem, 'wbsId' | 'deps'>[]): boolean => {
  const preds = new Map(items.map((i) => [i.wbsId, i.deps.map((d) => d.predId)]))
  const state = new Map<string, 1 | 2>()
  const visit = (id: string): boolean => {
    if (state.get(id) === 1) return true
    if (state.get(id) === 2) return false
    state.set(id, 1)
    for (const p of preds.get(id) ?? []) if (visit(p)) return true
    state.set(id, 2)
    return false
  }
  return items.some((i) => visit(i.wbsId))
}

/**
 * 상위 항목 집계(화면 표시용 — 서버가 다시 계산한다):
 * 진척 = 하위 가중 평균, 기간 = 하위의 최소 시작 ~ 최대 종료.
 */
export const rollup = (items: WbsItem[]): WbsItem[] => {
  const byId = new Map(items.map((i) => [i.wbsId, { ...i }]))
  const ordered = treeOrder([...byId.values()]).reverse() // 잎부터 위로
  ordered.forEach((node) => {
    const kids = [...byId.values()].filter((c) => c.parentId === node.wbsId)
    if (!kids.length) return
    const w = kids.reduce((s, k) => s + (k.weight || 0), 0)
    node.progress = w ? Math.round(kids.reduce((s, k) => s + k.progress * (k.weight || 0), 0) / w) : Math.round(kids.reduce((s, k) => s + k.progress, 0) / kids.length)
    const starts = kids.map((k) => k.planStart).filter((d): d is string => !!d).sort()
    const ends = kids.map((k) => k.planEnd).filter((d): d is string => !!d).sort()
    if (starts.length) node.planStart = starts[0]
    if (ends.length) node.planEnd = ends[ends.length - 1]
  })
  return items.map((i) => byId.get(i.wbsId) as WbsItem)
}

/** 일정 지연 여부: 계획 종료가 지났는데 완료가 아님 */
export const isDelayed = (item: Pick<WbsItem, 'planEnd' | 'status'>, asOf: string) =>
  !!item.planEnd && item.status !== 'DONE' && diffDays(item.planEnd, asOf) > 0
