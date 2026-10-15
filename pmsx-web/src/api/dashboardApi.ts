import api from '@/utils/axios'
import type { Page } from '@/types/common'
import type {
  GlobalSearchHit, MemberScope, Notification, NotificationSettings, PortfolioRow, ProjectDashboard,
} from '@/types/r2'

/* ── 알림 (docs/12 §7) ───────────────────────────────────── */
export const getNotificationsApi = (params: { unread?: boolean; page?: number; size?: number }) =>
  api.get<unknown, Page<Notification>>('/api/v1/notifications', { params, silent: true })

export const readNotificationApi = (id: string) =>
  api.post<unknown, void>(`/api/v1/notifications/${id}/read`, {})

export const readAllNotificationsApi = () =>
  api.post<unknown, void>('/api/v1/notifications/read-all', {})

export const getNotificationSettingsApi = () =>
  api.get<unknown, NotificationSettings>('/api/v1/notifications/settings')

export const saveNotificationSettingsApi = (settings: NotificationSettings) =>
  api.put<unknown, NotificationSettings>('/api/v1/notifications/settings', settings)

/* ── 대시보드·검색 ───────────────────────────────────────── */
export const getProjectDashboardApi = (projectId: string, view: 'PM' | 'CUSTOMER') =>
  api.get<unknown, ProjectDashboard>(`/api/v1/projects/${projectId}/dashboard`, { params: { view } })

export const getPortfolioApi = () =>
  api.get<unknown, PortfolioRow[]>('/api/v1/dashboard/portfolio')

export const globalSearchApi = (q: string, projectId?: string) =>
  api.get<unknown, GlobalSearchHit[]>('/api/v1/search', { params: { q, projectId } })

/** PARTNER 열람 범위 (docs/12 v0.2 — GET 추가) */
export const getMemberScopeApi = (projectId: string, userId: string) =>
  api.get<unknown, MemberScope>(`/api/v1/projects/${projectId}/members/${userId}/scope`)

export const saveMemberScopeApi = (projectId: string, userId: string, scope: MemberScope) =>
  api.put<unknown, MemberScope>(`/api/v1/projects/${projectId}/members/${userId}/scope`, scope)
