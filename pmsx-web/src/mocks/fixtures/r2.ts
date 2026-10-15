// R2 mock 픽스처 — docs/12 타입 그대로. 샘플 프로젝트 '○○공사 차세대 SI'(2026-09-01 ~ 2027-08-31)
import type { UserRef } from '@/types/api'
import type { ExcelDataset } from '@/types/common'
import type {
  AuditChecklistItem, Baseline, ChangeRequest, Decision, Defect, Deliverable, Issue, Meeting, Methodology, Notification,
  PortfolioRow, Requirement, Resource, Risk, TestRound, TestScenario, TraceLink, WbsItem, WeeklyReport,
} from '@/types/r2'

/** mock 의 '오늘' — 진척·지연 계산을 결정적으로 만든다 */
export const MOCK_TODAY = '2026-10-15'
export const PRJ = 'prj-si-01'

/* ── 사람 (12명) ─────────────────────────────────────────── */
export const PEOPLE: (UserRef & { orgType: string; company: string; grade: string; roleId: string; skills: [string, number][] })[] = [
  { userId: 'u-admin', name: '관리자', orgType: 'OWN', company: '○○정보', grade: '특급', roleId: 'PM', skills: [['PM', 5], ['Java', 3]] },
  { userId: 'u-pm01', name: '박관리', orgType: 'OWN', company: '○○정보', grade: '특급', roleId: 'PL', skills: [['PM', 4], ['Oracle', 4], ['Java', 4]] },
  { userId: 'u-pl', name: '이PL', orgType: 'OWN', company: '○○정보', grade: '고급', roleId: 'PL', skills: [['Java', 5], ['Spring', 5], ['Vue', 3]] },
  { userId: 'u-analyst', name: '김분석', orgType: 'OWN', company: '○○정보', grade: '고급', roleId: 'ANALYST', skills: [['업무분석', 5], ['Oracle', 3], ['Nexacro', 3]] },
  { userId: 'u-analyst2', name: '최분석', orgType: 'OWN', company: '○○정보', grade: '중급', roleId: 'ANALYST', skills: [['업무분석', 4], ['JSP', 3]] },
  { userId: 'u-dev01', name: '이개발', orgType: 'PARTNER', company: '△△소프트', grade: '중급', roleId: 'DEV', skills: [['Java', 4], ['Spring', 4], ['MyBatis', 4]] },
  { userId: 'u-dev02', name: '정개발', orgType: 'PARTNER', company: '△△소프트', grade: '중급', roleId: 'DEV', skills: [['Vue', 4], ['TypeScript', 4]] },
  { userId: 'u-dev03', name: '한개발', orgType: 'PARTNER', company: '□□시스템', grade: '초급', roleId: 'DEV', skills: [['Java', 3], ['Nexacro', 4]] },
  { userId: 'u-dba01', name: '윤데이터', orgType: 'PARTNER', company: '□□시스템', grade: '고급', roleId: 'DEV', skills: [['Oracle', 5], ['Tibero', 4], ['데이터이관', 5]] },
  { userId: 'u-qa01', name: '서품질', orgType: 'OWN', company: '○○정보', grade: '중급', roleId: 'QA', skills: [['테스트', 5], ['JMeter', 3]] },
  { userId: 'u-arch01', name: '강설계', orgType: 'OWN', company: '○○정보', grade: '특급', roleId: 'PL', skills: [['아키텍처', 5], ['Spring', 5], ['보안', 4]] },
  { userId: 'u-cust01', name: '최고객', orgType: 'CUSTOMER', company: '○○공사', grade: '-', roleId: 'CUSTOMER', skills: [] },
]
const u = (id: string): UserRef => {
  const p = PEOPLE.find((x) => x.userId === id)
  return { userId: id, name: p?.name ?? id }
}

export const RESOURCES: Resource[] = PEOPLE.map((p) => ({
  userId: p.userId, name: p.name, orgType: p.orgType, company: p.company, grade: p.grade,
  skills: p.skills.map(([skill, level]) => ({ skill, level })),
  currentProjects: [{ projectId: PRJ, name: '○○공사 차세대 SI', roleId: p.roleId }],
}))

/* ── 방법론 ──────────────────────────────────────────────── */
export const METHODOLOGIES: Methodology[] = [{
  methodologyId: 'mtd-si-std',
  name: 'SI 표준(분석·설계·구현·시험·이행)',
  phases: [
    { name: '분석', order: 1, tasks: [
      { name: '착수보고', weight: 1, durationDays: 3, deliverables: ['사업수행계획서'] },
      { name: '현행 시스템 분석', weight: 3, durationDays: 20, deliverables: ['현행시스템 분석서'] },
      { name: '요구사항 정의', weight: 3, durationDays: 15, deliverables: ['요구사항 정의서'] },
    ] },
    { name: '설계', order: 2, tasks: [
      { name: '화면 설계', weight: 3, durationDays: 25, deliverables: ['화면설계서'] },
      { name: 'DB 설계', weight: 2, durationDays: 20, deliverables: ['테이블 정의서', 'ERD'] },
      { name: '인터페이스 설계', weight: 1, durationDays: 10, deliverables: ['인터페이스 정의서'] },
    ] },
    { name: '구현', order: 3, tasks: [
      { name: '공통 모듈 개발', weight: 2, durationDays: 20, deliverables: ['소스코드'] },
      { name: '업무 개발', weight: 5, durationDays: 60, deliverables: ['프로그램 목록'] },
      { name: '단위 테스트', weight: 2, durationDays: 15, deliverables: ['단위테스트 결과서'] },
    ] },
    { name: '시험', order: 4, tasks: [
      { name: '통합 테스트', weight: 3, durationDays: 20, deliverables: ['통합테스트 결과서'] },
      { name: '인수 테스트', weight: 2, durationDays: 10, deliverables: ['인수테스트 결과서'] },
    ] },
    { name: '이행', order: 5, tasks: [
      { name: '데이터 이관', weight: 2, durationDays: 10, deliverables: ['이관 결과서'] },
      { name: '오픈·안정화', weight: 2, durationDays: 20, deliverables: ['완료보고서'] },
    ] },
  ],
}]

