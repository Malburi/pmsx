// Vue Flow 그래프 전체를 PNG 로 저장 (ERD·화면 이동 맵) — 화면에 보이는 부분이 아니라 모든 노드를 담는다.
import { getRectOfNodes, getTransformForBounds, type GraphNode } from '@vue-flow/core'
import { toPng } from 'html-to-image'

const MAX_SIDE = 6000

export const exportFlowPng = async (root: HTMLElement, nodes: GraphNode[], background: string): Promise<Blob> => {
  const viewport = root.querySelector<HTMLElement>('.vue-flow__viewport')
  if (!viewport || !nodes.length) throw new Error('no graph')
  const rect = getRectOfNodes(nodes)
  const width = Math.min(MAX_SIDE, Math.max(800, Math.ceil(rect.width + 120)))
  const height = Math.min(MAX_SIDE, Math.max(600, Math.ceil(rect.height + 120)))
  const { x, y, zoom } = getTransformForBounds(rect, width, height, 0.2, 2, 0.04)
  const dataUrl = await toPng(viewport, {
    backgroundColor: background,
    width,
    height,
    skipFonts: true, // 폰트 재다운로드 없이 (폐쇄망·오프라인)
    style: { width: `${width}px`, height: `${height}px`, transform: `translate(${x}px, ${y}px) scale(${zoom})` },
  })
  return (await fetch(dataUrl)).blob()
}

/** 내보내기 파일명: <이름>_yyyyMMdd_HHmm.png */
export const pngFileName = (name: string, now = new Date()) => {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${name.replace(/[\\/:*?"<>|]/g, '_')}_${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}_${p(now.getHours())}${p(now.getMinutes())}.png`
}
