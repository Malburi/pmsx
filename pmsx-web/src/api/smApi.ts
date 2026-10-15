import api from '@/utils/axios'
import type { Page } from '@/types/common'
import type {
  Change, ChangePayload, ChangeStatus, Incident, IncidentPayload, KbArticle, KbPayload, MonthlyReport, MonthlyReportSummary, OpsAsset,
  OpsAssetPayload, Problem, ProblemCandidate, ProblemPayload, RoutineJob, RoutineJobPayload, RoutineOccurrence, SimilarItem, SlaPolicy,
  SlaStatus, Sr, SrComment, SrPayload, SrQuery, SrStatus,
} from '@/types/r3'

/* ── SR (docs/13 §1) ─────────────────────────────────────── */
export const getSrsApi = (projectId: string, params: SrQuery) =>
  api.get<unknown, Page<Sr>>(`/api/v1/projects/${projectId}/srs`, { params })

export const getSrApi = (srId: string) =>
  api.get<unknown, Sr>(`/api/v1/srs/${srId}`)

export const createSrApi = (projectId: string, payload: SrPayload) =>
  api.post<unknown, Sr>(`/api/v1/projects/${projectId}/srs`, payload)

export const updateSrApi = (srId: string, payload: SrPayload) =>
  api.put<unknown, Sr>(`/api/v1/srs/${srId}`, payload)

export const transitionSrApi = (srId: string, payload: { to: SrStatus; comment?: string; assigneeId?: string }) =>
  api.post<unknown, Sr>(`/api/v1/srs/${srId}/transition`, payload)

export const addSrCommentApi = (srId: string, content: string, internal: boolean) =>
  api.post<unknown, SrComment>(`/api/v1/srs/${srId}/comments`, { content, internal })

export const srToKbApi = (srId: string) =>
  api.post<unknown, KbArticle>(`/api/v1/srs/${srId}/to-kb`, {})

/* ── SLA (§3) ────────────────────────────────────────────── */
export const getSlaPoliciesApi = (projectId: string) =>
  api.get<unknown, SlaPolicy[]>(`/api/v1/projects/${projectId}/sla-policies`)

export const createSlaPolicyApi = (projectId: string, payload: Omit<SlaPolicy, 'policyId'>) =>
  api.post<unknown, SlaPolicy>(`/api/v1/projects/${projectId}/sla-policies`, payload)

export const updateSlaPolicyApi = (policyId: string, payload: Omit<SlaPolicy, 'policyId'>) =>
  api.put<unknown, SlaPolicy>(`/api/v1/sla-policies/${policyId}`, payload)

export const deleteSlaPolicyApi = (policyId: string) =>
  api.delete<unknown, void>(`/api/v1/sla-policies/${policyId}`)

export const getSlaStatusApi = (projectId: string, month: string) =>
  api.get<unknown, SlaStatus>(`/api/v1/projects/${projectId}/sla/status`, { params: { month } })

/* ── 장애·문제·변경 (§4) ─────────────────────────────────── */
export const getIncidentsApi = (projectId: string) =>
  api.get<unknown, Incident[]>(`/api/v1/projects/${projectId}/incidents`)

export const getIncidentApi = (id: string) =>
  api.get<unknown, Incident>(`/api/v1/incidents/${id}`)

export const createIncidentApi = (projectId: string, payload: IncidentPayload) =>
  api.post<unknown, Incident>(`/api/v1/projects/${projectId}/incidents`, payload)

export const updateIncidentApi = (id: string, payload: IncidentPayload) =>
  api.put<unknown, Incident>(`/api/v1/incidents/${id}`, payload)

export const addIncidentTimelineApi = (id: string, entry: { at: string; content: string }) =>
  api.post<unknown, Incident>(`/api/v1/incidents/${id}/timeline`, entry)

export const getProblemsApi = (projectId: string) =>
  api.get<unknown, Problem[]>(`/api/v1/projects/${projectId}/problems`)

export const createProblemApi = (projectId: string, payload: ProblemPayload) =>
  api.post<unknown, Problem>(`/api/v1/projects/${projectId}/problems`, payload)

export const updateProblemApi = (id: string, payload: ProblemPayload) =>
  api.put<unknown, Problem>(`/api/v1/problems/${id}`, payload)

export const getProblemCandidatesApi = (projectId: string) =>
  api.get<unknown, ProblemCandidate[]>(`/api/v1/projects/${projectId}/problems/candidates`)

export const getChangesApi = (projectId: string) =>
  api.get<unknown, Change[]>(`/api/v1/projects/${projectId}/changes`)

