import api from '@/utils/axios'
import type { DeliverableQuery, DeliverableType } from '@/types/api'

/* ── 산출물 출력 (docs/11 §5) ────────────────────────────── */
export const getDeliverableTypesApi = (systemId: string) =>
  api.get<unknown, DeliverableType[]>(`/api/v1/systems/${systemId}/deliverables`)

export const downloadDeliverableApi = (systemId: string, type: string, query: DeliverableQuery) =>
  api.get<unknown, Blob>(`/api/v1/systems/${systemId}/deliverables/${type}`, {
    params: query,
    responseType: 'blob',
    timeout: 600_000, // 300화면 상한 — 생성에 시간이 걸린다
  })
