import { describe, expect, it } from 'vitest'
import type { Erd, NavigationMap, Relation } from '@/types/api'
import {
  ERD_MAX_COLUMNS, applyLayout, buildElkGraph, erdNodeSize, erdToFlow, gridLayout, navToFlow, relationClass, relationLabel,
} from '@/utils/graph'

const rel = (id: string, kind: Relation['kind'], decision: Relation['decision'], child = 't-a', parent = 't-b'): Relation => ({
  relationId: id, childTableId: child, parentTableId: parent, childName: 'A', parentName: 'B',
  columns: [['B_ID', 'ID']], kind, evidenceCount: kind === 'inferred' ? 5 : null, confidence: kind === 'inferred' ? 0.87 : null, decision,
})

const erd: Erd = {
  tables: [
    { tableId: 't-a', name: 'TB_A', comment: null, subjectArea: null, columns: [{ name: 'ID', dataType: 'NUMBER', pk: true, fk: false }] },
    { tableId: 't-b', name: 'TB_B', comment: null, subjectArea: null, columns: [{ name: 'ID', dataType: 'NUMBER', pk: true, fk: false }] },
  ],
  relations: [
    rel('r-fk', 'fk', 'confirmed'),
    rel('r-inf', 'inferred', 'pending'),
    rel('r-inf-ok', 'inferred', 'confirmed'),
    rel('r-rej', 'inferred', 'rejected'),
    rel('r-out', 'manual', 'confirmed', 't-a', 't-x'), // 화면에 없는 테이블
  ],
}

describe('ERD 변환', () => {
  it('테이블은 노드, 관계는 자식→부모 엣지가 된다', () => {
    // Act
    const { nodes, edges } = erdToFlow(erd, { showInferred: true, showRejected: false })
    // Assert
    expect(nodes.map((n) => n.id)).toEqual(['t-a', 't-b'])
    expect(edges.map((e) => e.id)).toEqual(['r-fk', 'r-inf', 'r-inf-ok'])
    expect(edges[0]).toMatchObject({ source: 't-a', target: 't-b', class: 'rel-solid' })
  })

  it('추정 관계를 끄면 미확정 추정만 숨기고 확정된 추정은 남긴다', () => {
    const { edges } = erdToFlow(erd, { showInferred: false, showRejected: false })
    expect(edges.map((e) => e.id)).toEqual(['r-fk', 'r-inf-ok'])
  })

  it('기각 관계는 켰을 때만 흐린 선으로 보인다', () => {
    const { edges } = erdToFlow(erd, { showInferred: true, showRejected: true })
    expect(edges.find((e) => e.id === 'r-rej')?.class).toBe('rel-rejected')
  })

  it('선 스타일·라벨', () => {
    expect(relationClass(rel('x', 'inferred', 'pending'))).toBe('rel-inferred')
    expect(relationClass(rel('x', 'manual', 'confirmed'))).toBe('rel-manual')
    expect(relationLabel(rel('x', 'inferred', 'pending'))).toBe('B_ID→ID · 87%')
  })

  it('노드 높이는 컬럼 수에 비례하고 상한 이후는 한 줄로 접는다', () => {
    const cols = (n: number) => Array.from({ length: n }, (_, i) => ({ name: `C${i}`, dataType: 'X', pk: false, fk: false }))
    const small = erdNodeSize({ ...erd.tables[0], columns: cols(2) })
    const big = erdNodeSize({ ...erd.tables[0], columns: cols(40) })
    const max = erdNodeSize({ ...erd.tables[0], columns: cols(ERD_MAX_COLUMNS + 1) })
    expect(big.height).toBeGreaterThan(small.height)
    expect(big.height).toBe(max.height)
  })
})

describe('화면 이동 맵·레이아웃', () => {
  const map: NavigationMap = {
    nodes: [
      { screenId: 's1', screenCode: 'A', name: 'A', kind: 'page', status: 'DONE' },
      { screenId: 's2', screenCode: 'B', name: 'B', kind: 'popup', status: 'AUTO' },
    ],
    links: [
      { fromScreenId: 's1', toScreenId: 's2', via: '찾기', kind: 'popup' },
      { fromScreenId: 's1', toScreenId: 's9', via: null, kind: 'link' },
    ],
  }

  it('없는 화면으로 가는 링크는 버린다', () => {
    const { edges } = navToFlow(map)
    expect(edges).toHaveLength(1)
    expect(edges[0]).toMatchObject({ source: 's1', target: 's2', label: '찾기', class: 'nav-popup' })
  })

  it('elk 입력을 만들고 결과 좌표를 노드에 반영한다', () => {
    const { nodes, edges } = navToFlow(map)
    const graph = buildElkGraph(nodes, edges, 'RIGHT')
    expect(graph.children).toHaveLength(2)
    expect(graph.edges[0]).toEqual({ id: edges[0].id, sources: ['s1'], targets: ['s2'] })
    const placed = applyLayout(nodes, { children: [{ id: 's1', x: 10, y: 20 }, { id: 's2', x: 300, y: 20 }] })
    expect(placed.map((n) => n.position)).toEqual([{ x: 10, y: 20 }, { x: 300, y: 20 }])
  })

  it('레이아웃 실패 시 격자 배치', () => {
    const { nodes } = navToFlow(map)
    const placed = gridLayout(nodes, 1)
    expect(placed[0].position).toEqual({ x: 0, y: 0 })
    expect(placed[1].position.y).toBeGreaterThan(0)
  })
})
