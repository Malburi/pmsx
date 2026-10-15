import { ref } from 'vue'
import { runElkLayout } from '@/utils/elkLayout'
import { applyLayout, buildElkGraph, gridLayout, type FlowEdge, type FlowNode } from '@/utils/graph'

// ERD·화면 이동 맵 공통 자동 배치 — elk(웹 워커) 실패 시 격자 배치로 대신한다
export const useGraphLayout = () => {
  const layouting = ref(false)

  const layout = async <T>(nodes: FlowNode<T>[], edges: FlowEdge[], direction: 'RIGHT' | 'DOWN' = 'RIGHT'): Promise<FlowNode<T>[]> => {
    if (!nodes.length) return nodes
    layouting.value = true
    try {
      return applyLayout(nodes, await runElkLayout(buildElkGraph(nodes, edges, direction)))
    } catch (e) {
      console.warn('[graph] elk 레이아웃 실패, 격자 배치로 대체', e)
      return gridLayout(nodes)
    } finally {
      layouting.value = false
    }
  }

  return { layouting, layout }
}