/* ── WBS (40행) ──────────────────────────────────────────── */
type W = [id: string, parent: string | null, name: string, start: string, end: string, opts?: Partial<WbsItem> & { who?: string[]; base?: [string, string] }]
const ROWS: W[] = [
  ['w1', null, '분석', '2026-09-01', '2026-10-30'],
  ['w1-1', 'w1', '착수보고', '2026-09-01', '2026-09-04', { weight: 1, progress: 100, status: 'DONE', who: ['u-admin'], milestone: false }],
  ['w1-2', 'w1', '현행 시스템 분석', '2026-09-07', '2026-10-23'],
  ['w1-2-1', 'w1-2', '인사시스템 As-Is 분석', '2026-09-07', '2026-10-09', { weight: 3, progress: 90, status: 'IN_PROGRESS', who: ['u-analyst'], base: ['2026-09-07', '2026-10-02'] }],
  ['w1-2-2', 'w1-2', '급여시스템 As-Is 분석', '2026-09-21', '2026-10-16', { weight: 2, progress: 60, status: 'IN_PROGRESS', who: ['u-analyst2'] }],
  ['w1-2-3', 'w1-2', '교육시스템 As-Is 분석', '2026-10-05', '2026-10-23', { weight: 1, progress: 20, status: 'IN_PROGRESS', who: ['u-analyst2'] }],
  ['w1-3', 'w1', '요구사항 정의', '2026-09-14', '2026-10-23'],
  ['w1-3-1', 'w1-3', '요구사항 수집·인터뷰', '2026-09-14', '2026-10-02', { weight: 2, progress: 100, status: 'DONE', who: ['u-pm01'] }],
  ['w1-3-2', 'w1-3', '요구사항 정의서 작성', '2026-10-05', '2026-10-23', { weight: 2, progress: 50, status: 'IN_PROGRESS', who: ['u-pm01', 'u-analyst'] }],
  ['w1-4', 'w1', '아키텍처 분석', '2026-09-21', '2026-10-16', { weight: 2, progress: 70, status: 'IN_PROGRESS', who: ['u-arch01'] }],
  ['w1-5', 'w1', '분석 단계 검토', '2026-10-30', '2026-10-30', { weight: 0, milestone: true, who: ['u-admin'] }],
  ['w2', null, '설계', '2026-11-02', '2027-01-29'],
  ['w2-1', 'w2', '화면 설계', '2026-11-02', '2026-12-18', { weight: 3, who: ['u-pl', 'u-dev02'] }],
  ['w2-2', 'w2', '프로그램 설계', '2026-11-16', '2027-01-08', { weight: 3, who: ['u-pl'] }],
  ['w2-3', 'w2', 'DB 설계', '2026-11-02', '2026-12-11', { weight: 2, who: ['u-dba01'] }],
  ['w2-4', 'w2', '인터페이스 설계', '2026-12-01', '2026-12-24', { weight: 1, who: ['u-arch01'] }],
  ['w2-5', 'w2', '데이터 이관 설계', '2026-12-07', '2027-01-15', { weight: 1, who: ['u-dba01'] }],
  ['w2-6', 'w2', '보안 설계', '2026-12-14', '2027-01-15', { weight: 1 }],
  ['w2-7', 'w2', '설계 검토', '2027-01-29', '2027-01-29', { weight: 0, milestone: true, who: ['u-admin'] }],
  ['w3', null, '구현', '2027-02-01', '2027-05-28'],
  ['w3-1', 'w3', '공통 모듈 개발', '2027-02-01', '2027-02-26', { weight: 2, who: ['u-pl'] }],
  ['w3-2', 'w3', '인사 업무 개발', '2027-03-02', '2027-04-30', { weight: 3, who: ['u-dev01'] }],
  ['w3-3', 'w3', '급여 업무 개발', '2027-03-02', '2027-05-07', { weight: 3, who: ['u-dev03'] }],
  ['w3-4', 'w3', '교육 업무 개발', '2027-03-15', '2027-05-07', { weight: 2, who: ['u-dev02'] }],
  ['w3-5', 'w3', '인터페이스 개발', '2027-03-15', '2027-04-23', { weight: 1 }],
  ['w3-6', 'w3', '단위 테스트', '2027-04-26', '2027-05-21', { weight: 2, who: ['u-qa01'] }],
  ['w3-7', 'w3', '구현 완료', '2027-05-28', '2027-05-28', { weight: 0, milestone: true }],
  ['w4', null, '시험', '2027-05-31', '2027-07-16'],
  ['w4-1', 'w4', '통합 테스트', '2027-05-31', '2027-06-25', { weight: 3, who: ['u-qa01'] }],
  ['w4-2', 'w4', '성능 테스트', '2027-06-14', '2027-06-30', { weight: 1, who: ['u-qa01'] }],
  ['w4-3', 'w4', '인수 테스트', '2027-07-01', '2027-07-14', { weight: 2, who: ['u-cust01'] }],
  ['w4-4', 'w4', '시험 완료', '2027-07-16', '2027-07-16', { weight: 0, milestone: true }],
  ['w5', null, '이행', '2027-07-19', '2027-08-31'],
  ['w5-1', 'w5', '데이터 이관', '2027-07-19', '2027-07-30', { weight: 2, who: ['u-dba01'] }],
  ['w5-2', 'w5', '이행 리허설', '2027-07-26', '2027-08-06', { weight: 1 }],
  ['w5-3', 'w5', '사용자 교육', '2027-08-02', '2027-08-13', { weight: 1, who: ['u-analyst'] }],
  ['w5-4', 'w5', '오픈', '2027-08-16', '2027-08-16', { weight: 0, milestone: true }],
  ['w5-5', 'w5', '안정화', '2027-08-16', '2027-08-27', { weight: 2 }],
  ['w5-6', 'w5', '종료보고', '2027-08-31', '2027-08-31', { weight: 1, who: ['u-admin'] }],
  ['w5-7', 'w5', '하자보수 인계', '2027-08-30', '2027-08-31', { weight: 1 }],
]

