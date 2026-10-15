// docs/11 (R1) mock 픽스처 — 분석 진척(화면 21개)·테이블/ERD(hr-legacy 규모 9개)·화면 이동·산출물 종류
import type {
  AnalysisHistoryItem, DeliverableType, ErdTable, MenuNode, NavigationMap, Note, Relation, TableColumn, UserRef,
} from '@/types/api'

const scr = (screenId: string, screenCode: string, status: 'AUTO' | 'WORKING' | 'REVIEW' | 'DONE', kind = 'page') =>
  ({ screenId, screenCode, kind, status })

const leaf = (menuId: string, name: string, order: number, screen: ReturnType<typeof scr>, children: MenuNode[] = []): MenuNode =>
  ({ menuId, name, order, screen, children })

/** R0 메뉴(7화면)를 진척 화면 확인용으로 21화면까지 늘린 인사시스템 메뉴 */
export const R1_HR_MENU: MenuNode[] = [
  {
    menuId: 'm-hr-01', name: '인사관리', order: 1, screen: null, children: [
      leaf('m-hr-0101', '사원관리', 1, scr('scr-hr0101', 'HR0101', 'REVIEW'), [
        leaf('m-hr-0101p01', '사원검색 (팝업)', 1, scr('scr-hr0101p01', 'HR0101P01', 'AUTO', 'popup')),
        leaf('m-hr-0102', '사원상세 (팝업)', 2, scr('scr-hr0102', 'HR0102', 'WORKING', 'popup')),
      ]),
      leaf('m-hr-0103', '발령관리', 2, scr('scr-hr0103', 'HR0103', 'DONE')),
      leaf('m-hr-0104', '부서관리', 3, scr('scr-hr0104', 'HR0104', 'DONE')),
      leaf('m-hr-0105', '교육이력관리', 4, scr('scr-hr0105', 'HR0105', 'WORKING')),
      leaf('m-hr-0106', '자격증관리', 5, scr('scr-hr0106', 'HR0106', 'AUTO')),
    ],
  },
  {
    menuId: 'm-hr-02', name: '근태관리', order: 2, screen: null, children: [
      leaf('m-hr-0201', '근태신청', 1, scr('scr-hr0201', 'HR0201', 'DONE')),
      leaf('m-hr-0202', '근태승인', 2, scr('scr-hr0202', 'HR0202', 'AUTO')),
      leaf('m-hr-0203', '휴가현황', 3, scr('scr-hr0203', 'HR0203', 'AUTO')),
      leaf('m-hr-0204', '초과근무관리', 4, scr('scr-hr0204', 'HR0204', 'WORKING')),
    ],
  },
  {
    menuId: 'm-hr-03', name: '급여관리', order: 3, screen: null, children: [
      leaf('m-hr-0301', '급여기준관리', 1, scr('scr-hr0301', 'HR0301', 'DONE')),
      leaf('m-hr-0302', '수당관리', 2, scr('scr-hr0302', 'HR0302', 'REVIEW')),
      leaf('m-hr-0303', '공제관리', 3, scr('scr-hr0303', 'HR0303', 'AUTO')),
      leaf('m-hr-0304', '급여명세조회', 4, scr('scr-hr0304', 'HR0304', 'AUTO'), [
        leaf('m-hr-0304p01', '명세 출력 (팝업)', 1, scr('scr-hr0304p01', 'HR0304P01', 'AUTO', 'popup')),
      ]),
    ],
  },
  {
    menuId: 'm-hr-04', name: '조직관리', order: 4, screen: null, children: [
      leaf('m-hr-0401', '조직도', 1, scr('scr-hr0401', 'HR0401', 'DONE')),
      leaf('m-hr-0402', '직위관리', 2, scr('scr-hr0402', 'HR0402', 'DONE')),
      leaf('m-hr-0403', '조직개편이력', 3, scr('scr-hr0403', 'HR0403', 'AUTO')),
    ],
  },
  {
    menuId: 'm-hr-09', name: '시스템관리', order: 9, screen: null, children: [
      leaf('m-hr-0901', '공통코드관리', 1, scr('scr-hr0901', 'HR0901', 'DONE')),
      leaf('m-hr-0902', '메뉴관리', 2, scr('scr-hr0902', 'HR0902', 'WORKING')),
    ],
  },
]

