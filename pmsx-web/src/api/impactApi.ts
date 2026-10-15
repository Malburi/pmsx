import api from '@/utils/axios'
import type { HandoverPreview, HandoverRequest, HandoverResult, ImpactResult } from '@/types/r3'

/* ── 영향도 분석 (docs/13 §8) ────────────────────────────── */
export const getImpactApi = (systemId: string, targetId: string, column?: string | null) =>
  api.get<unknown, ImpactResult>(`/api/v1/systems/${systemId}/impact`, { params: { targetId, column: column || undefined } })

/* ── SI → SM 인수인계 (§7) ───────────────────────────────── */
export const previewHandoverApi = (siProjectId: string, payload: HandoverRequest) =>
  api.post<unknown, HandoverPreview>(`/api/v1/projects/${siProjectId}/handover/preview`, payload)

export const runHandoverApi = (siProjectId: string, payload: HandoverRequest) =>
  api.post<unknown, HandoverResult>(`/api/v1/projects/${siProjectId}/handover`, payload)
