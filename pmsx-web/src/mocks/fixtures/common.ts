// mock 모드 픽스처 — 사용자·프로젝트·시스템·메뉴·수집 회차
import type { MenuNode, Project, RunSummary, ScreenDetail, SystemSummary, User } from '@/types/api'

export const MOCK_USERS: Record<string, User & { password: string }> = {
  // 서버 시드와 같게 admin 은 샘플 프로젝트 PM 역할도 가진다 (roles = 시스템 역할 + 프로젝트 역할)
  admin: { userId: 'u-admin', loginId: 'admin', name: '관리자', roles: ['SYS_ADMIN', 'PM'], password: 'pmsx1234!' },
  analyst: { userId: 'u-analyst', loginId: 'analyst', name: '김분석', roles: ['ANALYST'], password: 'pmsx1234!' },
  // R3: SM 운영 담당자와 고객(포털 전용) 계정
  operator: { userId: 'u-op01', loginId: 'operator', name: '정운영', roles: ['OPERATOR'], password: 'pmsx1234!' },
  customer: { userId: 'u-cust01', loginId: 'customer', name: '최고객', roles: ['CUSTOMER'], password: 'pmsx1234!' },
}

export const MOCK_PROJECTS: Project[] = [
  {
    projectId: 'prj-si-01', type: 'SI', name: '○○공사 차세대 SI', customer: '○○공사', startDate: '2026-09-01', endDate: '2027-08-31',
    contractMm: 96, pmUserId: 'u-admin', pmName: '관리자', originProjectId: null,
  },
  // R3: 인사시스템 운영(SM) — 이전 차수 SI 에서 인수인계 받은 운영 프로젝트
  {
    projectId: 'prj-sm-01', type: 'SM', name: '○○공사 인사·급여 운영(SM)', customer: '○○공사', startDate: '2026-01-01', endDate: '2026-12-31',
    contractMm: 36, pmUserId: 'u-admin', pmName: '관리자', originProjectId: null,
  },
]

export const MOCK_SYSTEMS: SystemSummary[] = [
  {
    systemId: 'sys-hr', projectId: 'prj-si-01', code: 'HR', name: '인사시스템',
    uiTech: ['jsp-jquery'], serverTech: ['spring-mybatis'], dbType: 'oracle', testUrl: 'https://hr-dev.example.local',
    screenCount: 21, doneCount: 7,
    lastRun: { runId: 'run-hr-3', seq: 3, collectedAt: '2026-10-02T10:12:00+09:00' },
  },
  {
    systemId: 'sys-py', projectId: 'prj-si-01', code: 'PY', name: '급여시스템',
    uiTech: ['nexacro'], serverTech: ['spring-mybatis'], dbType: 'tibero',
    screenCount: 1, doneCount: 0, lastRun: { runId: 'run-py-1', seq: 1, collectedAt: '2026-10-15T09:00:00+09:00' },
  },
  // R3: SM 프로젝트의 운영 대상 시스템 (인사시스템 As-Is 자산을 인수인계로 복사해 온 상태)
  {
    systemId: 'sys-hr-op', projectId: 'prj-sm-01', code: 'HR', name: '인사시스템(운영)',
    uiTech: ['jsp-jquery'], serverTech: ['spring-mybatis'], dbType: 'oracle',
    screenCount: 21, doneCount: 21, lastRun: { runId: 'run-hr-3', seq: 3, collectedAt: '2026-10-02T10:12:00+09:00' },
  },
]

const scr = (screenId: string, screenCode: string, status: 'AUTO' | 'WORKING' | 'REVIEW' | 'DONE', kind = 'page') =>
  ({ screenId, screenCode, kind, status })