export const R1_USERS: Record<string, UserRef> = {
  analyst: { userId: 'u-analyst', name: '김분석' },
  analyst2: { userId: 'u-analyst2', name: '최분석' },
  dev: { userId: 'u-dev01', name: '이개발' },
  pl: { userId: 'u-pl', name: '이PL' },
}

/** 화면별 담당·검토 (없으면 미배정) — 진척 차트가 고르게 보이도록 배치 */
export const R1_OWNERS: Record<string, { owner: UserRef | null; reviewer: UserRef | null }> = {
  'scr-hr0101': { owner: R1_USERS.analyst, reviewer: R1_USERS.pl },
  'scr-hr0101p01': { owner: R1_USERS.analyst, reviewer: null },
  'scr-hr0102': { owner: R1_USERS.analyst, reviewer: null },
  'scr-hr0103': { owner: R1_USERS.analyst2, reviewer: R1_USERS.pl },
  'scr-hr0104': { owner: R1_USERS.analyst2, reviewer: R1_USERS.pl },
  'scr-hr0105': { owner: R1_USERS.dev, reviewer: null },
  'scr-hr0201': { owner: R1_USERS.analyst2, reviewer: R1_USERS.pl },
  'scr-hr0204': { owner: R1_USERS.dev, reviewer: null },
  'scr-hr0301': { owner: R1_USERS.analyst, reviewer: R1_USERS.pl },
  'scr-hr0302': { owner: R1_USERS.analyst, reviewer: R1_USERS.pl },
  'scr-hr0401': { owner: R1_USERS.analyst2, reviewer: R1_USERS.pl },
  'scr-hr0402': { owner: R1_USERS.analyst2, reviewer: R1_USERS.pl },
  'scr-hr0901': { owner: R1_USERS.dev, reviewer: R1_USERS.pl },
  'scr-hr0902': { owner: R1_USERS.dev, reviewer: null },
}

export const R1_HISTORY: Record<string, AnalysisHistoryItem[]> = {
  'scr-hr0101': [
    { at: '2026-10-05T14:20:00+09:00', user: R1_USERS.analyst, fromStatus: 'WORKING', toStatus: 'REVIEW', comment: '저장 버튼 업무 규칙(입사일 검증) 확인 부탁드립니다.' },
    { at: '2026-10-03T09:10:00+09:00', user: R1_USERS.analyst, fromStatus: 'AUTO', toStatus: 'WORKING', comment: null },
    { at: '2026-09-18T10:02:00+09:00', user: { userId: 'u-system', name: '수집기' }, fromStatus: null, toStatus: 'AUTO', comment: '1회차 자동수집' },
  ],
}

export const R1_NOTES: Note[] = [
  {
    noteId: 'note-1', targetType: 'ELEMENT', targetId: 'el-hr0101-btnSave',
    content: '**업무 규칙 오류** — 입사일이 미래 날짜여도 저장된다.\n\n- 현업 확인: 입사일은 *오늘 이전*만 허용\n- 관련 SR: `SR-2026-0142`\n\n```js\nif (row.hireDt > gfn_today()) return false; // 추가 필요\n```',
    author: R1_USERS.analyst, createdAt: '2026-10-05T14:10:00+09:00', updatedAt: '2026-10-05T14:10:00+09:00',
  },
  {
    noteId: 'note-2', targetType: 'SCREEN', targetId: 'scr-hr0101',
    content: '사원 기본정보 관리 화면. 퇴직자는 `USE_YN = \'N\'` 논리삭제로 처리한다.\n\n| 권한 | 가능 동작 |\n|---|---|\n| 인사담당 | 조회·저장·삭제 |\n| 일반 | 조회 |',
    author: R1_USERS.pl, createdAt: '2026-10-04T11:00:00+09:00', updatedAt: '2026-10-04T16:30:00+09:00',
  },
]