// 선후행 (FS) — 크리티컬 패스 표시용 체인 포함
const DEPS: [succ: string, pred: string][] = [
  ['w1-2-1', 'w1-1'], ['w1-3-2', 'w1-3-1'], ['w1-5', 'w1-2-3'], ['w1-5', 'w1-3-2'],
  ['w2-1', 'w1-5'], ['w2-2', 'w2-1'], ['w2-3', 'w1-5'], ['w2-5', 'w2-3'], ['w2-7', 'w2-2'],
  ['w3-1', 'w2-7'], ['w3-2', 'w3-1'], ['w3-3', 'w3-1'], ['w3-6', 'w3-2'], ['w3-7', 'w3-6'],
  ['w4-1', 'w3-7'], ['w4-3', 'w4-1'], ['w4-4', 'w4-3'], ['w5-1', 'w4-4'], ['w5-4', 'w5-1'], ['w5-6', 'w5-4'],
]
const CRITICAL = new Set(['w1-1', 'w1-2-1', 'w1-3-2', 'w1-5', 'w2-1', 'w2-2', 'w2-7', 'w3-1', 'w3-2', 'w3-6', 'w3-7', 'w4-1', 'w4-3', 'w4-4', 'w5-1', 'w5-4', 'w5-6'])

export const WBS: WbsItem[] = ROWS.map(([id, parent, name, start, end, opts = {}], idx) => {
  const { who, base, ...rest } = opts
  const progress = rest.progress ?? 0
  return {
    wbsId: id, parentId: parent, wbsNo: '', name, order: idx,
    planStart: start, planEnd: end,
    actStart: progress > 0 ? start : null, actEnd: progress === 100 ? end : null,
    weight: 1, progress, milestone: false, status: 'TODO', assignees: (who ?? []).map(u), deliverableId: null,
    deps: DEPS.filter(([s]) => s === id).map(([, p]) => ({ predId: p, type: 'FS' as const, lagDays: 0 })),
    critical: CRITICAL.has(id),
    baselineStart: base?.[0] ?? start, baselineEnd: base?.[1] ?? end,
    ...rest,
  }
})

export const BASELINES: Baseline[] = [
  { baselineId: 'bl-1', name: '사업수행계획 승인본', createdAt: '2026-09-10T10:00:00+09:00', createdBy: '관리자' },
]

/* ── 산출물 ──────────────────────────────────────────────── */
export const DELIVERABLES: Deliverable[] = [
  ['dl-01', '분석', '사업수행계획서', 'u-admin', '2026-09-04', 'APPROVED', true, 'w1-1', 2],
  ['dl-02', '분석', '현행시스템 분석서(인사)', 'u-analyst', '2026-10-09', 'REVIEW', true, 'w1-2-1', 1],
  ['dl-03', '분석', '현행시스템 분석서(급여)', 'u-analyst2', '2026-10-16', 'DRAFT', false, 'w1-2-2', 0],
  ['dl-04', '분석', '요구사항 정의서', 'u-pm01', '2026-10-23', 'DRAFT', true, 'w1-3-2', 1],
  ['dl-05', '분석', '아키텍처 정의서', 'u-arch01', '2026-10-16', 'REJECTED', false, 'w1-4', 1],
  ['dl-06', '설계', '화면설계서', 'u-pl', '2026-12-18', 'DRAFT', true, 'w2-1', 0],
  ['dl-07', '설계', '테이블 정의서', 'u-dba01', '2026-12-11', 'DRAFT', false, 'w2-3', 0],
  ['dl-08', '설계', '인터페이스 정의서', 'u-arch01', '2026-12-24', 'DRAFT', false, 'w2-4', 0],
  ['dl-09', '구현', '단위테스트 결과서', 'u-qa01', '2027-05-21', 'DRAFT', false, 'w3-6', 0],
  ['dl-10', '시험', '통합테스트 결과서', 'u-qa01', '2027-06-25', 'DRAFT', true, 'w4-1', 0],
  ['dl-11', '이행', '데이터 이관 결과서', 'u-dba01', '2027-07-30', 'DRAFT', true, 'w5-1', 0],
  ['dl-12', '이행', '완료보고서', 'u-admin', '2027-08-31', 'DRAFT', true, 'w5-6', 0],
].map(([deliverableId, phase, name, owner, dueDate, status, customerApproval, wbsId, fileCount]) => ({
  deliverableId: deliverableId as string, phase: phase as string, name: name as string, owner: u(owner as string), dueDate: dueDate as string,
  status: status as Deliverable['status'], customerApproval: customerApproval as boolean, wbsId: wbsId as string, fileCount: fileCount as number,
  updatedAt: '2026-10-14T16:00:00+09:00',
}))

