import type { Router } from 'vue-router'
import { postAccessLogApi } from '@/api/auditApi'
import { getMeApi } from '@/api/authApi'
import { useAuthStore } from '@/stores/auth'

// 공개 경로는 이 한 곳에서만 관리한다 (routing-auth §2-4)
export const PUBLIC_ROUTES = ['/login', '/error']
export const HOME_PATH = '/home'
/** 고객 SR 포털 (SCR-SM-010) — CUSTOMER 단독 역할 사용자의 첫 화면 */
export const PORTAL_PATH = '/portal'

/** CUSTOMER 외 역할이 없으면 포털 전용 사용자로 본다 */
export const isCustomerOnly = (roles: readonly string[]): boolean => roles.length > 0 && roles.every((r) => r === 'CUSTOMER')

export const homePathFor = (roles: readonly string[]): string => (isCustomerOnly(roles) ? PORTAL_PATH : HOME_PATH)

/**
 * 고객 단독 사용자가 포털 밖 내부 화면으로 가려 하면 포털로 돌린다.
 * 내부 사용자는 그대로 둔다(포털 화면 자체의 역할 검사는 route meta.roles 가 한다).
 */
export const portalRedirect = (roles: readonly string[], path: string): string | null => {
  if (!isCustomerOnly(roles) || PUBLIC_ROUTES.includes(path)) return null
  return path === PORTAL_PATH || path.startsWith(`${PORTAL_PATH}/`) ? null : PORTAL_PATH
}

// 접근 로그(FR-CM-010) 과다 호출 방지: 같은 경로는 이 시간 안에 다시 보내지 않는다
const ACCESS_LOG_DEDUPE_MS = 30_000
const lastAccessLog = new Map<string, number>()

export const shouldLogAccess = (path: string, now = Date.now()): boolean => {
  const last = lastAccessLog.get(path)
  if (last !== undefined && now - last < ACCESS_LOG_DEDUPE_MS) return false
  lastAccessLog.set(path, now)
  // 오래된 항목 정리 (긴 세션에서 맵이 무한히 커지지 않게)
  if (lastAccessLog.size > 200) {
    lastAccessLog.forEach((at, p) => { if (now - at >= ACCESS_LOG_DEDUPE_MS) lastAccessLog.delete(p) })
  }
  return true
}

export const setupGuards = (router: Router) => {
  router.beforeEach(async (to) => {
    const auth = useAuthStore()
    const isPublic = PUBLIC_ROUTES.includes(to.path)

    // 1) 새로고침 직후에는 메모리에 토큰이 없으므로 refresh 1회 시도
    if (!auth.isAuthenticated && !isPublic) {
      const token = await auth.refresh()
      if (!token) return { path: '/login', query: { redirect: to.fullPath } }
    }

    // 2) 사용자 정보가 비어 있으면(다른 브라우저 등) 한 번 채운다
    if (auth.isAuthenticated && !auth.userId) {
      auth.setUser(await getMeApi())
    }

    // 3) 이미 로그인한 사용자의 로그인 화면 접근 · 루트 접근은 홈(고객 단독 역할은 포털)으로
    if (to.path === '/login' && auth.isAuthenticated) return homePathFor(auth.roles)
    if (to.path === '/') return homePathFor(auth.roles)

    // 3-1) 고객 단독 역할은 포털 밖 내부 화면에 들어가지 않는다 (SCR-SM-010)
    const portal = auth.isAuthenticated ? portalRedirect(auth.roles, to.path) : null
    if (portal) return portal

    // 4) 역할 기반 권한
    const requiredRoles = (to.meta.roles ?? []) as string[]
    if (requiredRoles.length > 0 && !auth.hasAnyRole(requiredRoles)) {
      return { path: '/error', query: { code: 'FORBIDDEN' } }
    }
    return true
  })

  // 화면 진입 접근 로그 — 경로(path) 단위, 쿼리 변경은 제외. 실패는 무시(fire-and-forget).
  router.afterEach((to, from, failure) => {
    if (failure || PUBLIC_ROUTES.includes(to.path) || to.path === from.path) return
    if (!useAuthStore().isAuthenticated || !shouldLogAccess(to.path)) return
    postAccessLogApi(to.path).catch(() => {})
  })

  router.onError((err) => {
    if (/Loading chunk .* failed|Failed to fetch dynamically imported module/i.test(err?.message ?? '')) {
      window.location.reload()
    }
  })
}
