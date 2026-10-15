import { ref, type Ref } from 'vue'
import type { ApiError } from '@/types/api'

export interface UseApiResult<T> {
  data: Ref<T | null>
  loading: Ref<boolean>
  error: Ref<ApiError | null>
  // 호출부마다 인자 형태가 달라 any[] 로 둔다 (useApi<T>(fn) 처럼 T 만 지정해 쓰기 위함)
  execute: (...args: any[]) => Promise<T | null> // eslint-disable-line @typescript-eslint/no-explicit-any
}

// 로딩/에러 상태가 함께 필요한 호출용 얇은 래퍼 (api-standard §2-3)
export const useApi = <T>(
  fn: (...args: any[]) => Promise<T>, // eslint-disable-line @typescript-eslint/no-explicit-any
  options: { immediate?: boolean } = {},
): UseApiResult<T> => {
  const data = ref<T | null>(null) as Ref<T | null>
  const loading = ref(false)
  const error = ref<ApiError | null>(null)

  const execute = async (...args: any[]) => { // eslint-disable-line @typescript-eslint/no-explicit-any
    loading.value = true
    error.value = null
    try {
      data.value = await fn(...args)
      return data.value
    } catch (e) {
      const err = e as Partial<ApiError> | null
      error.value = { code: err?.code ?? 'UNKNOWN', message: err?.message ?? '오류', fields: err?.fields }
      return null
    } finally {
      loading.value = false
    }
  }

  if (options.immediate) void execute()
  return { data, loading, error, execute }
}
