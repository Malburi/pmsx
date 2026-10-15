import api from '@/utils/axios'
import type { Page } from '@/types/common'
import type {
  AuditChecklistItem, Deliverable, DeliverablePayload, ElementLinks, FieldHistory, HistoryEntry, Requirement, RequirementPayload,
  TraceLink, TraceLinkPayload, TraceRow, TraceType,
} from '@/types/r2'

/* ── 프로젝트 산출물 대장 (docs/12 §5) — As-Is 산출물 출력(deliverableApi)과 다르다 ── */
export const getProjectDeliverablesApi = (projectId: string, params: { phase?: string; status?: string } = {}) =>
  api.get<unknown, Deliverable[]>(`/api/v1/projects/${projectId}/deliverables`, { params })

export const createProjectDeliverableApi = (projectId: string, payload: DeliverablePayload) =>
  api.post<unknown, Deliverable>(`/api/v1/projects/${projectId}/deliverables`, payload)

export const updateProjectDeliverableApi = (id: string, payload: DeliverablePayload) =>
  api.put<unknown, Deliverable>(`/api/v1/deliverables/${id}`, payload)

export const deleteProjectDeliverableApi = (id: string) =>
  api.delete<unknown, void>(`/api/v1/deliverables/${id}`)

export const deliverableActionApi = (id: string, action: 'request-review' | 'approve' | 'reject', comment?: string) =>
  api.post<unknown, Deliverable>(`/api/v1/deliverables/${id}/${action}`, { comment })

export const getDeliverableHistoryApi = (id: string) =>
  api.get<unknown, HistoryEntry[]>(`/api/v1/deliverables/${id}/history`)

/* ── 요구사항 ────────────────────────────────────────────── */
export const getRequirementsApi = (projectId: string, params: { q?: string; status?: string; page?: number; size?: number }) =>
  api.get<unknown, Page<Requirement>>(`/api/v1/projects/${projectId}/requirements`, { params })

export const createRequirementApi = (projectId: string, payload: RequirementPayload) =>
  api.post<unknown, Requirement>(`/api/v1/projects/${projectId}/requirements`, payload)

export const updateRequirementApi = (reqId: string, payload: RequirementPayload) =>
  api.put<unknown, Requirement>(`/api/v1/requirements/${reqId}`, payload)

export const deleteRequirementApi = (reqId: string) =>
  api.delete<unknown, void>(`/api/v1/requirements/${reqId}`)

export const getRequirementHistoryApi = (reqId: string) =>
  api.get<unknown, FieldHistory[]>(`/api/v1/requirements/${reqId}/history`)

/* ── 추적 링크·매트릭스 ──────────────────────────────────── */
export const getTraceLinksApi = (type: TraceType, id: string) =>
  api.get<unknown, TraceLink[]>('/api/v1/trace-links', { params: { type, id } })

export const createTraceLinkApi = (payload: TraceLinkPayload) =>
  api.post<unknown, TraceLink>('/api/v1/trace-links', payload)

export const deleteTraceLinkApi = (linkId: string) =>
  api.delete<unknown, void>(`/api/v1/trace-links/${linkId}`)

export const getTraceMatrixApi = (projectId: string) =>
  api.get<unknown, TraceRow[]>(`/api/v1/projects/${projectId}/trace-matrix`)

export const getAuditChecklistApi = (projectId: string) =>
  api.get<unknown, AuditChecklistItem[]>(`/api/v1/projects/${projectId}/audit-checklist`)

export const saveAuditChecklistApi = (projectId: string, items: AuditChecklistItem[]) =>
  api.put<unknown, AuditChecklistItem[]>(`/api/v1/projects/${projectId}/audit-checklist`, items)

/** 뷰어 "연결 이력" 탭 (FR-AS-027) */
export const getElementLinksApi = (elementId: string) =>
  api.get<unknown, ElementLinks>(`/api/v1/elements/${elementId}/links`)
