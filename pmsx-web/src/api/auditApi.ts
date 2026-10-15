import api from '@/utils/axios'

// 화면 진입 접근 로그 — 실패해도 사용자에게 알리지 않는다(silent)
export const postAccessLogApi = (path: string) =>
  api.post<unknown, void>('/api/v1/audit/access', { path }, { silent: true })
