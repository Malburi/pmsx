import api from '@/utils/axios'
import type {
  AnalysisPatch, CallTreeNode, CaptureInfo, CrudMatrix, EdgeCreate, EdgeInfo, EdgePatch, ElementPayload, ElementSummary,
  ElementUpdate, MenuNode, NavigationMap, NodeDetail, NodeSummary, NodeType, NodeUsage, ScreenAnalysis, ScreenDetail,
  SearchHit, Workflow,
} from '@/types/api'
import type { Page } from '@/types/common'

export const getMenuTreeApi = (systemId: string) =>
  api.get<unknown, MenuNode[]>(`/api/v1/systems/${systemId}/menu-tree`)

export const getScreenApi = (screenId: string) =>
  api.get<unknown, ScreenDetail>(`/api/v1/screens/${screenId}`)

// 캡처 이미지는 인증 헤더가 필요해 <img src> 로 직접 못 쓴다 → blob 으로 받는다.
export const getCaptureImageApi = (captureId: string) =>
  api.get<unknown, Blob>(`/api/v1/captures/${captureId}/image`, { responseType: 'blob' })

export const getWorkflowApi = (elementId: string) =>
  api.get<unknown, Workflow>(`/api/v1/elements/${elementId}/workflow`)

export const getNodeApi = (nodeId: string) =>
  api.get<unknown, NodeDetail>(`/api/v1/nodes/${nodeId}`)

export const getScreenCrudApi = (screenId: string) =>
  api.get<unknown, CrudMatrix>(`/api/v1/screens/${screenId}/crud`)

export const searchAsisApi = (systemId: string, q: string) =>
  api.get<unknown, SearchHit[]>(`/api/v1/systems/${systemId}/search`, { params: { q } })

export const patchAnalysisApi = (screenId: string, payload: AnalysisPatch) =>
  api.patch<unknown, ScreenAnalysis>(`/api/v1/screens/${screenId}/analysis`, payload)

/* ── 핫스팟 편집기·연결 보정 (docs/11 §1) ─────────────────── */
export const createElementApi = (screenId: string, payload: ElementPayload) =>
  api.post<unknown, ElementSummary>(`/api/v1/screens/${screenId}/elements`, payload)

export const updateElementApi = (elementId: string, payload: ElementUpdate) =>
  api.put<unknown, ElementSummary>(`/api/v1/elements/${elementId}`, payload)

// 수동 요소는 삭제, 수집 요소는 숨김 처리된다 (서버 판단)
export const deleteElementApi = (elementId: string) =>
  api.delete<unknown, void>(`/api/v1/elements/${elementId}`)

export const restoreElementApi = (elementId: string) =>
  api.post<unknown, ElementSummary>(`/api/v1/elements/${elementId}/restore`)

export const searchNodesApi = (systemId: string, query: { q?: string; type?: NodeType; page?: number; size?: number }) =>
  api.get<unknown, Page<NodeSummary>>(`/api/v1/systems/${systemId}/nodes`, { params: query })

export const createEdgeApi = (payload: EdgeCreate) =>
  api.post<unknown, EdgeInfo>('/api/v1/edges', payload)

export const patchEdgeApi = (edgeId: string, payload: EdgePatch) =>
  api.patch<unknown, EdgeInfo>(`/api/v1/edges/${edgeId}`, payload)

export const deleteEdgeApi = (edgeId: string) =>
  api.delete<unknown, void>(`/api/v1/edges/${edgeId}`)

export const uploadCaptureApi = (screenId: string, file: File, size?: { width: number; height: number }) => {
  const form = new FormData()
  form.append('file', file)
  if (size) {
    form.append('width', String(size.width))
    form.append('height', String(size.height))
  }
  return api.post<unknown, CaptureInfo>(`/api/v1/screens/${screenId}/captures`, form, { timeout: 120_000 })
}

/* ── 시스템 단위 분석 뷰 (docs/11 §4) ─────────────────────── */
export const getSystemCrudApi = (systemId: string, by: 'screen' | 'element', menuId?: string) =>
  api.get<unknown, CrudMatrix>(`/api/v1/systems/${systemId}/crud`, { params: { by, menuId } })

export const getNavigationApi = (systemId: string) =>
  api.get<unknown, NavigationMap>(`/api/v1/systems/${systemId}/navigation`)

export const getCallTreeApi = (nodeId: string, direction: 'down' | 'up', depth = 3) =>
  api.get<unknown, CallTreeNode>(`/api/v1/nodes/${nodeId}/call-tree`, { params: { direction, depth } })

export const getNodeUsageApi = (nodeId: string) =>
  api.get<unknown, NodeUsage[]>(`/api/v1/nodes/${nodeId}/usage`)
