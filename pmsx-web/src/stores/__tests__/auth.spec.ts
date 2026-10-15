import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import * as authApi from '@/api/authApi'
import { useAuthStore } from '@/stores/auth'

vi.mock('@/api/authApi')

describe('useAuthStore', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('refresh 성공 시 accessToken 이 메모리에 세팅된다', async () => {
    vi.mocked(authApi.refreshTokenApi).mockResolvedValue({ accessToken: 'new-token' })
    const auth = useAuthStore()

    const token = await auth.refresh()

    expect(token).toBe('new-token')
    expect(auth.isAuthenticated).toBe(true)
  })

  it('refresh 실패 시 상태를 초기화하고 null 을 돌려준다', async () => {
    vi.mocked(authApi.refreshTokenApi).mockRejectedValue({ code: 'AUTH_EXPIRED' })
    const auth = useAuthStore()
    auth.accessToken = 'old'

    const token = await auth.refresh()

    expect(token).toBeNull()
    expect(auth.isAuthenticated).toBe(false)
  })

  it('login 은 토큰과 사용자 정보를 저장한다', async () => {
    vi.mocked(authApi.loginApi).mockResolvedValue({
      accessToken: 't', user: { userId: 'u1', loginId: 'admin', name: '관리자', roles: ['SYS_ADMIN'] },
    })
    const auth = useAuthStore()

    await auth.login({ loginId: 'admin', password: 'x' })

    expect(auth.accessToken).toBe('t')
    expect(auth.hasRole('SYS_ADMIN')).toBe(true)
  })
})
