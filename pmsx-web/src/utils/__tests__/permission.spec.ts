import { describe, expect, it } from 'vitest'
import type { ScreenAnalysis } from '@/types/api'
import { allowedStatuses, canEditScreen } from '@/utils/permission'

const roles = (list: string[]) => (want: string[]) => want.some((r) => list.includes(r))
const analysis = (ownerId: string | null): ScreenAnalysis => ({
  status: 'WORKING', owner: ownerId ? { userId: ownerId, name: 'x' } : null, reviewer: null, updatedAt: '2026-10-15T00:00:00+09:00',
})

describe('화면 편집 권한 (docs/06)', () => {
  it('PM·DEV 는 담당과 관계없이 편집할 수 있다', () => {
    expect(canEditScreen(roles(['PM']), analysis('u-other'), 'u-me')).toBe(true)
    expect(canEditScreen(roles(['DEV']), analysis('u-other'), 'u-me')).toBe(true)
  })

  it('ANALYST 는 본인 담당이거나 미배정 화면만 편집한다', () => {
    expect(canEditScreen(roles(['ANALYST']), analysis('u-me'), 'u-me')).toBe(true)
    expect(canEditScreen(roles(['ANALYST']), analysis(null), 'u-me')).toBe(true)
    expect(canEditScreen(roles(['ANALYST']), analysis('u-other'), 'u-me')).toBe(false)
  })

  it('SYS_ADMIN·CUSTOMER 만 가진 사용자는 편집할 수 없다', () => {
    expect(canEditScreen(roles(['SYS_ADMIN']), analysis(null), 'u-me')).toBe(false)
    expect(canEditScreen(roles(['CUSTOMER']), analysis(null), 'u-me')).toBe(false)
  })

  it('확정(DONE)은 PM·PL 만 고를 수 있다', () => {
    expect(allowedStatuses(roles(['PL']))).toContain('DONE')
    expect(allowedStatuses(roles(['ANALYST']))).not.toContain('DONE')
  })
})