/* ── 요구사항 (15건) ─────────────────────────────────────── */
const REQS: [string, string, string, Requirement['priority'], Requirement['status']][] = [
  ['사원 정보 등록·수정·삭제', '인사', '제안요청서 3.1', 'H', 'ACCEPTED'],
  ['사원 검색(이름·부서·입사일)', '인사', '제안요청서 3.1', 'M', 'IMPLEMENTED'],
  ['인사발령 처리 및 이력 관리', '인사', '인터뷰(인사팀)', 'H', 'ACCEPTED'],
  ['부서 조직도 관리', '인사', '제안요청서 3.2', 'M', 'VERIFIED'],
  ['근태 신청·승인', '인사', '인터뷰(총무팀)', 'M', 'PROPOSED'],
  ['월 급여 계산', '급여', '제안요청서 4.1', 'H', 'ACCEPTED'],
  ['급여명세서 출력·메일 발송', '급여', '제안요청서 4.2', 'M', 'ACCEPTED'],
  ['연말정산 자료 생성', '급여', '제안요청서 4.5', 'H', 'PROPOSED'],
  ['교육과정 등록·수강 신청', '교육', '제안요청서 5.1', 'M', 'IMPLEMENTED'],
  ['교육 이수 이력 조회', '교육', '인터뷰(교육팀)', 'L', 'ACCEPTED'],
  ['권한별 메뉴 접근 제어', '공통', '보안 요구사항', 'H', 'ACCEPTED'],
  ['개인정보 마스킹', '공통', '보안 요구사항', 'H', 'ACCEPTED'],
  ['엑셀 다운로드 이력 저장', '공통', '감사 요구사항', 'M', 'IMPLEMENTED'],
  ['그룹웨어 결재 연계', '연계', '제안요청서 6.1', 'M', 'PROPOSED'],
  ['구 시스템 데이터 이관', '이관', '제안요청서 7.1', 'H', 'ACCEPTED'],
]
export const REQUIREMENTS: Requirement[] = REQS.map(([title, category, source, priority, status], i) => ({
  reqId: `req-${i + 1}`, reqNo: `REQ-${String(i + 1).padStart(4, '0')}`, title, description: `${title}에 대한 요구사항입니다.`,
  source, category, priority, status, updatedAt: '2026-10-1' + (i % 5) + 'T10:00:00+09:00',
}))

/* ── 이슈·리스크·의사결정·CR ─────────────────────────────── */
export const ISSUES: Issue[] = [
  ['급여 테스트 DB 접근 권한 미확보', 'H', 'OPEN', 'u-pm01', '2026-10-10', 1, 'el-hr0101-btnSave'],
  ['현행 소스 일부(교육) 형상관리 누락', 'H', 'IN_PROGRESS', 'u-analyst2', '2026-10-20', 0, null],
  ['고객 인터뷰 일정 지연(재무팀)', 'M', 'OPEN', 'u-admin', '2026-10-17', 0, null],
  ['Nexacro 라이선스 버전 확인 필요', 'M', 'IN_PROGRESS', 'u-arch01', '2026-10-24', 0, null],
  ['개발 PC 보안 USB 정책 협의', 'L', 'RESOLVED', 'u-pl', '2026-10-08', 0, null],
  ['요구사항 범위 확대 요청(연말정산)', 'H', 'OPEN', 'u-admin', '2026-10-22', 2, null],
  ['분석 산출물 양식 고객 확인', 'M', 'CLOSED', 'u-pm01', '2026-09-30', 0, null],
  ['테스트 서버 디스크 부족', 'M', 'OPEN', 'u-dba01', '2026-10-30', 0, null],
  ['교육 시스템 담당자 부재', 'L', 'IN_PROGRESS', 'u-pm01', '2026-11-05', 0, null],
  ['데이터 이관 대상 범위 미정', 'H', 'OPEN', 'u-dba01', '2026-11-13', 0, null],
].map(([title, priority, status, owner, dueDate, escalation, elementId], i) => ({
  issueId: `iss-${i + 1}`, issueNo: `ISS-${String(i + 1).padStart(4, '0')}`, title: title as string,
  description: `${title as string} — 상세 내용과 영향 범위를 기록합니다.`, priority: priority as Issue['priority'], status: status as Issue['status'],
  owner: u(owner as string), dueDate: dueDate as string, escalation: escalation as Issue['escalation'], elementId: elementId as string | null,
  createdAt: '2026-09-2' + (i % 9) + 'T09:00:00+09:00', updatedAt: '2026-10-1' + (i % 5) + 'T14:00:00+09:00',
  comments: i === 0 ? [{ commentId: 'cm-1', content: 'DBA 팀에 계정 요청서 제출했습니다.', author: u('u-dba01'), createdAt: '2026-10-08T10:20:00+09:00' }] : [],
}))