/* ── 테이블·관계 (hr-legacy 규모) ─────────────────────────── */
const col = (name: string, dataType: string, opt: Partial<TableColumn> = {}): TableColumn =>
  ({ name, dataType, nullable: true, pk: false, fk: false, default: null, comment: null, ...opt })

export interface MockTable {
  tableId: string
  schema: string
  name: string
  comment: string | null
  subjectArea: string | null
  columns: TableColumn[]
  indexes: { name: string; columns: string[]; unique: boolean }[]
}

const pk = (name: string, dataType: string, comment: string) => col(name, dataType, { pk: true, nullable: false, comment })

export const R1_TABLES: MockTable[] = [
  {
    tableId: 'nd-tbl-emp', schema: 'HR', name: 'TB_EMP', comment: '사원', subjectArea: '인사',
    columns: [
      pk('EMP_NO', 'VARCHAR2(10)', '사원번호'), col('EMP_NM', 'VARCHAR2(50)', { nullable: false, comment: '사원명' }),
      col('DEPT_CD', 'VARCHAR2(10)', { comment: '부서코드' }), col('POS_CD', 'VARCHAR2(5)', { comment: '직위코드' }),
      col('HIRE_DT', 'DATE', { comment: '입사일' }), col('RETIRE_DT', 'DATE', { comment: '퇴직일' }), col('EMAIL', 'VARCHAR2(100)'),
      col('PHONE', 'VARCHAR2(20)'), col('USE_YN', 'CHAR(1)', { default: "'Y'", nullable: false }),
      col('REG_DT', 'DATE', { default: 'SYSDATE' }), col('REG_ID', 'VARCHAR2(20)'), col('UPD_DT', 'DATE'), col('UPD_ID', 'VARCHAR2(20)'),
      col('BIRTH_DT', 'DATE', { comment: '생년월일(마스킹 대상)' }),
    ],
    indexes: [{ name: 'PK_TB_EMP', columns: ['EMP_NO'], unique: true }, { name: 'IX_TB_EMP_01', columns: ['DEPT_CD', 'USE_YN'], unique: false }],
  },
  {
    tableId: 'nd-tbl-dept', schema: 'HR', name: 'TB_DEPT', comment: '부서', subjectArea: '조직',
    columns: [pk('DEPT_CD', 'VARCHAR2(10)', '부서코드'), col('DEPT_NM', 'VARCHAR2(100)', { nullable: false }), col('UP_DEPT_CD', 'VARCHAR2(10)', { comment: '상위부서' }), col('SORT_ORD', 'NUMBER(5)'), col('USE_YN', 'CHAR(1)')],
    indexes: [{ name: 'PK_TB_DEPT', columns: ['DEPT_CD'], unique: true }],
  },
  {
    tableId: 'nd-tbl-emp-hist', schema: 'HR', name: 'TB_EMP_HIST', comment: '사원 변경 이력', subjectArea: '인사',
    columns: [pk('HIST_SEQ', 'NUMBER(12)', '이력순번'), col('EMP_NO', 'VARCHAR2(10)', { fk: true, nullable: false }), col('CHG_DT', 'DATE'), col('CHG_USER', 'VARCHAR2(20)'), col('BEFORE_JSON', 'CLOB')],
    indexes: [{ name: 'PK_TB_EMP_HIST', columns: ['HIST_SEQ'], unique: true }],
  },
  {
    tableId: 'nd-tbl-appt', schema: 'HR', name: 'TB_APPT', comment: '인사발령', subjectArea: '인사',
    columns: [pk('APPT_NO', 'VARCHAR2(12)', '발령번호'), col('EMP_NO', 'VARCHAR2(10)'), col('APPT_TYPE', 'VARCHAR2(5)'), col('FROM_DEPT_CD', 'VARCHAR2(10)'), col('TO_DEPT_CD', 'VARCHAR2(10)'), col('APPT_DT', 'DATE')],
    indexes: [{ name: 'PK_TB_APPT', columns: ['APPT_NO'], unique: true }],
  },
  {
    tableId: 'nd-tbl-attend', schema: 'HR', name: 'TB_ATTEND', comment: '근태', subjectArea: '근태',
    columns: [pk('ATT_NO', 'NUMBER(12)', '근태번호'), col('EMP_NO', 'VARCHAR2(10)'), col('ATT_TYPE', 'VARCHAR2(5)'), col('FROM_DT', 'DATE'), col('TO_DT', 'DATE'), col('APPR_YN', 'CHAR(1)'), col('APPR_EMP_NO', 'VARCHAR2(10)')],
    indexes: [{ name: 'PK_TB_ATTEND', columns: ['ATT_NO'], unique: true }],
  },
  {
    tableId: 'nd-tbl-edu', schema: 'HR', name: 'TB_EDU', comment: '교육과정', subjectArea: '교육',
    columns: [pk('EDU_CD', 'VARCHAR2(10)', '교육코드'), col('EDU_NM', 'VARCHAR2(100)'), col('EDU_HOURS', 'NUMBER(4)')],
    indexes: [{ name: 'PK_TB_EDU', columns: ['EDU_CD'], unique: true }],
  },
  {
    tableId: 'nd-tbl-edu-hist', schema: 'HR', name: 'TB_EDU_HIST', comment: '교육이력', subjectArea: '교육',
    columns: [pk('EDU_SEQ', 'NUMBER(12)', '순번'), col('EMP_NO', 'VARCHAR2(10)'), col('EDU_CD', 'VARCHAR2(10)', { fk: true }), col('COMPLETE_DT', 'DATE'), col('SCORE', 'NUMBER(5,2)')],
    indexes: [{ name: 'PK_TB_EDU_HIST', columns: ['EDU_SEQ'], unique: true }],
  },
  {
    tableId: 'nd-tbl-code', schema: 'HR', name: 'TB_CODE', comment: '공통코드', subjectArea: '공통',
    columns: [pk('GRP_CD', 'VARCHAR2(10)', '그룹코드'), pk('CD', 'VARCHAR2(10)', '코드'), col('CD_NM', 'VARCHAR2(100)'), col('SORT_ORD', 'NUMBER(5)'), col('USE_YN', 'CHAR(1)')],
    indexes: [{ name: 'PK_TB_CODE', columns: ['GRP_CD', 'CD'], unique: true }],
  },
  {
    tableId: 'nd-tbl-pay', schema: 'HR', name: 'TB_PAY', comment: '급여', subjectArea: '급여',
    columns: [pk('PAY_YM', 'CHAR(6)', '급여년월'), pk('EMP_NO', 'VARCHAR2(10)', '사원번호'), col('BASE_AMT', 'NUMBER(12)'), col('ALLOW_AMT', 'NUMBER(12)'), col('DEDUCT_AMT', 'NUMBER(12)'), col('NET_AMT', 'NUMBER(12)')],
    indexes: [{ name: 'PK_TB_PAY', columns: ['PAY_YM', 'EMP_NO'], unique: true }],
  },
]

