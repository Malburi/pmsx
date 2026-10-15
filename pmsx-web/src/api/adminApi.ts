import api from '@/utils/axios'
import type {
  AdminUser, AdminUserCreate, AdminUserUpdate, AuditLog, AuditLogQuery, Page, PageQuery, Role,
} from '@/types/common'

/* ── 사용자 ───────────────────────────────────────────────── */
export const getAdminUsersApi = (query: PageQuery & { q?: string }) =>
  api.get<unknown, Page<AdminUser>>('/api/v1/admin/users', { params: query })

export const createAdminUserApi = (payload: AdminUserCreate) =>
  api.post<unknown, AdminUser>('/api/v1/admin/users', payload)

export const updateAdminUserApi = (userId: string, payload: AdminUserUpdate) =>
  api.put<unknown, AdminUser>(`/api/v1/admin/users/${userId}`, payload)

export const resetPasswordApi = (userId: string, newPassword: string) =>
  api.post<unknown, void>(`/api/v1/admin/users/${userId}/reset-password`, { newPassword })

export const lockUserApi = (userId: string) =>
  api.post<unknown, void>(`/api/v1/admin/users/${userId}/lock`)

export const unlockUserApi = (userId: string) =>
  api.post<unknown, void>(`/api/v1/admin/users/${userId}/unlock`)

export const getRolesApi = () =>
  api.get<unknown, Role[]>('/api/v1/admin/roles')

/* ── 감사 로그 ────────────────────────────────────────────── */
export const getAuditLogsApi = (query: AuditLogQuery) =>
  api.get<unknown, Page<AuditLog>>('/api/v1/admin/audit-logs', { params: query })