export const RISKS: Risk[] = [
  ['핵심 분석 인력 이탈', 2, 5, '대체 인력 풀 확보, 분석 산출물 상시 공유', 'MITIGATING', 'u-admin'],
  ['현행 소스와 운영 소스 불일치', 4, 4, '운영 서버 소스 직접 반출 요청', 'IDENTIFIED', 'u-analyst'],
  ['연말정산 범위 추가로 일정 지연', 3, 5, 'CR 절차로 공수 재산정', 'IDENTIFIED', 'u-admin'],
  ['Nexacro 버전 호환성', 2, 3, '기술 검증(PoC) 선행', 'MITIGATING', 'u-arch01'],
  ['테스트 데이터 개인정보 노출', 2, 4, '마스킹 도구 적용', 'MITIGATING', 'u-dba01'],
  ['고객 의사결정 지연', 3, 3, '주간회의 의사결정 항목 별도 관리', 'IDENTIFIED', 'u-pm01'],
  ['협력사 인력 투입 지연', 4, 3, '대체 협력사 확보', 'OCCURRED', 'u-pm01'],
  ['성능 요구 미달', 1, 4, '설계 단계 성능 검토', 'CLOSED', 'u-arch01'],
].map(([title, p, i, response, status, owner], idx) => ({
  riskId: `rsk-${idx + 1}`, riskNo: `RSK-${String(idx + 1).padStart(4, '0')}`, title: title as string, description: `${title as string}에 대한 리스크입니다.`,
  probability: p as Risk['probability'], impact: i as Risk['impact'], score: (p as number) * (i as number), response: response as string,
  status: status as Risk['status'], owner: u(owner as string), dueDate: '2026-11-30',
}))

export const DECISIONS: Decision[] = [
  { decisionId: 'dec-1', title: '분석 산출물 양식 확정', content: '현행시스템 분석서는 PMS-X 화면정의서 출력으로 대체', rationale: '중복 작성 방지', decidedBy: '최고객', decidedAt: '2026-09-25' },
  { decisionId: 'dec-2', title: '개발 프레임워크 확정', content: '전자정부 표준프레임워크 4.x + Vue 3', rationale: '고객사 표준', decidedBy: '최고객', decidedAt: '2026-10-02' },
  { decisionId: 'dec-3', title: '주간보고 제출 요일', content: '매주 금요일 17시까지 제출', rationale: '고객 주간회의(월) 준비', decidedBy: '관리자', decidedAt: '2026-09-07' },
]

export const CHANGE_REQUESTS: ChangeRequest[] = [
  { crId: 'cr-1', crNo: 'CR-0001', title: '연말정산 자료 생성 기능 추가', reason: '세법 개정 대응', requester: '최고객', impact: { scope: '급여 화면 3본, 배치 1본 추가', mmDelta: 2.5, scheduleDeltaDays: 10 }, status: 'ANALYZED', reqIds: ['req-8'], wbsIds: ['w3-3'], updatedAt: '2026-10-14T11:00:00+09:00' },
  { crId: 'cr-2', crNo: 'CR-0002', title: '사원 검색 조건에 직급 추가', reason: '현업 요청', requester: '최고객', impact: { scope: '사원관리 화면 조회조건 1개', mmDelta: 0.1, scheduleDeltaDays: 0 }, status: 'APPROVED', reqIds: ['req-2'], wbsIds: ['w3-2'], updatedAt: '2026-10-08T15:00:00+09:00' },
  { crId: 'cr-3', crNo: 'CR-0003', title: '모바일 근태 신청', reason: '현업 편의', requester: '총무팀', impact: { scope: '모바일 웹 화면 2본', mmDelta: 1.2, scheduleDeltaDays: 5 }, status: 'REQUESTED', reqIds: ['req-5'], wbsIds: [], updatedAt: '2026-10-15T09:30:00+09:00' },
  { crId: 'cr-4', crNo: 'CR-0004', title: '부서 코드 체계 변경', reason: '조직 개편', requester: '인사팀', impact: { scope: '코드 이관 규칙 변경', mmDelta: 0.5, scheduleDeltaDays: 0 }, status: 'REJECTED', reqIds: [], wbsIds: [], updatedAt: '2026-10-01T10:00:00+09:00' },
]

/* ── 테스트 ──────────────────────────────────────────────── */
export const TEST_SCENARIOS: TestScenario[] = [
  { scenarioId: 'ts-1', level: 'UNIT', name: '인사 — 사원관리', cases: [
    ['사원 신규 등록(정상)', 'req-1', 'scr-hr0101', 'PASS'], ['필수값 누락 시 저장 차단', 'req-1', 'scr-hr0101', 'PASS'],
    ['미래 입사일 입력 차단', 'req-1', 'scr-hr0101', 'FAIL'], ['사원 검색(부서)', 'req-2', 'scr-hr0101', 'PASS'],
  ] },
  { scenarioId: 'ts-2', level: 'INTEGRATION', name: '인사 — 발령 처리', cases: [
    ['발령 등록 → 사원 소속 변경', 'req-3', 'scr-hr0103', 'BLOCK'], ['발령 이력 조회', 'req-3', 'scr-hr0103', null],
  ] },
  { scenarioId: 'ts-3', level: 'INTEGRATION', name: '급여 — 월 급여 계산', cases: [
    ['기본급·수당 계산', 'req-6', null, 'FAIL'], ['명세서 메일 발송', 'req-7', null, null], ['권한 없는 사용자 접근 차단', 'req-11', null, 'PASS'],
  ] },
].map((s, si) => ({
  scenarioId: s.scenarioId, level: s.level as TestScenario['level'], name: s.name,
  cases: (s.cases as [string, string, string | null, string | null][]).map(([title, req, screenId, last], ci) => ({
    caseId: `tc-${si + 1}-${ci + 1}`, tcNo: `TC-${String(si * 10 + ci + 1).padStart(4, '0')}`, title, precondition: '로그인 상태',
    steps: '1. 화면 진입\n2. 값 입력\n3. 저장', expected: '정상 처리 메시지', reqIds: [req], screenId, lastResult: last,
  })),
}))

