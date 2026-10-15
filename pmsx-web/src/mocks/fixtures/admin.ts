// mock 모드 픽스처 — 공통코드·용어·사용자·멤버·감사로그·엑셀·첨부·라이선스 (docs/10)
import type {
  AdminUser, AuditAction, AuditLog, Code, CodeGroup, ExcelDataset, ExcelTemplate, FileMeta, LicenseStatus, Member, Role, SystemInfo, Term,
} from '@/types/common'

export const MOCK_CODE_GROUPS: CodeGroup[] = [
  { groupCode: 'PROJECT_TYPE', name: '프로젝트 유형', description: 'SI 구축 / SM 운영', system: true },
  { groupCode: 'ANALYSIS_STATUS', name: '분석 상태', description: 'As-Is 화면 분석 진행 상태', system: true },
  { groupCode: 'ELEMENT_TYPE', name: '화면 요소 유형', description: null, system: true },
  { groupCode: 'UI_TECH', name: '화면 기술', description: '수집기 파서 선택 기준', system: true },
  { groupCode: 'DB_TYPE', name: 'DB 종류', description: null, system: true },
  { groupCode: 'ORG_TYPE', name: '소속 구분', description: '자사 / 협력사 / 고객', system: true },
  { groupCode: 'GRADE', name: '기술 등급', description: 'SW 기술자 등급', system: true },
  { groupCode: 'DOC_KIND', name: '산출물 종류', description: '프로젝트에서 추가한 그룹 예시', system: false },
]

const c = (code: string, name: string, sortOrder: number, useYn = true, attr1: string | null = null): Code =>
  ({ code, name, sortOrder, useYn, attr1, attr2: null })

export const MOCK_CODES: Record<string, Code[]> = {
  PROJECT_TYPE: [c('SI', 'SI 구축', 1), c('SM', 'SM 운영', 2)],
  ANALYSIS_STATUS: [c('AUTO', '자동수집', 1), c('WORKING', '분석중', 2), c('REVIEW', '검토요청', 3), c('DONE', '확정', 4)],
  ELEMENT_TYPE: [c('button', '버튼', 1), c('input', '입력', 2), c('combo', '콤보', 3), c('grid', '그리드', 4), c('popup', '팝업호출', 5), c('other', '기타', 9)],
  UI_TECH: [c('jsp-jquery', 'JSP + jQuery', 1), c('nexacro', 'Nexacro', 2), c('websquare', 'WebSquare', 3), c('vue', 'Vue', 4), c('react', 'React', 5), c('winforms', 'WinForms', 6)],
  DB_TYPE: [c('oracle', 'Oracle', 1), c('tibero', 'Tibero', 2), c('mssql', 'MS SQL Server', 3), c('postgresql', 'PostgreSQL', 4), c('mysql', 'MySQL', 5)],
  ORG_TYPE: [c('OWN', '자사', 1), c('PARTNER', '협력사', 2), c('CUSTOMER', '고객', 3)],
  GRADE: [c('SPECIAL', '특급', 1, true, '10'), c('HIGH', '고급', 2, true, '7'), c('MID', '중급', 3, true, '4'), c('LOW', '초급', 4, true, '0')],
  DOC_KIND: [c('REQ', '요구사항정의서', 1), c('SCR', '화면정의서', 2), c('PGM', '프로그램사양서', 3), c('TBL', '테이블정의서', 4), c('OLD', '구 양식(미사용)', 9, false)],
}