const rel = (
  relationId: string, child: string, parent: string, columns: [string, string][], kind: Relation['kind'],
  evidenceCount: number | null, confidence: number | null, decision: Relation['decision'],
): Relation => {
  const name = (id: string) => R1_TABLES.find((x) => x.tableId === id)?.name ?? id
  return { relationId, childTableId: child, parentTableId: parent, childName: name(child), parentName: name(parent), columns, kind, evidenceCount, confidence, decision }
}

export const R1_RELATIONS: Relation[] = [
  rel('rel-1', 'nd-tbl-emp-hist', 'nd-tbl-emp', [['EMP_NO', 'EMP_NO']], 'fk', null, null, 'confirmed'),
  rel('rel-2', 'nd-tbl-emp', 'nd-tbl-dept', [['DEPT_CD', 'DEPT_CD']], 'inferred', 23, 0.95, 'confirmed'),
  rel('rel-3', 'nd-tbl-appt', 'nd-tbl-emp', [['EMP_NO', 'EMP_NO']], 'inferred', 9, 0.92, 'pending'),
  rel('rel-4', 'nd-tbl-appt', 'nd-tbl-dept', [['TO_DEPT_CD', 'DEPT_CD']], 'inferred', 6, 0.81, 'pending'),
  rel('rel-5', 'nd-tbl-attend', 'nd-tbl-emp', [['EMP_NO', 'EMP_NO']], 'inferred', 7, 0.9, 'pending'),
  rel('rel-6', 'nd-tbl-edu-hist', 'nd-tbl-emp', [['EMP_NO', 'EMP_NO']], 'inferred', 4, 0.86, 'pending'),
  rel('rel-7', 'nd-tbl-edu-hist', 'nd-tbl-edu', [['EDU_CD', 'EDU_CD']], 'fk', null, null, 'confirmed'),
  rel('rel-8', 'nd-tbl-dept', 'nd-tbl-dept', [['UP_DEPT_CD', 'DEPT_CD']], 'inferred', 3, 0.74, 'pending'),
  rel('rel-9', 'nd-tbl-emp', 'nd-tbl-code', [['POS_CD', 'CD']], 'inferred', 3, 0.58, 'rejected'),
  rel('rel-10', 'nd-tbl-pay', 'nd-tbl-emp', [['EMP_NO', 'EMP_NO']], 'inferred', 12, 0.94, 'pending'),
]

