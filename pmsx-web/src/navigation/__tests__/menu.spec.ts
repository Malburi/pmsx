import { describe, expect, it } from 'vitest'
import { filterByProjectType, filterByRoles, NAV_ITEMS, type NavItem } from '@/navigation/menu'

const ITEMS: NavItem[] = [
  { key: 'asis', titleKey: 'a', release: 'R0', children: [{ key: 'viewer', titleKey: 'v', to: '/v', release: 'R0' }] },
  {
    key: 'admin', titleKey: 'm', release: 'R0', children: [
      { key: 'user', titleKey: 'u', to: '/u', release: 'R0', roles: ['SYS_ADMIN'] },
      { key: 'audit', titleKey: 'l', to: '/l', release: 'R0', roles: ['SYS_ADMIN', 'PM'] },
    ],
  },
]

describe('역할별 메뉴 노출', () => {
  it('관리 역할이 없는 사용자는 관리 그룹이 통째로 숨겨진다', () => {
    const visible = filterByRoles(ITEMS, (roles) => roles.includes('ANALYST'))
    expect(visible.map((g) => g.key)).toEqual(['asis'])
  })

  it('PM 은 허용된 관리 메뉴만 본다', () => {
    const visible = filterByRoles(ITEMS, (roles) => roles.includes('PM'))
    expect(visible[1].children?.map((c) => c.key)).toEqual(['audit'])
  })
})

describe('프로젝트 유형별 메뉴 (docs/02 [SI]/[SM])', () => {
  const keys = (items: NavItem[]) => items.map((g) => g.key)
  const childKeys = (items: NavItem[], group: string) => items.find((g) => g.key === group)?.children?.map((c) => c.key) ?? []

  it('SI 프로젝트는 운영(SM) 메뉴와 SLA 정책을 숨긴다', () => {
    const si = filterByProjectType(NAV_ITEMS, 'SI')
    expect(keys(si)).not.toContain('sm')
    expect(keys(si)).toContain('schedule')
    expect(childKeys(si, 'admin')).not.toContain('cm-sla')
    expect(childKeys(si, 'quality')).toContain('qa-test')
  })

  it('SM 프로젝트는 일정·감리·테스트를 숨기고 운영 메뉴를 보인다', () => {
    const sm = filterByProjectType(NAV_ITEMS, 'SM')
    expect(keys(sm)).toContain('sm')
    expect(keys(sm)).not.toContain('schedule')
    expect(childKeys(sm, 'deliverable')).not.toContain('dl-audit')
    expect(childKeys(sm, 'quality')).not.toContain('qa-test')
    expect(childKeys(sm, 'admin')).toContain('cm-sla')
  })

  it('R3 메뉴는 모두 화면이 연결되어 있다', () => {
    const r3 = NAV_ITEMS.flatMap((g) => g.children ?? []).filter((i) => i.release === 'R3')
    expect(r3.length).toBeGreaterThan(0)
    expect(r3.every((i) => !!i.to)).toBe(true)
  })
})
