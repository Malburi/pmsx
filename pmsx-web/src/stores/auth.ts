import { defineStore } from 'pinia'
import { loginApi, logoutApi, refreshTokenApi } from '@/api/authApi'
import type { LoginPayload, User } from '@/types/api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    accessToken: null as string | null, // 메모리에만 보관 — persist 금지 (security-frontend §2-2)
    userId: null as string | null,
    loginId: null as string | null,
    name: null as string | null,
    roles: [] as string[],
  }),
  getters: {
    isAuthenticated: (s) => !!s.accessToken,
    hasRole: (s) => (role: string) => s.roles.includes(role),
    hasAnyRole: (s) => (roles: string[]) => roles.some((r) => s.roles.includes(r)),
  },
  actions: {
    setUser(user: User) {
      this.userId = user.userId
      this.loginId = user.loginId
      this.name = user.name
      this.roles = user.roles
    },
    async login(payload: LoginPayload) {
      const { accessToken, user } = await loginApi(payload)
      this.accessToken = accessToken
      this.setUser(user)
    },
    async refresh(): Promise<string | null> {
      try {
        const { accessToken } = await refreshTokenApi() // refresh 토큰은 httpOnly 쿠키로 자동 전송
        this.accessToken = accessToken
        return accessToken
      } catch {
        this.$reset()
        return null
      }
    },
    async logout() {
      await logoutApi().catch(() => {})
      this.$reset()
    },
  },
  persist: {
    pick: ['userId', 'loginId', 'name', 'roles'],
  },
})
