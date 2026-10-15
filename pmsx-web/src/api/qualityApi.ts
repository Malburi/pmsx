import api from '@/utils/axios'
import type { Page } from '@/types/common'
import type {
  ActionItem, ChangeRequest, ChangeRequestPayload, Comment, CrStatus, Decision, Defect, DefectPayload, DefectStatus, Issue, IssuePayload,
  Meeting, MeetingPayload, QualityReport, Risk, RiskPayload, TestCase, TestCasePayload, TestLevel, TestResultRow, TestRound, TestScenario,
} from '@/types/r2'

type ListQuery = { status?: string; q?: string; page?: number; size?: number }

/* ── 이슈 (docs/12 §6) ───────────────────────────────────── */
export const getIssuesApi = (projectId: string, params: ListQuery) =>
  api.get<unknown, Page<Issue>>(`/api/v1/projects/${projectId}/issues`, { params })

export const getIssueApi = (issueId: string) =>
  api.get<unknown, Issue>(`/api/v1/issues/${issueId}`)

export const createIssueApi = (projectId: string, payload: IssuePayload) =>
  api.post<unknown, Issue>(`/api/v1/projects/${projectId}/issues`, payload)

export const updateIssueApi = (issueId: string, payload: IssuePayload) =>
  api.put<unknown, Issue>(`/api/v1/issues/${issueId}`, payload)

export const deleteIssueApi = (issueId: string) =>
  api.delete<unknown, void>(`/api/v1/issues/${issueId}`)

export const addIssueCommentApi = (issueId: string, content: string) =>
  api.post<unknown, Comment>(`/api/v1/issues/${issueId}/comments`, { content })

export const escalateIssueApi = (issueId: string) =>
  api.post<unknown, Issue>(`/api/v1/issues/${issueId}/escalate`, {})

/* ── 리스크·의사결정 ─────────────────────────────────────── */
export const getRisksApi = (projectId: string) =>
  api.get<unknown, Risk[]>(`/api/v1/projects/${projectId}/risks`)

export const createRiskApi = (projectId: string, payload: RiskPayload) =>
  api.post<unknown, Risk>(`/api/v1/projects/${projectId}/risks`, payload)

export const updateRiskApi = (riskId: string, payload: RiskPayload) =>
  api.put<unknown, Risk>(`/api/v1/risks/${riskId}`, payload)

export const deleteRiskApi = (riskId: string) =>
  api.delete<unknown, void>(`/api/v1/risks/${riskId}`)

export const riskToIssueApi = (riskId: string) =>
  api.post<unknown, Issue>(`/api/v1/risks/${riskId}/to-issue`, {})

export const getDecisionsApi = (projectId: string) =>
  api.get<unknown, Decision[]>(`/api/v1/projects/${projectId}/decisions`)

export const createDecisionApi = (projectId: string, payload: Omit<Decision, 'decisionId'>) =>
  api.post<unknown, Decision>(`/api/v1/projects/${projectId}/decisions`, payload)

export const updateDecisionApi = (id: string, payload: Omit<Decision, 'decisionId'>) =>
  api.put<unknown, Decision>(`/api/v1/decisions/${id}`, payload)

export const deleteDecisionApi = (id: string) =>
  api.delete<unknown, void>(`/api/v1/decisions/${id}`)

/* ── 변경요청 ────────────────────────────────────────────── */
export const getChangeRequestsApi = (projectId: string) =>
  api.get<unknown, ChangeRequest[]>(`/api/v1/projects/${projectId}/change-requests`)

export const createChangeRequestApi = (projectId: string, payload: ChangeRequestPayload) =>
  api.post<unknown, ChangeRequest>(`/api/v1/projects/${projectId}/change-requests`, payload)

export const updateChangeRequestApi = (crId: string, payload: ChangeRequestPayload) =>
  api.put<unknown, ChangeRequest>(`/api/v1/change-requests/${crId}`, payload)

export const transitionChangeRequestApi = (crId: string, to: CrStatus, comment?: string) =>
  api.post<unknown, ChangeRequest>(`/api/v1/change-requests/${crId}/transition`, { to, comment })

/* ── 테스트 ──────────────────────────────────────────────── */
export const getTestScenariosApi = (projectId: string) =>
  api.get<unknown, TestScenario[]>(`/api/v1/projects/${projectId}/test-scenarios`)