export const MOCK_MENU_TREE: Record<string, MenuNode[]> = {
  'sys-hr': [
    {
      menuId: 'm-hr-01', name: '인사관리', order: 1, screen: null, children: [
        {
          menuId: 'm-hr-0101', name: '사원관리', order: 1, screen: scr('scr-hr0101', 'HR0101', 'REVIEW'), children: [
            { menuId: 'm-hr-0101p01', name: '사원검색 (팝업)', order: 1, screen: scr('scr-hr0101p01', 'HR0101P01', 'AUTO', 'popup'), children: [] },
            { menuId: 'm-hr-0102', name: '사원상세 (팝업)', order: 2, screen: scr('scr-hr0102', 'HR0102', 'WORKING', 'popup'), children: [] },
          ],
        },
        { menuId: 'm-hr-0103', name: '발령관리', order: 2, screen: scr('scr-hr0103', 'HR0103', 'DONE'), children: [] },
        { menuId: 'm-hr-0104', name: '부서관리', order: 3, screen: scr('scr-hr0104', 'HR0104', 'DONE'), children: [] },
      ],
    },
    {
      menuId: 'm-hr-02', name: '근태관리', order: 2, screen: null, children: [
        { menuId: 'm-hr-0201', name: '근태신청', order: 1, screen: scr('scr-hr0201', 'HR0201', 'DONE'), children: [] },
        { menuId: 'm-hr-0202', name: '근태승인', order: 2, screen: scr('scr-hr0202', 'HR0202', 'AUTO'), children: [] },
      ],
    },
  ],
  'sys-py': [],
}

// HR0101 외 화면은 캡처 없이 요약만 있는 상태로 응답 (수집 실패/수동 캡처 대기 상황 재현)
export const genericScreen = (screenId: string, screenCode: string, name: string, path: string[], status: ScreenDetail['analysis']['status'], kind: string): ScreenDetail => ({
  screenId, screenCode, name, kind, uiTech: 'jsp-jquery',
  sourceFile: `/WEB-INF/jsp/hr/${screenCode}.jsp`,
  menuPath: path,
  analysis: { status, owner: null, reviewer: null, updatedAt: '2026-10-02T10:12:00+09:00' },
  capture: null,
  elements: [
    {
      elementId: `el-${screenId}-btnSearch`, seqNo: 1, elementCode: 'btnSearch', label: '조회', type: 'button',
      box: null, binding: null, matched: 'static-only', danger: false, events: [{ event: 'click', handler: 'fn_search()' }],
      manual: false, hidden: false,
    },
  ],
})

export const MOCK_RUNS: Record<string, RunSummary[]> = {
  'sys-hr': [
    {
      runId: 'run-hr-3', systemId: 'sys-hr', seq: 3, collectedAt: '2026-10-02T10:12:00+09:00', uploadedAt: '2026-10-02T11:03:00+09:00',
      stats: { menus: 9, screens: 7, captured: 6, elements: 84, edges: 412, blockedRequests: 5, unmatchedElements: 3 },
      diff: { screensAdded: 1, screensRemoved: 0, elementsAdded: 6, elementsRemoved: 1, edgesAdded: 38, edgesRemoved: 4 },
    },
    {
      runId: 'run-hr-2', systemId: 'sys-hr', seq: 2, collectedAt: '2026-09-25T16:40:00+09:00', uploadedAt: '2026-09-25T17:10:00+09:00',
      stats: { menus: 9, screens: 6, captured: 5, elements: 79, edges: 378, blockedRequests: 4, unmatchedElements: 6 },
      diff: { screensAdded: 2, screensRemoved: 0, elementsAdded: 21, elementsRemoved: 0, edgesAdded: 120, edgesRemoved: 2 },
    },
    {
      runId: 'run-hr-1', systemId: 'sys-hr', seq: 1, collectedAt: '2026-09-18T09:30:00+09:00', uploadedAt: '2026-09-18T10:02:00+09:00',
      stats: { menus: 8, screens: 4, captured: 4, elements: 58, edges: 260, blockedRequests: 3, unmatchedElements: 9 },
      diff: { screensAdded: 4, screensRemoved: 0, elementsAdded: 58, elementsRemoved: 0, edgesAdded: 260, edgesRemoved: 0 },
    },
  ],
  'sys-py': [
    {
      runId: 'run-py-1', systemId: 'sys-py', seq: 1, collectedAt: '2026-10-15T09:00:00+09:00', uploadedAt: '2026-10-15T09:20:00+09:00',
      stats: { menus: 2, screens: 1, captured: 0, screensWireframe: 1, elements: 7, edges: 38, warnings: 2 },
      diff: { screensAdded: 1, screensRemoved: 0, elementsAdded: 7, elementsRemoved: 0, edgesAdded: 38, edgesRemoved: 0 },
    },
  ],
}

export const MOCK_REPORT_HTML = `<!doctype html><html lang="ko"><meta charset="utf-8"><title>수집 리포트 (mock)</title>
<body style="font-family:'Malgun Gothic',sans-serif;padding:24px"><h1>수집 실행 리포트 (mock)</h1>
<p>실제 리포트는 수집기 패키지의 report.html 이 표시됩니다.</p></body></html>`
