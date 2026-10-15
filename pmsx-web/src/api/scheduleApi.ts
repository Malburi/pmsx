import api from '@/utils/axios'
import type { Project } from '@/types/api'
import type {
  Baseline, BaselineCompareRow, Methodology, ProjectPayload, ProjectProgress, WbsItem, WbsPatch, WeeklyReport, WeeklyReportSummary,
} from '@/types/r2'

/* ── 프로젝트·방법론 (docs/12 §1) ────────────────────────── */
export const createProjectApi = (payload: ProjectPayload) =>
  api.post<unknown, Project>('/api/v1/projects', payload)

export const updateProjectApi = (projectId: string, payload: ProjectPayload) =>
  api.put<unknown, Project>(`/api/v1/projects/${projectId}`, payload)

export const getMethodologiesApi = () =>
  api.get<unknown, Methodology[]>('/api/v1/methodologies')

export const createMethodologyApi = (payload: Omit<Methodology, 'methodologyId'>) =>
  api.post<unknown, Methodology>('/api/v1/methodologies', payload)

export const updateMethodologyApi = (id: string, payload: Omit<Methodology, 'methodologyId'>) =>
  api.put<unknown, Methodology>(`/api/v1/methodologies/${id}`, payload)

export const deleteMethodologyApi = (id: string) =>
  api.delete<unknown, void>(`/api/v1/methodologies/${id}`)

/* ── WBS·베이스라인 (§2) ─────────────────────────────────── */
export const getWbsApi = (projectId: string) =>
  api.get<unknown, WbsItem[]>(`/api/v1/projects/${projectId}/wbs`)

/** 신규 항목은 wbsId 없이 보낸다 — 화면 임시 ID(tmp-*)는 여기서 비운다 */
export const saveWbsApi = (projectId: string, items: WbsItem[]) =>
  api.put<unknown, WbsItem[]>(`/api/v1/projects/${projectId}/wbs`, items)

export const patchWbsApi = (wbsId: string, patch: WbsPatch) =>
  api.patch<unknown, WbsItem>(`/api/v1/wbs/${wbsId}`, patch)

export const generateWbsApi = (projectId: string, payload: { methodologyId: string; startDate: string; replace: boolean }) =>
  api.post<unknown, WbsItem[]>(`/api/v1/projects/${projectId}/wbs/generate`, payload)

export const importMsProjectApi = (projectId: string, file: File, replace: boolean) => {
  const form = new FormData()
  form.append('file', file)
  form.append('replace', String(replace))
  return api.post<unknown, WbsItem[]>(`/api/v1/projects/${projectId}/wbs/import-msproject`, form)
}

export const getBaselinesApi = (projectId: string) =>
  api.get<unknown, Baseline[]>(`/api/v1/projects/${projectId}/baselines`)

export const createBaselineApi = (projectId: string, name: string) =>
  api.post<unknown, Baseline>(`/api/v1/projects/${projectId}/baselines`, { name })

export const compareBaselineApi = (baselineId: string) =>
  api.get<unknown, BaselineCompareRow[]>(`/api/v1/baselines/${baselineId}/compare`)

export const getProjectProgressApi = (projectId: string) =>
  api.get<unknown, ProjectProgress>(`/api/v1/projects/${projectId}/progress`)

/* ── 주간보고 (§3) ───────────────────────────────────────── */
export const getWeeklyReportsApi = (projectId: string) =>
  api.get<unknown, WeeklyReportSummary[]>(`/api/v1/projects/${projectId}/weekly-reports`)

export const generateWeeklyReportApi = (projectId: string, weekStart: string) =>
  api.post<unknown, WeeklyReport>(`/api/v1/projects/${projectId}/weekly-reports/generate`, { weekStart })

export const getWeeklyReportApi = (reportId: string) =>
  api.get<unknown, WeeklyReport>(`/api/v1/weekly-reports/${reportId}`)

export const updateWeeklyReportApi = (reportId: string, report: WeeklyReport) =>
  api.put<unknown, WeeklyReport>(`/api/v1/weekly-reports/${reportId}`, report)

export const submitWeeklyReportApi = (reportId: string) =>
  api.post<unknown, WeeklyReport>(`/api/v1/weekly-reports/${reportId}/submit`)