export const TEST_ROUNDS: TestRound[] = [
  { roundId: 'tr-1', name: '단위테스트 1차', level: 'UNIT', createdAt: '2026-10-12T09:00:00+09:00' },
  { roundId: 'tr-2', name: '통합테스트 사전점검', level: 'INTEGRATION', createdAt: '2026-10-14T09:00:00+09:00' },
]

/* ── 결함 (14건) ─────────────────────────────────────────── */
const DEF: [string, Defect['severity'], Defect['status'], string | null, string | null, string | null, string][] = [
  ['미래 입사일 저장됨', 'MAJOR', 'ASSIGNED', 'u-dev01', 'scr-hr0101', 'el-hr0101-btnSave', '2026-10-06'],
  ['삭제 후 건수 미갱신', 'MINOR', 'FIXED', 'u-dev01', 'scr-hr0101', 'el-hr0101-btnDelete', '2026-10-07'],
  ['엑셀 다운로드 파일명 깨짐', 'TRIVIAL', 'CLOSED', 'u-dev02', 'scr-hr0101', null, '2026-10-02'],
  ['발령 저장 시 오류(ORA-01400)', 'CRITICAL', 'NEW', null, 'scr-hr0103', null, '2026-10-14'],
  ['부서 트리 정렬 오류', 'MINOR', 'VERIFIED', 'u-dev03', 'scr-hr0104', null, '2026-10-05'],
  ['급여 계산 소수점 반올림 오류', 'CRITICAL', 'ASSIGNED', 'u-dev03', null, null, '2026-10-13'],
  ['명세서 PDF 한글 깨짐', 'MAJOR', 'REOPENED', 'u-dev03', null, null, '2026-10-08'],
  ['권한 없는 메뉴 노출', 'MAJOR', 'FIXED', 'u-pl', null, null, '2026-10-09'],
  ['사원 검색 페이징 오류', 'MINOR', 'CLOSED', 'u-dev01', 'scr-hr0101', null, '2026-09-29'],
  ['근태 승인 알림 누락', 'MINOR', 'NEW', null, 'scr-hr0202', null, '2026-10-15'],
  ['교육 신청 중복 허용', 'MAJOR', 'ASSIGNED', 'u-dev02', null, null, '2026-10-12'],
  ['조회조건 초기화 안 됨', 'TRIVIAL', 'FIXED', 'u-dev01', 'scr-hr0101', 'el-hr0101-btnSearch', '2026-10-10'],
  ['세션 만료 후 저장 시 오류', 'MAJOR', 'VERIFIED', 'u-pl', null, null, '2026-10-03'],
  ['입사일 달력 주말 표시 오류', 'TRIVIAL', 'CLOSED', 'u-dev02', 'scr-hr0101', null, '2026-09-30'],
]
export const DEFECTS: Defect[] = DEF.map(([title, severity, status, assignee, screenId, elementId, created], i) => ({
  defectId: `def-${i + 1}`, defectNo: `DEF-${String(i + 1).padStart(4, '0')}`, title, description: `${title} — 재현 절차와 기대 결과를 적습니다.`,
  severity, priority: severity === 'CRITICAL' ? 'H' : severity === 'MAJOR' ? 'M' : 'L', status,
  assignee: assignee ? u(assignee) : null, reporter: u('u-qa01'), caseId: i === 0 ? 'tc-1-3' : null, screenId, elementId,
  createdAt: `${created}T10:00:00+09:00`, updatedAt: '2026-10-15T09:00:00+09:00',
}))

/* ── 회의록 ──────────────────────────────────────────────── */
export const MEETINGS: Meeting[] = [
  { meetingId: 'mtg-1', meetingNo: 'MTG-0001', title: '착수 회의', at: '2026-09-02T10:00:00+09:00', attendees: '최고객, 관리자, 박관리, 강설계',
    content: '## 안건\n- 사업 범위 확인\n- 일정·보고 체계\n\n## 결정\n- 주간보고는 금요일 제출', actionItems: [
      { itemId: 'ai-1', content: '사업수행계획서 제출', owner: u('u-admin'), dueDate: '2026-09-04', done: true },
    ] },
  { meetingId: 'mtg-2', meetingNo: 'MTG-0002', title: '분석 중간 점검', at: '2026-10-07T14:00:00+09:00', attendees: '최고객, 관리자, 김분석, 최분석',
    content: '## 안건\n- As-Is 분석 진척 공유\n- 급여 DB 접근 권한 이슈', actionItems: [
      { itemId: 'ai-2', content: '급여 테스트 DB 계정 발급 요청', owner: u('u-pm01'), dueDate: '2026-10-10', done: false },
      { itemId: 'ai-3', content: '교육 시스템 소스 반출 요청', owner: u('u-analyst2'), dueDate: '2026-10-16', done: false },
    ] },
  { meetingId: 'mtg-3', meetingNo: 'MTG-0003', title: '연말정산 범위 협의', at: '2026-10-14T16:00:00+09:00', attendees: '최고객, 관리자',
    content: '## 안건\n- 연말정산 기능 추가 요청\n\n## 결과\n- CR-0001 로 영향 분석 후 결정', actionItems: [
      { itemId: 'ai-4', content: 'CR-0001 영향 분석 보고', owner: u('u-admin'), dueDate: '2026-10-21', done: false },
    ] },
]

