import api from '@/utils/axios'
import type { Page } from '@/types/common'
import type {
  Assignment, ChecklistItem, HeatmapRow, MmPlanRow, MmSummaryRow, Resource, ResourcePayload, ResourceQuery, Timesheet, WorkItem, WorkTargetType,
} from '@/types/r2'

/* ── 인력·공수 (docs/12 §4) ──────────────────────────────── */
export const getResourcesApi = (query: ResourceQuery) =>
  api.get<unknown, Page<Resource>>('/api/v1/resources', { params: query })

export const updateResourceApi = (userId: string, payload: ResourcePayload) =>
  api.put<unknown, Resource>(`/api/v1/resources/${userId}`, payload)

export const getMmPlanApi = (projectId: string, from: string, to: string) =>
  api.get<unknown, MmPlanRow[]>(`/api/v1/projects/${projectId}/mm-plan`, { params: { from, to } })

export const saveMmPlanApi = (projectId: string, rows: MmPlanRow[], from: string, to: string) =>
  api.put<unknown, MmPlanRow[]>(`/api/v1/projects/${projectId}/mm-plan`, rows, { params: { from, to } })

export const getHeatmapApi = (params: { from: string; to: string; unit: 'month' | 'week'; projectId?: string }) =>
  api.get<unknown, HeatmapRow[]>('/api/v1/resources/heatmap', { params })

export const getUnassignedApi = (projectId: string, type?: WorkTargetType) =>
  api.get<unknown, WorkItem[]>(`/api/v1/projects/${projectId}/work-items/unassigned`, { params: { type } })

export const createAssignmentApi = (payload: { targetType: WorkTargetType; targetId: string; userId: string; allocPct: number }) =>
  api.post<unknown, Assignment>('/api/v1/assignments', payload)

export const deleteAssignmentApi = (assignmentId: string) =>
  api.delete<unknown, void>(`/api/v1/assignments/${assignmentId}`)

export const getTimesheetApi = (userId: string, weekStart: string) =>
  api.get<unknown, Timesheet>('/api/v1/timesheets', { params: { userId, weekStart } })

export const saveTimesheetApi = (ts: Timesheet) =>
  api.put<unknown, Timesheet>('/api/v1/timesheets', ts)

export const submitTimesheetApi = (timesheetId: string) =>
  api.post<unknown, Timesheet>(`/api/v1/timesheets/${timesheetId}/submit`, {})

export const approveTimesheetApi = (timesheetId: string, comment?: string) =>
  api.post<unknown, Timesheet>(`/api/v1/timesheets/${timesheetId}/approve`, { comment })

export const rejectTimesheetApi = (timesheetId: string, comment: string) =>
  api.post<unknown, Timesheet>(`/api/v1/timesheets/${timesheetId}/reject`, { comment })

export const getPendingTimesheetsApi = (projectId: string) =>
  api.get<unknown, Timesheet[]>(`/api/v1/projects/${projectId}/timesheets/pending`)

export const getMmSummaryApi = (projectId: string, from: string, to: string) =>
  api.get<unknown, MmSummaryRow[]>(`/api/v1/projects/${projectId}/mm-summary`, { params: { from, to } })

export const getChecklistApi = (projectId: string, userId: string) =>
  api.get<unknown, ChecklistItem[]>(`/api/v1/projects/${projectId}/members/${userId}/checklist`)

export const saveChecklistApi = (projectId: string, userId: string, items: ChecklistItem[]) =>
  api.put<unknown, ChecklistItem[]>(`/api/v1/projects/${projectId}/members/${userId}/checklist`, items)
