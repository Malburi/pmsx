import api from '@/utils/axios'
import type { Project } from '@/types/api'
import type { Page } from '@/types/common'
import type { PortalSrPayload, Sr, SrComment, SrStatus } from '@/types/r3'

// 고객 SR 포털 (docs/13 §2) — CUSTOMER 역할 전용. 본인(같은 고객사) SR 만, 내부 코멘트 제외.
export const getPortalProjectsApi = () =>
  api.get<unknown, Project[]>('/api/v1/portal/projects')

export const getPortalSrsApi = (projectId: string, params: { status?: SrStatus; q?: string; page?: number; size?: number }) =>
  api.get<unknown, Page<Sr>>(`/api/v1/portal/projects/${projectId}/srs`, { params })

export const createPortalSrApi = (projectId: string, payload: PortalSrPayload) =>
  api.post<unknown, Sr>(`/api/v1/portal/projects/${projectId}/srs`, payload)

export const getPortalSrApi = (srId: string) =>
  api.get<unknown, Sr>(`/api/v1/portal/srs/${srId}`)

export const addPortalCommentApi = (srId: string, content: string) =>
  api.post<unknown, SrComment>(`/api/v1/portal/srs/${srId}/comments`, { content })

export const confirmPortalSrApi = (srId: string) =>
  api.post<unknown, Sr>(`/api/v1/portal/srs/${srId}/confirm`, {})

export const reopenPortalSrApi = (srId: string, reason: string) =>
  api.post<unknown, Sr>(`/api/v1/portal/srs/${srId}/reopen`, { reason })
