import api from '@/utils/axios'
import type { LicenseInfo, LicenseStatus, SystemInfo } from '@/types/common'

export const getSystemInfoApi = () =>
  api.get<unknown, SystemInfo>('/api/v1/system/info')

// 인증 불필요 — 로그인 화면 배너용
export const getLicenseStatusApi = () =>
  api.get<unknown, LicenseStatus>('/api/v1/system/license-status', { silent: true })

export const registerLicenseApi = (key: string) =>
  api.post<unknown, LicenseInfo>('/api/v1/system/license', { key })