/* ── 추적 링크·감리 ──────────────────────────────────────── */
export const TRACE_LINKS: TraceLink[] = [
  ['REQ', 'req-1', 'REQ-0001 사원 정보 등록·수정·삭제', 'SCREEN', 'scr-hr0101', 'HR0101 사원관리'],
  ['REQ', 'req-1', 'REQ-0001 사원 정보 등록·수정·삭제', 'ELEMENT', 'el-hr0101-btnSave', '사원관리 › 저장'],
  ['REQ', 'req-2', 'REQ-0002 사원 검색(이름·부서·입사일)', 'SCREEN', 'scr-hr0101', 'HR0101 사원관리'],
  ['REQ', 'req-3', 'REQ-0003 인사발령 처리 및 이력 관리', 'SCREEN', 'scr-hr0103', 'HR0103 발령관리'],
  ['REQ', 'req-4', 'REQ-0004 부서 조직도 관리', 'SCREEN', 'scr-hr0104', 'HR0104 부서관리'],
  ['REQ', 'req-5', 'REQ-0005 근태 신청·승인', 'SCREEN', 'scr-hr0201', 'HR0201 근태신청'],
  ['REQ', 'req-8', 'REQ-0008 연말정산 자료 생성', 'CR', 'cr-1', 'CR-0001 연말정산 자료 생성 기능 추가'],
  ['REQ', 'req-2', 'REQ-0002 사원 검색(이름·부서·입사일)', 'CR', 'cr-2', 'CR-0002 사원 검색 조건에 직급 추가'],
  ['ELEMENT', 'el-hr0101-btnSave', '사원관리 › 저장', 'ISSUE', 'iss-1', 'ISS-0001 급여 테스트 DB 접근 권한 미확보'],
].map(([fromType, fromId, fromLabel, toType, toId, toLabel], i) => ({
  linkId: `lnk-${i + 1}`, fromType, fromId, fromLabel, toType, toId, toLabel, linkType: null,
} as TraceLink))

export const AUDIT_CHECKLIST: AuditChecklistItem[] = [
  ['분석', '사업수행계획서의 적정성', ['dl-01'], 'READY'],
  ['분석', '요구사항 정의의 완전성·추적성', ['dl-04'], 'NOT_READY'],
  ['분석', '현행 시스템 분석의 충분성', ['dl-02', 'dl-03'], 'NOT_READY'],
  ['분석', '아키텍처 정의의 적정성', ['dl-05'], 'NOT_READY'],
  ['설계', '화면 설계의 일관성', ['dl-06'], 'NOT_READY'],
  ['설계', 'DB 설계 정규화·무결성', ['dl-07'], 'NOT_READY'],
  ['설계', '인터페이스 정의', ['dl-08'], 'NA'],
  ['시험', '통합테스트 계획·결과', ['dl-10'], 'NOT_READY'],
  ['이행', '데이터 이관 검증', ['dl-11'], 'NOT_READY'],
].map(([phase, item, deliverableIds, status], i) => ({
  itemId: `ac-${i + 1}`, phase: phase as string, item: item as string, deliverableIds: deliverableIds as string[],
  status: status as AuditChecklistItem['status'], note: '',
}))

/* ── 알림·포트폴리오·주간보고 ────────────────────────────── */
export const NOTIFICATIONS: Notification[] = [
  ['DEFECT_ASSIGNED', '결함이 배정되었습니다', 'DEF-0001 미래 입사일 저장됨', '/quality/defects?id=def-1', false, '2026-10-15T09:10:00+09:00'],
  ['REVIEW_REQUESTED', '산출물 검토 요청', '현행시스템 분석서(인사) 검토를 요청했습니다.', '/deliverable/list', false, '2026-10-14T18:00:00+09:00'],
  ['ISSUE_ESCALATED', '이슈 에스컬레이션', 'ISS-0006 요구사항 범위 확대 요청(연말정산) — 2단계', '/quality/issues?id=iss-6', false, '2026-10-14T15:00:00+09:00'],
  ['OVERDUE', '기한 지연', 'ISS-0001 급여 테스트 DB 접근 권한 미확보 (기한 10/10)', '/quality/issues?id=iss-1', true, '2026-10-11T08:00:00+09:00'],
  ['ASSIGNED', '작업이 배정되었습니다', 'WBS 1.2.3 교육시스템 As-Is 분석', '/schedule/wbs', true, '2026-10-05T09:00:00+09:00'],
  ['DUE_SOON', '기한 임박', '산출물 현행시스템 분석서(급여) — 10/16', '/deliverable/list', true, '2026-10-14T08:00:00+09:00'],
  ['APPROVED', '산출물 승인', '사업수행계획서가 승인되었습니다.', '/deliverable/list', true, '2026-09-05T11:00:00+09:00'],
  ['TIMESHEET_REJECTED', '작업일지 반려', '10/5 주 작업일지가 반려되었습니다. (시간 재확인)', '/resource/timesheet', true, '2026-10-12T10:00:00+09:00'],
].map(([event, title, body, link, read, createdAt], i) => ({
  notificationId: `ntf-${i + 1}`, event, title, body, link, read, createdAt,
} as Notification))

