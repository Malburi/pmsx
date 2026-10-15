import 'axios'

declare module 'axios' {
  interface AxiosRequestConfig {
    /** true 이면 네트워크/5xx 전역 토스트를 띄우지 않는다 (접근 로그 등 부가 호출) */
    silent?: boolean
    /** 401 → refresh 후 재시도는 요청당 한 번만 (인터셉터 내부용) */
    _retried?: boolean
  }
}

export {}