/** 테이블을 쓰는 다른 화면 (HR0101 은 워크플로우에서 계산) */
export const R1_TABLE_USAGE: Record<string, { screenId: string; screenName: string; crud: string; sqlName: string }[]> = {
  'nd-tbl-emp': [
    { screenId: 'scr-hr0103', screenName: '발령관리', crud: 'RU', sqlName: 'ApptMapper.updateEmpDept' },
    { screenId: 'scr-hr0105', screenName: '교육이력관리', crud: 'R', sqlName: 'EduMapper.selectEmpList' },
    { screenId: 'scr-hr0304', screenName: '급여명세조회', crud: 'R', sqlName: 'PayMapper.selectPaySlip' },
  ],
  'nd-tbl-dept': [{ screenId: 'scr-hr0104', screenName: '부서관리', crud: 'CRUD', sqlName: 'DeptMapper.saveDept' }, { screenId: 'scr-hr0401', screenName: '조직도', crud: 'R', sqlName: 'DeptMapper.selectTree' }],
  'nd-tbl-appt': [{ screenId: 'scr-hr0103', screenName: '발령관리', crud: 'CR', sqlName: 'ApptMapper.insertAppt' }],
  'nd-tbl-attend': [{ screenId: 'scr-hr0201', screenName: '근태신청', crud: 'CR', sqlName: 'AttendMapper.insertAttend' }, { screenId: 'scr-hr0202', screenName: '근태승인', crud: 'RU', sqlName: 'AttendMapper.approve' }],
  'nd-tbl-edu': [{ screenId: 'scr-hr0105', screenName: '교육이력관리', crud: 'R', sqlName: 'EduMapper.selectEduList' }],
  'nd-tbl-edu-hist': [{ screenId: 'scr-hr0105', screenName: '교육이력관리', crud: 'CRUD', sqlName: 'EduMapper.saveEduHist' }],
  'nd-tbl-code': [{ screenId: 'scr-hr0901', screenName: '공통코드관리', crud: 'CRUD', sqlName: 'CodeMapper.saveCode' }],
  'nd-tbl-pay': [{ screenId: 'scr-hr0301', screenName: '급여기준관리', crud: 'R', sqlName: 'PayMapper.selectBase' }, { screenId: 'scr-hr0304', screenName: '급여명세조회', crud: 'R', sqlName: 'PayMapper.selectPaySlip' }],
}

