import { describe, expect, it, vi } from 'vitest'
import { useApi } from '@/composables/useApi'

describe('useApi', () => {
  it('성공하면 data 가 채워지고 loading 이 false 로 끝난다', async () => {
    const fn = vi.fn().mockResolvedValue([{ id: '1' }])
    const { data, loading, error, execute } = useApi(fn)

    const result = await execute()

    expect(result).toEqual([{ id: '1' }])
    expect(data.value).toEqual([{ id: '1' }])
    expect(loading.value).toBe(false)
    expect(error.value).toBeNull()
  })

  it('실패하면 error 가 채워지고 data 는 null 로 남는다', async () => {
    const fn = vi.fn().mockRejectedValue({ code: 'NOT_FOUND', message: '없음' })
    const { data, error, execute } = useApi(fn)

    await execute()

    expect(data.value).toBeNull()
    expect(error.value?.code).toBe('NOT_FOUND')
  })
})