const TERM_SEED: [string, string | null, string, string | null][] = [
  ['사원번호', 'EMP_NO', '사원을 식별하는 8자리 번호. 입사연도 4자리 + 일련번호 4자리.', '인사'],
  ['부서코드', 'DEPT_CD', '조직 단위를 식별하는 코드.', '인사'],
  ['입사일자', 'HIRE_DT', '최초 입사한 날짜 (YYYYMMDD).', '인사'],
  ['발령', 'APPNT', '부서·직위 변경 등 인사 이동.', '인사'],
  ['직위코드', 'POS_CD', '직위(사원~부장)를 나타내는 공통코드.', '인사'],
  ['사용여부', 'USE_YN', 'Y/N 으로 사용 중인 데이터인지 나타냄. 논리 삭제 시 N.', '공통'],
  ['등록일시', 'REG_DT', '데이터가 처음 등록된 일시.', '공통'],
  ['수정일시', 'UPD_DT', '데이터가 마지막으로 수정된 일시.', '공통'],
  ['서비스요청', 'SR', '운영 중 고객이 요청한 처리 건 (문의·개선·데이터 추출 등).', 'SM'],
  ['서비스수준협약', 'SLA', '응답·처리 목표 시간에 대한 고객과의 약속.', 'SM'],
  ['근본원인분석', 'RCA', '장애의 근본 원인을 찾아 재발을 막는 분석.', 'SM'],
  ['작업분할구조', 'WBS', '프로젝트 범위를 관리 가능한 작업 단위로 나눈 구조.', 'PM'],
  ['맨먼스', 'MM', '1인이 1개월 일한 공수 단위.', 'PM'],
  ['변경요청', 'CR', '확정된 범위·일정을 바꾸자는 공식 요청.', 'PM'],
  ['획득가치', 'EV', '완료한 작업의 계획 원가 기준 가치.', 'PM'],
  ['추적성', null, '요구사항부터 화면·프로그램·테스트·결함까지 이어지는 연결 관계.', 'PM'],
  ['화면정의서', null, '화면 레이아웃·요소·이벤트를 정리한 설계 산출물.', '산출물'],
  ['프로그램사양서', null, '프로그램의 처리 로직·입출력을 정리한 산출물.', '산출물'],
  ['테이블정의서', null, '테이블·컬럼·키·인덱스를 정리한 산출물.', '산출물'],
  ['CRUD 매트릭스', 'CRUD', '프로그램(화면)과 테이블 사이의 생성·조회·수정·삭제 관계 표.', '산출물'],
  ['감리', null, '발주기관을 대신해 사업 수행 품질을 점검하는 제3자 활동.', 'PM'],
  ['컷오버', 'CUTOVER', '구 시스템에서 신 시스템으로 전환하는 시점의 작업.', 'PM'],
]

export const MOCK_TERMS: Term[] = TERM_SEED.map(([term, abbr, definition, domain], i) => ({
  termId: `term-${String(i + 1).padStart(3, '0')}`, term, abbr, definition, domain,
}))

export const MOCK_ROLES: Role[] = [
  { roleId: 'SYS_ADMIN', scope: 'SYSTEM', name: '시스템 관리자' },
  { roleId: 'PMO', scope: 'SYSTEM', name: 'PMO' },
  { roleId: 'PM', scope: 'PROJECT', name: '프로젝트 관리자' },
  { roleId: 'PL', scope: 'PROJECT', name: '파트 리더' },
  { roleId: 'ANALYST', scope: 'PROJECT', name: '분석 담당' },
  { roleId: 'DEV', scope: 'PROJECT', name: '개발자' },
  { roleId: 'QA', scope: 'PROJECT', name: '품질 담당' },
  { roleId: 'OPERATOR', scope: 'PROJECT', name: '운영 담당' },
  { roleId: 'CUSTOMER', scope: 'PROJECT', name: '고객' },
  { roleId: 'PARTNER', scope: 'PROJECT', name: '협력사' },
]

export const MOCK_ADMIN_USERS: AdminUser[] = [
  { userId: 'u-admin', loginId: 'admin', name: '관리자', email: 'admin@pmsx.local', orgType: 'OWN', authType: 'LOCAL', roles: ['SYS_ADMIN'], locked: false, lastLoginAt: '2026-10-15T08:55:00+09:00' },
  { userId: 'u-analyst', loginId: 'analyst', name: '김분석', email: 'analyst@pmsx.local', orgType: 'OWN', authType: 'LOCAL', roles: [], locked: false, lastLoginAt: '2026-10-14T17:20:00+09:00' },
  { userId: 'u-pm01', loginId: 'pm01', name: '박관리', email: 'pm01@pmsx.local', orgType: 'OWN', authType: 'LDAP', roles: [], locked: false, lastLoginAt: '2026-10-15T09:02:00+09:00' },
  { userId: 'u-dev01', loginId: 'dev01', name: '이개발', email: null, orgType: 'PARTNER', authType: 'LOCAL', roles: [], locked: false, lastLoginAt: '2026-10-13T11:40:00+09:00' },
  { userId: 'u-cust01', loginId: 'cust01', name: '최고객', email: 'cust01@customer.example', orgType: 'CUSTOMER', authType: 'LOCAL', roles: [], locked: false, lastLoginAt: null },
  { userId: 'u-locked01', loginId: 'locked01', name: '정잠김', email: null, orgType: 'PARTNER', authType: 'LOCAL', roles: [], locked: true, lastLoginAt: '2026-09-30T10:00:00+09:00' },
  { userId: 'u-analyst2', loginId: 'analyst2', name: '최분석', email: null, orgType: 'OWN', authType: 'LOCAL', roles: [], locked: false, lastLoginAt: '2026-10-14T10:00:00+09:00' },
  { userId: 'u-pl', loginId: 'pl01', name: '이PL', email: 'pl01@pmsx.local', orgType: 'OWN', authType: 'LOCAL', roles: [], locked: false, lastLoginAt: '2026-10-15T08:30:00+09:00' },
  { userId: 'u-pmo01', loginId: 'pmo01', name: '한PMO', email: 'pmo@pmsx.local', orgType: 'OWN', authType: 'LDAP', roles: ['PMO'], locked: false, lastLoginAt: '2026-10-10T14:10:00+09:00' },
]

