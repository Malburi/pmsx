// LNB 메뉴 — docs/02_메뉴구조_화면목록.md 기준. `to` 가 없는 항목은 이후 릴리스에서 제공된다.
import { MM_VIEW_ROLES, RESOURCE_VIEW_ROLES, SLA_POLICY_VIEW_ROLES, SM_VIEW_ROLES, TIMESHEET_ROLES, WORK_ASSIGN_VIEW_ROLES } from '@/utils/permission'

export type ProjectType = 'SI' | 'SM'

export interface NavItem {
  key: string
  titleKey: string
  icon?: string
  to?: string
  screenCode?: string
  release: 'R0' | 'R1' | 'R2' | 'R3'
  /** 지정하면 이 역할 중 하나를 가진 사용자에게만 보인다 (docs/06 권한 매트릭스) */
  roles?: string[]
  /** 지정하면 이 유형의 프로젝트를 선택했을 때만 보인다 (docs/02 의 [SI]/[SM] 표기) */
  projectTypes?: ProjectType[]
  children?: NavItem[]
}

/** 관리 메뉴 노출 역할 */
export const ADMIN_ROLES = ['SYS_ADMIN']
export const MANAGER_ROLES = ['SYS_ADMIN', 'PMO', 'PM']

export const NAV_ITEMS: NavItem[] = [
  { key: 'home', titleKey: 'nav.home', icon: 'ri-home-5-line', to: '/home', screenCode: 'SCR-CM-001', release: 'R2' },
  {
    key: 'asis', titleKey: 'nav.asis.group', icon: 'ri-layout-masonry-line', release: 'R0', children: [
      { key: 'asis-viewer', titleKey: 'nav.asis.viewer', to: '/asis/viewer', screenCode: 'SCR-AS-001', release: 'R0' },
      { key: 'asis-progress', titleKey: 'nav.asis.progress', to: '/asis/progress', screenCode: 'SCR-AS-002', release: 'R1' },
      { key: 'asis-systems', titleKey: 'nav.asis.systems', to: '/asis/systems', screenCode: 'SCR-AS-003', release: 'R0' },
      { key: 'asis-runs', titleKey: 'nav.asis.runs', to: '/asis/runs', screenCode: 'SCR-AS-004', release: 'R0' },
      { key: 'asis-tables', titleKey: 'nav.asis.tables', to: '/asis/tables', screenCode: 'SCR-AS-005', release: 'R1' },
      { key: 'asis-erd', titleKey: 'nav.asis.erd', to: '/asis/erd', screenCode: 'SCR-AS-006', release: 'R1' },
      { key: 'asis-crud', titleKey: 'nav.asis.crud', to: '/asis/crud', screenCode: 'SCR-AS-007', release: 'R1' },
      { key: 'asis-navmap', titleKey: 'nav.asis.navmap', to: '/asis/navmap', screenCode: 'SCR-AS-008', release: 'R1' },
      { key: 'asis-impact', titleKey: 'nav.asis.impact', to: '/asis/impact', screenCode: 'SCR-AS-009', release: 'R3' },
      { key: 'asis-export', titleKey: 'nav.asis.export', to: '/asis/export', screenCode: 'SCR-AS-010', release: 'R1' },
    ],
  },
  {
    key: 'schedule', titleKey: 'nav.schedule.group', icon: 'ri-calendar-schedule-line', release: 'R2', projectTypes: ['SI'], children: [
      { key: 'pj-wbs', titleKey: 'nav.schedule.wbs', to: '/schedule/wbs', screenCode: 'SCR-PJ-001', release: 'R2' },
      { key: 'pj-kanban', titleKey: 'nav.schedule.kanban', to: '/schedule/kanban', screenCode: 'SCR-PJ-002', release: 'R2' },
      { key: 'pj-baseline', titleKey: 'nav.schedule.baseline', to: '/schedule/baseline', screenCode: 'SCR-PJ-003', release: 'R2' },
      { key: 'pj-weekly', titleKey: 'nav.schedule.weekly', to: '/schedule/weekly', screenCode: 'SCR-PJ-004', release: 'R2' },
    ],
  },
  {
    key: 'resource', titleKey: 'nav.resource.group', icon: 'ri-team-line', release: 'R2', children: [
      { key: 'rs-pool', titleKey: 'nav.resource.pool', to: '/resource/pool', screenCode: 'SCR-RS-001', release: 'R2', roles: RESOURCE_VIEW_ROLES },
      { key: 'rs-plan', titleKey: 'nav.resource.plan', to: '/resource/plan', screenCode: 'SCR-RS-002', release: 'R2', roles: RESOURCE_VIEW_ROLES },
      { key: 'rs-assign', titleKey: 'nav.resource.assign', to: '/resource/assign', screenCode: 'SCR-RS-003', release: 'R2', roles: WORK_ASSIGN_VIEW_ROLES },
      { key: 'rs-timesheet', titleKey: 'nav.resource.timesheet', to: '/resource/timesheet', screenCode: 'SCR-RS-004', release: 'R2', roles: TIMESHEET_ROLES },
      { key: 'rs-mm', titleKey: 'nav.resource.mm', to: '/resource/mm', screenCode: 'SCR-RS-005', release: 'R2', roles: MM_VIEW_ROLES },
    ],
  },
  {
    key: 'deliverable', titleKey: 'nav.deliverable.group', icon: 'ri-file-list-3-line', release: 'R2', children: [
      { key: 'dl-list', titleKey: 'nav.deliverable.list', to: '/deliverable/list', screenCode: 'SCR-DL-001', release: 'R2' },
      { key: 'dl-req', titleKey: 'nav.deliverable.requirement', to: '/deliverable/requirements', screenCode: 'SCR-DL-002', release: 'R2' },
      { key: 'dl-trace', titleKey: 'nav.deliverable.trace', to: '/deliverable/trace', screenCode: 'SCR-DL-003', release: 'R2' },
      { key: 'dl-audit', titleKey: 'nav.deliverable.audit', to: '/deliverable/audit', screenCode: 'SCR-DL-004', release: 'R2', projectTypes: ['SI'] },
    ],
  },
  {
    key: 'quality', titleKey: 'nav.quality.group', icon: 'ri-bug-line', release: 'R2', children: [
      { key: 'qa-issue', titleKey: 'nav.quality.issue', to: '/quality/issues', screenCode: 'SCR-QA-001', release: 'R2' },
      { key: 'qa-risk', titleKey: 'nav.quality.risk', to: '/quality/risks', screenCode: 'SCR-QA-002', release: 'R2' },
      { key: 'qa-cr', titleKey: 'nav.quality.cr', to: '/quality/change-requests', screenCode: 'SCR-QA-003', release: 'R2' },
      { key: 'qa-test', titleKey: 'nav.quality.test', to: '/quality/tests', screenCode: 'SCR-QA-004', release: 'R2', projectTypes: ['SI'] },
      { key: 'qa-defect', titleKey: 'nav.quality.defect', to: '/quality/defects', screenCode: 'SCR-QA-005', release: 'R2' },
      { key: 'qa-meeting', titleKey: 'nav.quality.meeting', to: '/quality/meetings', screenCode: 'SCR-QA-006', release: 'R2' },
    ],
  },
  {
    key: 'sm', titleKey: 'nav.sm.group', icon: 'ri-customer-service-2-line', release: 'R3', projectTypes: ['SM'], roles: SM_VIEW_ROLES, children: [
      { key: 'sm-sr', titleKey: 'nav.sm.sr', to: '/sm/srs', screenCode: 'SCR-SM-001', release: 'R3' },
      { key: 'sm-incident', titleKey: 'nav.sm.incident', to: '/sm/incidents', screenCode: 'SCR-SM-002', release: 'R3' },
      { key: 'sm-problem', titleKey: 'nav.sm.problem', to: '/sm/problems', screenCode: 'SCR-SM-003', release: 'R3' },
      { key: 'sm-change', titleKey: 'nav.sm.change', to: '/sm/changes', screenCode: 'SCR-SM-004', release: 'R3' },
      { key: 'sm-routine', titleKey: 'nav.sm.routine', to: '/sm/routine', screenCode: 'SCR-SM-005', release: 'R3' },
      { key: 'sm-asset', titleKey: 'nav.sm.asset', to: '/sm/assets', screenCode: 'SCR-SM-006', release: 'R3' },
      { key: 'sm-kb', titleKey: 'nav.sm.kb', to: '/sm/kb', screenCode: 'SCR-SM-007', release: 'R3' },
      { key: 'sm-sla', titleKey: 'nav.sm.sla', to: '/sm/sla', screenCode: 'SCR-SM-008', release: 'R3' },
      { key: 'sm-report', titleKey: 'nav.sm.report', to: '/sm/reports', screenCode: 'SCR-SM-009', release: 'R3' },
    ],
  },
  {
    key: 'admin', titleKey: 'nav.admin.group', icon: 'ri-settings-3-line', release: 'R0', children: [
      { key: 'cm-project', titleKey: 'nav.admin.project', to: '/admin/projects', screenCode: 'SCR-CM-002', release: 'R0', roles: MANAGER_ROLES },
      { key: 'cm-user', titleKey: 'nav.admin.user', to: '/admin/users', screenCode: 'SCR-CM-003', release: 'R0', roles: ADMIN_ROLES },
      { key: 'cm-template', titleKey: 'nav.admin.template', to: '/admin/methodologies', screenCode: 'SCR-CM-004', release: 'R2', roles: ['SYS_ADMIN', 'PM'] },
      { key: 'cm-code', titleKey: 'nav.admin.code', to: '/admin/codes', screenCode: 'SCR-CM-005', release: 'R0', roles: ['SYS_ADMIN', 'PM'] },
      { key: 'cm-excel', titleKey: 'nav.admin.excel', to: '/admin/excel-templates', screenCode: 'SCR-CM-006', release: 'R0', roles: ['SYS_ADMIN', 'PM'] },
      { key: 'cm-sla', titleKey: 'nav.admin.sla', to: '/admin/sla-policies', screenCode: 'SCR-CM-007', release: 'R3', projectTypes: ['SM'], roles: SLA_POLICY_VIEW_ROLES },
      { key: 'cm-notify', titleKey: 'nav.admin.notify', to: '/admin/notifications', screenCode: 'SCR-CM-008', release: 'R2' },
      { key: 'cm-audit', titleKey: 'nav.admin.audit', to: '/admin/audit-logs', screenCode: 'SCR-CM-009', release: 'R0', roles: MANAGER_ROLES },
      { key: 'cm-system', titleKey: 'nav.admin.system', to: '/admin/system', screenCode: 'SCR-CM-010', release: 'R0', roles: MANAGER_ROLES },
    ],
  },
]

/**
 * 선택한 프로젝트 유형에 맞는 메뉴만 남긴다 (SI: 일정·감리·테스트 / SM: 운영·SLA 정책).
 * 프로젝트를 아직 고르지 않았으면(type=null) 유형 표기가 있는 메뉴를 모두 숨긴다.
 */
export const filterByProjectType = (items: NavItem[], type: ProjectType | null): NavItem[] =>
  items.flatMap((item) => {
    if (item.projectTypes && (!type || !item.projectTypes.includes(type))) return []
    if (!item.children) return [item]
    const children = filterByProjectType(item.children, type)
    return children.length ? [{ ...item, children }] : []
  })

export const isEnabled = (item: NavItem): boolean => !!item.to || !!item.children?.some(isEnabled)

/** 역할로 보이는 메뉴만 남긴다. 하위 항목이 모두 숨겨진 그룹은 그룹째 숨긴다. */
export const filterByRoles = (items: NavItem[], hasAnyRole: (roles: string[]) => boolean): NavItem[] =>
  items.flatMap((item) => {
    if (item.roles && !hasAnyRole(item.roles)) return []
    if (!item.children) return [item]
    const children = filterByRoles(item.children, hasAnyRole)
    return children.length ? [{ ...item, children }] : []
  })
