import axios, { AxiosError, type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth'
import type { ApiError } from '@/types/api'
import { showErrorToast } from '@/utils/toast'

// 단일 Axios 인스턴스 (api-standard §2-1). 화면에서 axios.create 재호출 금지.
const api: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_URL || '/',
  timeout: 20_000,
  withCredentials: true, // httpOnly refresh 쿠키(PMSX_RT) 전송
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

// 서버 없이 화면을 확인하는 mock 모드. 운영 빌드에서는 상수 분기로 제거된다.
if (import.meta.env.VITE_USE_MOCK === 'true') {
  api.defaults.adapter = async (config) => {
    const { mockAdapter } = await import('@/mocks')
    return mockAdapter(config)
  }
}

/* ── Request: Access Token 자동 첨부 ───────────────────────── */
api.interceptors.request.use((config: InternalAxiosRequestConfig) => {
  const auth = useAuthStore()
  if (auth.accessToken) {
    config.headers.set('Authorization', `Bearer ${auth.accessToken}`)
  }
  return config
})

/* ── Response: 언랩 + 401 갱신 + 전역 에러 ──────────────────── */
let refreshing: Promise<string | null> | null = null

// 인증 API 자체의 401 은 갱신을 다시 시도하면 자기 자신을 기다리는 교착이 생기므로 제외한다.
const isAuthCall = (config?: InternalAxiosRequestConfig) => !!config?.url?.includes('/api/v1/auth/')

api.interceptors.response.use(
  (res) => res.data,
  async (error: AxiosError<ApiError>) => {
    const { response, config } = error
    if (!response) {
      if (!config?.silent) showErrorToast('common.error.network')
      return Promise.reject({ code: 'NETWORK', message: '네트워크 오류' })
    }

    if (response.status === 401 && config && !isAuthCall(config) && !config._retried) {
      config._retried = true
      const auth = useAuthStore()
      refreshing ??= auth.refresh()
      const newToken = await refreshing.finally(() => { refreshing = null })
      if (newToken) {
        config.headers.set('Authorization', `Bearer ${newToken}`)
        return api.request(config)
      }
      await auth.logout()
      return Promise.reject({ code: 'AUTH_EXPIRED', message: '다시 로그인해주세요.' })
    }

    // 5xx 만 전역 토스트, 4xx 는 호출 컴포넌트가 인라인 처리. silent 요청(접근 로그 등)은 토스트 생략
    if (response.status >= 500 && !config?.silent) {
      showErrorToast('common.error.server')
    }
    return Promise.reject(response.data ?? { code: 'UNKNOWN', message: '오류' })
  },
)

export default api