/** 화면별 CRUD (HR0101 외) — 시스템 CRUD 매트릭스용 */
export const R1_SCREEN_CRUD: Record<string, Record<string, string>> = {
  'scr-hr0101p01': { 'nd-tbl-emp': 'R', 'nd-tbl-dept': 'R' },
  'scr-hr0102': { 'nd-tbl-emp': 'RU', 'nd-tbl-emp-hist': 'R' },
  'scr-hr0103': { 'nd-tbl-appt': 'CR', 'nd-tbl-emp': 'RU', 'nd-tbl-dept': 'R' },
  'scr-hr0104': { 'nd-tbl-dept': 'CRUD' },
  'scr-hr0105': { 'nd-tbl-edu-hist': 'CRUD', 'nd-tbl-edu': 'R', 'nd-tbl-emp': 'R' },
  'scr-hr0106': { 'nd-tbl-emp': 'R', 'nd-tbl-code': 'R' },
  'scr-hr0201': { 'nd-tbl-attend': 'CR', 'nd-tbl-emp': 'R' },
  'scr-hr0202': { 'nd-tbl-attend': 'RU' },
  'scr-hr0203': { 'nd-tbl-attend': 'R', 'nd-tbl-emp': 'R' },
  'scr-hr0204': { 'nd-tbl-attend': 'CRU' },
  'scr-hr0301': { 'nd-tbl-pay': 'R', 'nd-tbl-code': 'RU' },
  'scr-hr0302': { 'nd-tbl-pay': 'U', 'nd-tbl-code': 'R' },
  'scr-hr0303': { 'nd-tbl-pay': 'U' },
  'scr-hr0304': { 'nd-tbl-pay': 'R', 'nd-tbl-emp': 'R' },
  'scr-hr0304p01': { 'nd-tbl-pay': 'R' },
  'scr-hr0401': { 'nd-tbl-dept': 'R', 'nd-tbl-emp': 'R' },
  'scr-hr0402': { 'nd-tbl-code': 'CRUD' },
  'scr-hr0403': { 'nd-tbl-dept': 'R' },
  'scr-hr0901': { 'nd-tbl-code': 'CRUD' },
  'scr-hr0902': {},
}

export const R1_NAV_LINKS: NavigationMap['links'] = [
  { fromScreenId: 'scr-hr0101', toScreenId: 'scr-hr0101p01', via: '사원찾기', kind: 'popup' },
  { fromScreenId: 'scr-hr0101', toScreenId: 'scr-hr0102', via: '그리드 더블클릭', kind: 'popup' },
  { fromScreenId: 'scr-hr0103', toScreenId: 'scr-hr0101p01', via: '대상자 검색', kind: 'popup' },
  { fromScreenId: 'scr-hr0105', toScreenId: 'scr-hr0101p01', via: '사원찾기', kind: 'popup' },
  { fromScreenId: 'scr-hr0202', toScreenId: 'scr-hr0201', via: '신청 상세', kind: 'link' },
  { fromScreenId: 'scr-hr0302', toScreenId: 'scr-hr0301', via: '기준 보기', kind: 'link' },
  { fromScreenId: 'scr-hr0304', toScreenId: 'scr-hr0304p01', via: '명세 출력', kind: 'popup' },
  { fromScreenId: 'scr-hr0401', toScreenId: 'scr-hr0402', via: '직위 탭', kind: 'tab' },
  { fromScreenId: 'scr-hr0401', toScreenId: 'scr-hr0403', via: '개편 이력', kind: 'link' },
  { fromScreenId: 'scr-hr0104', toScreenId: 'scr-hr0401', via: '조직도 보기', kind: 'link' },
]

export const R1_DELIVERABLE_TYPES: DeliverableType[] = [
  { type: 'screen-spec', name: '화면정의서', formats: ['xlsx', 'pptx'] },
  { type: 'program-spec', name: '프로그램 사양서', formats: ['xlsx'] },
  { type: 'table-spec', name: '테이블 정의서', formats: ['xlsx'] },
  { type: 'crud-matrix', name: 'CRUD 매트릭스', formats: ['xlsx'] },
  { type: 'interface-list', name: '인터페이스 목록', formats: ['xlsx'] },
]

export const toErdTable = (t: MockTable): ErdTable => ({
  tableId: t.tableId, name: t.name, comment: t.comment, subjectArea: t.subjectArea,
  columns: t.columns.map((c) => ({ name: c.name, dataType: c.dataType, pk: c.pk, fk: c.fk })),
})
