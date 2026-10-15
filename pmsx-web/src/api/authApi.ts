import api from '@/utils/axios'
import type { LoginPayload, LoginResponse, RefreshResponse, User } from '@/types/api'
import type { ChangePasswordPayload } from '@/types/common'

export const loginApi = (payload: LoginPayload) =>
  api.post<unknown, LoginResponse>('/api/v1/auth/login', payload)

export const refreshTokenApi = () =>
  api.post<unknown, RefreshResponse>('/api/v1/auth/refresh')

export const logoutApi = () =>
  api.post<unknown, void>('/api/v1/auth/logout')

export const getMeApi = () =>
  api.get<unknown, User>('/api/v1/auth/me')

export const changePasswordApi = (payload: ChangePasswordPayload) =>
  api.post<unknown, void>('/api/v1/auth/change-password', payload)