export const PORTFOLIO: PortfolioRow[] = [
  { projectId: PRJ, name: '○○공사 차세대 SI', type: 'SI', customer: '○○공사', pm: '관리자', planned: 0, actual: 0, spi: null, openIssues: 0, criticalDefects: 0, health: 'GREEN' },
  { projectId: 'prj-sm-02', name: '△△청 통합유지보수', type: 'SM', customer: '△△청', pm: '박관리', planned: 75, actual: 74, spi: 0.99, openIssues: 2, criticalDefects: 0, health: 'GREEN' },
  { projectId: 'prj-si-03', name: '□□공단 포털 고도화', type: 'SI', customer: '□□공단', pm: '강설계', planned: 52, actual: 44, spi: 0.85, openIssues: 7, criticalDefects: 1, health: 'RED' },
]

export const WEEKLY_PREV: WeeklyReport = {
  reportId: 'wr-1', projectId: PRJ, weekStart: '2026-10-05', status: 'SUBMITTED',
  summary: '분석 단계 진행 중. 인사 As-Is 분석은 예정보다 1주 지연되었으나 다음 주 완료 예정입니다.',
  progress: { planned: 14.2, actual: 12.8, prevActual: 9.9 },
  thisWeek: [
    { wbsNo: '1.2.1', name: '인사시스템 As-Is 분석', assignees: '김분석', progress: 80, done: false, note: '' },
    { wbsNo: '1.3.1', name: '요구사항 수집·인터뷰', assignees: '박관리', progress: 100, done: true, note: '' },
  ],
  nextWeek: [{ wbsNo: '1.2.1', name: '인사시스템 As-Is 분석', assignees: '김분석', planEnd: '2026-10-09', note: '마무리' }],
  issues: [{ no: 'ISS-0001', title: '급여 테스트 DB 접근 권한 미확보', status: 'OPEN', owner: '박관리', dueDate: '2026-10-10' }],
  risks: [{ no: 'RSK-0002', title: '현행 소스와 운영 소스 불일치', score: 16, response: '운영 서버 소스 직접 반출 요청' }],
  mm: { planned: 2.5, actual: 2.3 },
  analysis: { done: 7, total: 21 },
}

/* ── 엑셀 데이터셋 (12 명세 §2·§4·§5·§6) ─────────────────── */
const col = (field: string, header: string, required = false, type: 'string' | 'number' | 'date' | 'code' = 'string') => ({ field, header, required, type })
export const R2_DATASETS: ExcelDataset[] = [
  { datasetId: 'wbs', name: 'WBS', importable: true, params: ['projectId'], columns: [col('wbsNo', 'WBS 번호', true), col('name', '작업명', true), col('planStart', '계획 시작', true, 'date'), col('planEnd', '계획 종료', true, 'date'), col('weight', '가중치', false, 'number'), col('assignees', '담당자')] },
  { datasetId: 'weekly-report', name: '주간보고', importable: false, params: ['reportId'], columns: [col('section', '구분'), col('wbsNo', 'WBS 번호'), col('name', '작업명'), col('note', '비고')] },
  { datasetId: 'mm-plan', name: '투입 계획(MM)', importable: true, params: ['projectId'], columns: [col('loginId', '아이디', true), col('name', '성명'), col('month', '월', true), col('mm', 'MM', true, 'number')] },
  { datasetId: 'mm-settlement', name: '협력사 MM 정산', importable: false, params: ['projectId', 'month'], columns: [col('company', '업체'), col('name', '성명'), col('grade', '등급'), col('mm', '실적 MM', false, 'number')] },
  { datasetId: 'requirements', name: '요구사항', importable: true, params: ['projectId'], columns: [col('reqNo', '요구사항 ID'), col('title', '요구사항명', true), col('category', '분류'), col('priority', '우선순위', true, 'code'), col('status', '상태', true, 'code'), col('source', '출처')] },
  { datasetId: 'trace-matrix', name: '추적성 매트릭스', importable: false, params: ['projectId'], columns: [col('reqNo', '요구사항 ID'), col('screens', '화면'), col('tests', '테스트'), col('defects', '결함'), col('gaps', '누락')] },
  { datasetId: 'deliverables', name: '산출물 대장', importable: false, params: ['projectId'], columns: [col('phase', '단계'), col('name', '산출물명'), col('owner', '담당'), col('status', '상태')] },
  { datasetId: 'issues', name: '이슈', importable: true, params: ['projectId'], columns: [col('title', '제목', true), col('priority', '우선순위', true, 'code'), col('owner', '담당'), col('dueDate', '기한', false, 'date')] },
  { datasetId: 'risks', name: '리스크', importable: true, params: ['projectId'], columns: [col('title', '제목', true), col('probability', '확률', true, 'number'), col('impact', '영향', true, 'number'), col('response', '대응')] },
  { datasetId: 'defects', name: '결함', importable: true, params: ['projectId'], columns: [col('title', '제목', true), col('severity', '심각도', true, 'code'), col('assignee', '담당'), col('status', '상태')] },
  { datasetId: 'test-cases', name: '테스트 케이스', importable: true, params: ['projectId'], columns: [col('scenario', '시나리오', true), col('title', '케이스명', true), col('steps', '절차'), col('expected', '기대 결과')] },
]