export const MOCK_MEMBERS: Record<string, Member[]> = {
  'prj-si-01': [
    { userId: 'u-admin', loginId: 'admin', name: '관리자', orgType: 'OWN', roleId: 'PM', inDate: '2026-09-01', outDate: null },
    { userId: 'u-pm01', loginId: 'pm01', name: '박관리', orgType: 'OWN', roleId: 'PL', inDate: '2026-09-01', outDate: '2027-08-31' },
    { userId: 'u-analyst', loginId: 'analyst', name: '김분석', orgType: 'OWN', roleId: 'ANALYST', inDate: '2026-09-15', outDate: '2027-02-28' },
    { userId: 'u-dev01', loginId: 'dev01', name: '이개발', orgType: 'PARTNER', roleId: 'DEV', inDate: '2026-11-01', outDate: '2027-06-30' },
    { userId: 'u-cust01', loginId: 'cust01', name: '최고객', orgType: 'CUSTOMER', roleId: 'CUSTOMER', inDate: null, outDate: null },
  ],
}

// 감사 로그 — 결정적(시드 고정) 의사 난수로 120건 생성
const AUDIT_SAMPLES: [AuditAction, string | null, string | null][] = [
  ['LOGIN', null, null], ['ACCESS', '/asis/viewer/sys-hr/scr-hr0101', null], ['VIEW_SOURCE', 'node:fn_save', 'HR0101.js:38'],
  ['DOWNLOAD', 'excel:as-elements', 'systemId=sys-hr'], ['UPLOAD', 'run:sys-hr', 'pmsx-HR-20261014-230807.zip'],
  ['UPDATE', 'screen:scr-hr0101', 'status AUTO → REVIEW'], ['LOGIN_FAIL', null, 'AUTH_INVALID'], ['CREATE', 'user:dev01', null],
  ['DELETE', 'attachment:att-12', '화면설계_v1.pdf'], ['LOGOUT', null, null], ['LICENSE', 'license', '키 등록 (○○공사, ~2027-12-31)'],
]
const AUDIT_USERS = ['admin', 'analyst', 'pm01', 'dev01']
let seed = 7
const rnd = () => { seed = (seed * 9301 + 49297) % 233280; return seed / 233280 }

export const MOCK_AUDIT_LOGS: AuditLog[] = Array.from({ length: 120 }, (_, i) => {
  const [action, target, detail] = AUDIT_SAMPLES[Math.floor(rnd() * AUDIT_SAMPLES.length)]
  const loginId = AUDIT_USERS[Math.floor(rnd() * AUDIT_USERS.length)]
  const at = new Date(Date.UTC(2026, 9, 15, 0, 0) - i * 47 * 60_000).toISOString()
  return {
    logId: `log-${String(120 - i).padStart(4, '0')}`, at, userId: `u-${loginId}`, loginId, action, target, detail,
    ip: `10.10.${Math.floor(rnd() * 4) + 1}.${Math.floor(rnd() * 200) + 10}`,
  }
})

