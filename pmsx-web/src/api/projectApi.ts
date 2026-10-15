import api from '@/utils/axios'
import type { Project, SystemCreate, SystemSummary } from '@/types/api'
import type { Member, MemberPayload } from '@/types/common'

export const getProjectsApi = () =>
  api.get<unknown, Project[]>('/api/v1/projects')

export const getSystemsApi = (projectId: string) =>
  api.get<unknown, SystemSummary[]>(`/api/v1/projects/${projectId}/systems`)

export const createSystemApi = (projectId: string, payload: SystemCreate) =>
  api.post<unknown, SystemSummary>(`/api/v1/projects/${projectId}/systems`, payload)

export const getSystemApi = (systemId: string) =>
  api.get<unknown, SystemSummary>(`/api/v1/systems/${systemId}`)

/* ── 프로젝트 멤버 (docs/10 §4) ───────────────────────────── */
export const getMembersApi = (projectId: string) =>
  api.get<unknown, Member[]>(`/api/v1/projects/${projectId}/members`)

export const addMemberApi = (projectId: string, payload: MemberPayload) =>
  api.post<unknown, Member>(`/api/v1/projects/${projectId}/members`, payload)

export const updateMemberApi = (projectId: string, userId: string, payload: MemberPayload) =>
  api.put<unknown, Member>(`/api/v1/projects/${projectId}/members/${userId}`, payload)

export const removeMemberApi = (projectId: string, userId: string) =>
  api.delete<unknown, void>(`/api/v1/projects/${projectId}/members/${userId}`)