export const createChangeApi = (projectId: string, payload: ChangePayload) =>
  api.post<unknown, Change>(`/api/v1/projects/${projectId}/changes`, payload)

export const updateChangeApi = (id: string, payload: ChangePayload) =>
  api.put<unknown, Change>(`/api/v1/changes/${id}`, payload)

export const cabDecisionApi = (id: string, decision: 'APPROVE' | 'REJECT', comment: string) =>
  api.post<unknown, Change>(`/api/v1/changes/${id}/cab`, { decision, comment })

export const transitionChangeApi = (id: string, to: ChangeStatus, comment?: string) =>
  api.post<unknown, Change>(`/api/v1/changes/${id}/transition`, { to, comment })

export const getChangeCalendarApi = (projectId: string, from: string, to: string) =>
  api.get<unknown, Change[]>(`/api/v1/projects/${projectId}/changes/calendar`, { params: { from, to } })

/* ── 정기 작업·운영 자산·KB (§5) ─────────────────────────── */
export const getRoutineJobsApi = (projectId: string) =>
  api.get<unknown, RoutineJob[]>(`/api/v1/projects/${projectId}/routine-jobs`)

export const createRoutineJobApi = (projectId: string, payload: RoutineJobPayload) =>
  api.post<unknown, RoutineJob>(`/api/v1/projects/${projectId}/routine-jobs`, payload)

export const updateRoutineJobApi = (id: string, payload: RoutineJobPayload) =>
  api.put<unknown, RoutineJob>(`/api/v1/routine-jobs/${id}`, payload)

export const deleteRoutineJobApi = (id: string) =>
  api.delete<unknown, void>(`/api/v1/routine-jobs/${id}`)

export const getRoutineOccurrencesApi = (projectId: string, from: string, to: string) =>
  api.get<unknown, RoutineOccurrence[]>(`/api/v1/projects/${projectId}/routine-jobs/occurrences`, { params: { from, to } })

export const checkRoutineApi = (id: string, payload: { date: string; done: boolean; note?: string }) =>
  api.post<unknown, RoutineOccurrence>(`/api/v1/routine-jobs/${id}/check`, payload)

export const getOpsAssetsApi = (projectId: string) =>
  api.get<unknown, OpsAsset[]>(`/api/v1/projects/${projectId}/ops-assets`)

export const createOpsAssetApi = (projectId: string, payload: OpsAssetPayload) =>
  api.post<unknown, OpsAsset>(`/api/v1/projects/${projectId}/ops-assets`, payload)

export const updateOpsAssetApi = (id: string, payload: OpsAssetPayload) =>
  api.put<unknown, OpsAsset>(`/api/v1/ops-assets/${id}`, payload)

export const deleteOpsAssetApi = (id: string) =>
  api.delete<unknown, void>(`/api/v1/ops-assets/${id}`)

export const getKbListApi = (projectId: string, params: { q?: string; page?: number; size?: number }) =>
  api.get<unknown, Page<KbArticle>>(`/api/v1/projects/${projectId}/kb`, { params })

export const getKbApi = (id: string) =>
  api.get<unknown, KbArticle>(`/api/v1/kb/${id}`)

export const createKbApi = (projectId: string, payload: KbPayload) =>
  api.post<unknown, KbArticle>(`/api/v1/projects/${projectId}/kb`, payload)

export const updateKbApi = (id: string, payload: KbPayload) =>
  api.put<unknown, KbArticle>(`/api/v1/kb/${id}`, payload)

export const deleteKbApi = (id: string) =>
  api.delete<unknown, void>(`/api/v1/kb/${id}`)

export const getKbSimilarApi = (projectId: string, text: string) =>
  api.get<unknown, SimilarItem[]>(`/api/v1/projects/${projectId}/kb/similar`, { params: { text } })

/* ── 월간 운영보고 (§6) ──────────────────────────────────── */
export const getMonthlyReportsApi = (projectId: string) =>
  api.get<unknown, MonthlyReportSummary[]>(`/api/v1/projects/${projectId}/monthly-reports`)

export const generateMonthlyReportApi = (projectId: string, month: string) =>
  api.post<unknown, MonthlyReport>(`/api/v1/projects/${projectId}/monthly-reports/generate`, { month })

export const getMonthlyReportApi = (reportId: string) =>
  api.get<unknown, MonthlyReport>(`/api/v1/monthly-reports/${reportId}`)

export const updateMonthlyReportApi = (reportId: string, payload: MonthlyReport) =>
  api.put<unknown, MonthlyReport>(`/api/v1/monthly-reports/${reportId}`, payload)