export const createTestScenarioApi = (projectId: string, payload: { level: TestLevel; name: string }) =>
  api.post<unknown, TestScenario>(`/api/v1/projects/${projectId}/test-scenarios`, payload)

export const updateTestScenarioApi = (id: string, payload: { level: TestLevel; name: string }) =>
  api.put<unknown, TestScenario>(`/api/v1/test-scenarios/${id}`, payload)

export const deleteTestScenarioApi = (id: string) =>
  api.delete<unknown, void>(`/api/v1/test-scenarios/${id}`)

export const createTestCaseApi = (scenarioId: string, payload: TestCasePayload) =>
  api.post<unknown, TestCase>(`/api/v1/test-scenarios/${scenarioId}/cases`, payload)

export const updateTestCaseApi = (caseId: string, payload: TestCasePayload) =>
  api.put<unknown, TestCase>(`/api/v1/test-cases/${caseId}`, payload)

export const deleteTestCaseApi = (caseId: string) =>
  api.delete<unknown, void>(`/api/v1/test-cases/${caseId}`)

export const getTestRoundsApi = (projectId: string) =>
  api.get<unknown, TestRound[]>(`/api/v1/projects/${projectId}/test-rounds`)

export const createTestRoundApi = (projectId: string, payload: { name: string; level: TestLevel }) =>
  api.post<unknown, TestRound>(`/api/v1/projects/${projectId}/test-rounds`, payload)

export const getTestResultsApi = (roundId: string) =>
  api.get<unknown, TestResultRow[]>(`/api/v1/test-rounds/${roundId}/results`)

export const saveTestResultsApi = (roundId: string, rows: TestResultRow[]) =>
  api.put<unknown, TestResultRow[]>(`/api/v1/test-rounds/${roundId}/results`, rows)

/* ── 결함 ────────────────────────────────────────────────── */
export const getDefectsApi = (projectId: string, params: ListQuery & { severity?: string; screenId?: string }) =>
  api.get<unknown, Page<Defect>>(`/api/v1/projects/${projectId}/defects`, { params })

export const getDefectApi = (defectId: string) =>
  api.get<unknown, Defect>(`/api/v1/defects/${defectId}`)

export const createDefectApi = (projectId: string, payload: DefectPayload) =>
  api.post<unknown, Defect>(`/api/v1/projects/${projectId}/defects`, payload)

export const updateDefectApi = (defectId: string, payload: DefectPayload) =>
  api.put<unknown, Defect>(`/api/v1/defects/${defectId}`, payload)

export const transitionDefectApi = (defectId: string, to: DefectStatus, comment?: string) =>
  api.post<unknown, Defect>(`/api/v1/defects/${defectId}/transition`, { to, comment })

export const getQualityReportApi = (projectId: string) =>
  api.get<unknown, QualityReport>(`/api/v1/projects/${projectId}/quality-report`)

/* ── 회의록·액션아이템 ───────────────────────────────────── */
export const getMeetingsApi = (projectId: string) =>
  api.get<unknown, Meeting[]>(`/api/v1/projects/${projectId}/meetings`)

export const getMeetingApi = (meetingId: string) =>
  api.get<unknown, Meeting>(`/api/v1/meetings/${meetingId}`)

export const createMeetingApi = (projectId: string, payload: MeetingPayload) =>
  api.post<unknown, Meeting>(`/api/v1/projects/${projectId}/meetings`, payload)

export const updateMeetingApi = (meetingId: string, payload: MeetingPayload) =>
  api.put<unknown, Meeting>(`/api/v1/meetings/${meetingId}`, payload)

export const deleteMeetingApi = (meetingId: string) =>
  api.delete<unknown, void>(`/api/v1/meetings/${meetingId}`)

export const getActionItemsApi = (projectId: string, open = true) =>
  api.get<unknown, (ActionItem & { meetingId?: string; meetingNo?: string })[]>(`/api/v1/projects/${projectId}/action-items`, { params: { open } })

export const patchActionItemApi = (itemId: string, patch: { done?: boolean; ownerId?: string | null; dueDate?: string | null }) =>
  api.patch<unknown, ActionItem>(`/api/v1/action-items/${itemId}`, patch)