export const MOCK_DATASETS: ExcelDataset[] = [
  {
    datasetId: 'codes', name: '공통코드', importable: true, params: ['groupCode'], columns: [
      { field: 'code', header: '코드', required: true, type: 'string' },
      { field: 'name', header: '코드명', required: true, type: 'string' },
      { field: 'sortOrder', header: '정렬', required: false, type: 'number' },
      { field: 'useYn', header: '사용여부', required: true, type: 'string' },
      { field: 'attr1', header: '속성1', required: false, type: 'string' },
      { field: 'attr2', header: '속성2', required: false, type: 'string' },
    ],
  },
  {
    datasetId: 'terms', name: '용어 사전', importable: true, params: [], columns: [
      { field: 'term', header: '용어', required: true, type: 'string' },
      { field: 'abbr', header: '영문 약어', required: false, type: 'string' },
      { field: 'domain', header: '도메인', required: false, type: 'string' },
      { field: 'definition', header: '정의', required: true, type: 'string' },
    ],
  },
  {
    datasetId: 'users', name: '사용자', importable: false, params: [], columns: [
      { field: 'loginId', header: '아이디', required: true, type: 'string' },
      { field: 'name', header: '이름', required: true, type: 'string' },
      { field: 'orgType', header: '소속', required: true, type: 'code', codeGroup: 'ORG_TYPE' },
      { field: 'authType', header: '인증', required: true, type: 'string' },
      { field: 'roles', header: '역할', required: false, type: 'string' },
    ],
  },
  {
    datasetId: 'audit-logs', name: '감사 로그', importable: false, params: ['from', 'to', 'action', 'q'], columns: [
      { field: 'at', header: '일시', required: true, type: 'date' },
      { field: 'loginId', header: '사용자', required: false, type: 'string' },
      { field: 'action', header: '행위', required: true, type: 'string' },
      { field: 'target', header: '대상', required: false, type: 'string' },
      { field: 'ip', header: 'IP', required: false, type: 'string' },
    ],
  },
  {
    datasetId: 'as-elements', name: '화면 컴포넌트 목록', importable: false, params: ['systemId'], columns: [
      { field: 'screenCode', header: '화면ID', required: true, type: 'string' },
      { field: 'screenName', header: '화면명', required: true, type: 'string' },
      { field: 'seqNo', header: '번호', required: true, type: 'number' },
      { field: 'elementCode', header: '요소ID', required: true, type: 'string' },
      { field: 'label', header: '라벨', required: false, type: 'string' },
      { field: 'type', header: '유형', required: true, type: 'code', codeGroup: 'ELEMENT_TYPE' },
      { field: 'handler', header: '이벤트 핸들러', required: false, type: 'string' },
    ],
  },
  {
    datasetId: 'as-screens', name: '화면 목록', importable: false, params: ['systemId'], columns: [
      { field: 'menuPath', header: '메뉴 경로', required: true, type: 'string' },
      { field: 'screenCode', header: '화면ID', required: true, type: 'string' },
      { field: 'name', header: '화면명', required: true, type: 'string' },
      { field: 'status', header: '분석 상태', required: true, type: 'code', codeGroup: 'ANALYSIS_STATUS' },
    ],
  },
]

export const MOCK_TEMPLATES: ExcelTemplate[] = [
  {
    templateId: 'tpl-001', name: '○○공사 화면정의서 양식', datasetId: 'as-elements', fileId: 'file-tpl-001',
    mapping: {
      sheet: '화면목록', startRow: 6,
      columns: { screenCode: 'B', screenName: 'C', seqNo: 'D', elementCode: 'E', label: 'F', type: 'G', handler: 'H' },
      cells: { projectName: 'C2', systemName: 'C3', exportedAt: 'H2', exportedBy: 'H3' },
    },
  },
]

export const MOCK_ATTACHMENTS: Record<string, FileMeta[]> = {
  'AS_SCREEN:scr-hr0101': [
    {
      fileId: 'file-a2', attachId: 'att-1', name: '사원관리_화면설계_검토의견.pdf', mime: 'application/pdf', size: 482_113, version: 2,
      uploadedAt: '2026-10-08T15:20:00+09:00', uploadedBy: { userId: 'u-analyst', name: '김분석' },
      versions: [
        { fileId: 'file-a2', version: 2, uploadedAt: '2026-10-08T15:20:00+09:00' },
        { fileId: 'file-a1', version: 1, uploadedAt: '2026-10-03T10:02:00+09:00' },
      ],
    },
    {
      fileId: 'file-b1', attachId: 'att-2', name: '현행_운영화면_캡처.svg', mime: 'image/svg+xml', size: 128_400, version: 1,
      uploadedAt: '2026-10-05T09:11:00+09:00', uploadedBy: { userId: 'u-admin', name: '관리자' },
      versions: [{ fileId: 'file-b1', version: 1, uploadedAt: '2026-10-05T09:11:00+09:00' }],
    },
  ],
}

export const MOCK_SYSTEM_INFO: SystemInfo = {
  version: '0.2.0-R0',
  buildTime: '2026-10-15T09:30:00+09:00',
  profile: 'mock',
  db: 'H2 2.3 (PostgreSQL mode)',
  javaVersion: '21.0.5',
  users: { total: 7, active: 6 },
  license: {
    customer: '○○공사', issuedAt: '2026-09-01T00:00:00+09:00', expiresAt: '2027-12-31T23:59:59+09:00',
    maxUsers: 50, features: ['ASIS', 'PJ', 'SM'], valid: true, reason: null,
  },
}

export const MOCK_LICENSE_STATUS: LicenseStatus = { valid: true, reason: null, expiresAt: '2027-12-31T23:59:59+09:00' }
