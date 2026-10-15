import { describe, expect, it, vi } from 'vitest'
import { homePathFor, isCustomerOnly, PORTAL_PATH, portalRedirect, shouldLogAccess } from '@/router/guards'

vi.mock('@/api/auditApi')
vi.mock('@/api/authApi')

describe('접근 로그 중복 방지', () => {
  it('같은 경로는 30초 안에 한 번만 보낸다', () => {
    const t0 = 1_000_000
    expect(shouldLogAccess('/admin/users', t0)).toBe(true)
    expect(shouldLogAccess('/admin/users', t0 + 5_000)).toBe(false)
    expect(shouldLogAccess('/admin/codes', t0 + 5_000)).toBe(true)
    expect(shouldLogAccess('/admin/users', t0 + 31_000)).toBe(true)
  })
})

describe('고객 SR 포털 라우팅 (SCR-SM-010)', () => {
  it('CUSTOMER 단독 역할만 포털 사용자로 본다', () => {
    expect(isCustomerOnly(['CUSTOMER'])).toBe(true)
    expect(isCustomerOnly(['CUSTOMER', 'PM'])).toBe(false)
    expect(isCustomerOnly([])).toBe(false)
  })

  it('로그인 후 첫 화면 — 고객은 포털, 내부 사용자는 홈', () => {
    expect(homePathFor(['CUSTOMER'])).toBe(PORTAL_PATH)
    expect(homePathFor(['PM'])).toBe('/home')
  })

  it('고객은 내부 화면 대신 포털로 돌린다 (포털·공개 경로는 그대로)', () => {
    expect(portalRedirect(['CUSTOMER'], '/sm/srs')).toBe(PORTAL_PATH)
    expect(portalRedirect(['CUSTOMER'], '/asis/viewer')).toBe(PORTAL_PATH)
    expect(portalRedirect(['CUSTOMER'], '/portal')).toBeNull()
    expect(portalRedirect(['CUSTOMER'], '/portal/srs/sr-1')).toBeNull()
    expect(portalRedirect(['CUSTOMER'], '/login')).toBeNull()
    expect(portalRedirect(['OPERATOR'], '/sm/srs')).toBeNull()
  })
})
