// R3 mock 픽스처 — SM 프로젝트(prj-sm-01) 의 SR·SLA·장애·문제·변경·정기 작업·운영 자산·KB·월간 보고·영향도
import type { ExcelDataset } from '@/types/common'
import type {
  Change, ImpactResult, Incident, KbArticle, MonthlyReport, OpsAsset, Problem, RoutineJob, SlaPolicy, SrPriority, SrStatus, SrType,
} from '@/types/r3'

export const SM_PRJ = 'prj-sm-01'
export const SM_SYS = 'sys-hr-op'

export const SM_PEOPLE = {
  op: { userId: 'u-op01', name: '정운영' },
  op2: { userId: 'u-dev01', name: '이개발' },
  pl: { userId: 'u-pl', name: '이PL' },
  admin: { userId: 'u-admin', name: '관리자' },
  cust: { userId: 'u-cust01', name: '최고객' },
}

export const SLA_POLICIES: SlaPolicy[] = [
  { policyId: 'sla-p1', name: 'P1 장애 (24×7)', types: ['INCIDENT'], priorities: ['P1'], responseMinutes: 30, resolveMinutes: 240, businessHours: { start: '00:00', end: '23:59', days: [0, 1, 2, 3, 4, 5, 6] }, twentyFourSeven: true, order: 1 },
  { policyId: 'sla-p2', name: 'P2 높음', types: [], priorities: ['P2'], responseMinutes: 60, resolveMinutes: 540, businessHours: { start: '09:00', end: '18:00', days: [1, 2, 3, 4, 5] }, twentyFourSeven: false, order: 2 },
  { policyId: 'sla-p3', name: 'P3 보통', types: [], priorities: ['P3'], responseMinutes: 240, resolveMinutes: 1620, businessHours: { start: '09:00', end: '18:00', days: [1, 2, 3, 4, 5] }, twentyFourSeven: false, order: 3 },
  { policyId: 'sla-p4', name: 'P4 낮음', types: [], priorities: ['P4'], responseMinutes: 540, resolveMinutes: 2700, businessHours: { start: '09:00', end: '18:00', days: [1, 2, 3, 4, 5] }, twentyFourSeven: false, order: 4 },
]

/** SR 시드 — [번호, 유형, 우선순위, 제목, 상태, 담당 키, 화면ID, 요소ID, 남은 분(해결 기한까지, 음수=초과), 생성 n분 전, 채널, 고객 요청 여부] */
type SrSeed = [number, SrType, SrPriority, string, SrStatus, keyof typeof SM_PEOPLE | null, string | null, string | null, number, number, 'PORTAL' | 'EMAIL' | 'MANUAL', boolean]
export const SR_SEEDS: SrSeed[] = [
  [1, 'INCIDENT', 'P1', '사원관리 저장 시 500 오류 발생', 'IN_PROGRESS', 'op', 'scr-hr0101', 'el-hr0101-btnSave', 25, 215, 'PORTAL', true],
  [2, 'IMPROVE', 'P3', '사원관리 엑셀 다운로드에 입사일 컬럼 추가', 'ASSIGNED', 'op', 'scr-hr0101', 'el-hr0101-btnExcel', 1210, 300, 'PORTAL', true],
  [3, 'DATA', 'P2', '2026년 3분기 퇴직자 명단 추출 요청', 'IN_PROGRESS', 'op2', 'scr-hr0103', null, 48, 480, 'EMAIL', false],
  [4, 'INQUIRY', 'P4', '근태승인 화면 승인 버튼 비활성 사유 문의', 'RECEIVED', null, 'scr-hr0202', null, 2600, 60, 'PORTAL', true],
  [5, 'AUTH', 'P3', '신규 입사자 인사시스템 권한 부여', 'CLASSIFIED', null, null, null, 1500, 90, 'MANUAL', false],
  [6, 'INCIDENT', 'P2', '급여명세조회 화면 조회 지연(30초 이상)', 'IN_PROGRESS', 'op', 'scr-hr0304', null, -45, 600, 'PORTAL', true],
  [7, 'IMPROVE', 'P3', '부서관리 화면 정렬 순서 변경', 'ON_HOLD', 'op2', 'scr-hr0104', null, 900, 1800, 'MANUAL', false],
  [8, 'DATA', 'P3', '교육이력 일괄 보정 (2025년 하반기)', 'RESOLVED', 'op', 'scr-hr0105', null, 300, 2400, 'EMAIL', false],
  [9, 'INQUIRY', 'P3', '발령관리 저장 후 사원 부서가 안 바뀜', 'RESOLVED', 'op', 'scr-hr0103', null, 200, 2000, 'PORTAL', true],
  [10, 'INCIDENT', 'P2', '사원관리 저장 버튼 중복 클릭 시 이력 2건 생성', 'CLOSED', 'op', 'scr-hr0101', 'el-hr0101-btnSave', 120, 9000, 'PORTAL', true],
  [11, 'IMPROVE', 'P4', '자격증관리 화면 첨부파일 미리보기', 'ASSIGNED', 'op2', 'scr-hr0106', null, 2400, 400, 'MANUAL', false],
  [12, 'INCIDENT', 'P2', '사원관리 저장 시 미래 입사일 입력 가능', 'IN_PROGRESS', 'op', 'scr-hr0101', 'el-hr0101-btnSave', 70, 420, 'PORTAL', true],
  [13, 'DATA', 'P2', '부서별 인원 현황 추출', 'CLOSED', 'op2', null, null, 30, 12000, 'EMAIL', false],
  [14, 'AUTH', 'P2', '퇴직자 계정 일괄 잠금', 'CONFIRMED', 'op', null, null, 15, 6000, 'MANUAL', false],
  [15, 'INQUIRY', 'P4', '초과근무관리 집계 기준 문의', 'CLOSED', 'op', 'scr-hr0204', null, 800, 15000, 'PORTAL', true],
  [16, 'INCIDENT', 'P1', '인사시스템 로그인 불가 (SSO 연동 오류)', 'CLOSED', 'op', null, null, 40, 20000, 'EMAIL', false],
  [17, 'IMPROVE', 'P3', '공통코드관리 화면 검색 조건 추가', 'IN_PROGRESS', 'op2', 'scr-hr0901', null, 520, 1100, 'MANUAL', false],
  [18, 'DATA', 'P3', '연말정산용 부양가족 데이터 추출', 'ASSIGNED', 'op', null, null, 1400, 200, 'EMAIL', false],
  [19, 'INQUIRY', 'P3', '휴가현황 잔여일수 계산 기준', 'REJECTED', 'op', 'scr-hr0203', null, 1000, 7000, 'PORTAL', true],
  [20, 'INCIDENT', 'P2', '사원관리 저장 후 재조회 안 됨', 'CLOSED', 'op', 'scr-hr0101', 'el-hr0101-btnSave', 90, 18000, 'PORTAL', true],
  [21, 'IMPROVE', 'P3', '조직도 화면 인쇄 기능', 'RECEIVED', null, 'scr-hr0401', null, 1580, 40, 'PORTAL', true],
  [22, 'DATA', 'P4', '직위별 평균 근속 통계', 'RESOLVED', 'op2', null, null, -120, 5000, 'EMAIL', false],
  [23, 'INCIDENT', 'P3', '근태신청 첨부 업로드 실패(10MB 이상)', 'IN_PROGRESS', 'op', 'scr-hr0201', null, 260, 1300, 'PORTAL', true],
  [24, 'AUTH', 'P3', '급여 담당자 권한 추가', 'ASSIGNED', 'op2', null, null, 1100, 500, 'MANUAL', false],
  [25, 'IMPROVE', 'P2', '사원관리 조회 조건에 직위 추가', 'CLOSED', 'op', 'scr-hr0101', 'el-hr0101-btnSearch', 10, 25000, 'PORTAL', true],
  [26, 'INQUIRY', 'P3', '수당관리 화면 계산식 확인', 'ON_HOLD', 'op', 'scr-hr0302', null, 700, 2600, 'EMAIL', false],
  [27, 'INCIDENT', 'P2', '메뉴관리 저장 시 권한 캐시 미갱신', 'CLOSED', 'op2', 'scr-hr0902', null, 60, 30000, 'MANUAL', false],
  [28, 'DATA', 'P3', '사원 사진 일괄 등록', 'IN_PROGRESS', 'op2', 'scr-hr0101', null, 15, 1600, 'EMAIL', false],
  [29, 'IMPROVE', 'P4', '공제관리 엑셀 업로드 양식 변경', 'CLASSIFIED', null, 'scr-hr0303', null, 2650, 120, 'PORTAL', true],
  [30, 'INCIDENT', 'P2', '사원관리 화면 사원검색 팝업 닫힘 오류', 'RESOLVED', 'op', 'scr-hr0101p01', null, 180, 900, 'PORTAL', true],
]

export const SCREEN_NAMES: Record<string, string> = {
  'scr-hr0101': '사원관리', 'scr-hr0101p01': '사원검색 (팝업)', 'scr-hr0103': '발령관리', 'scr-hr0104': '부서관리', 'scr-hr0105': '교육이력관리',
  'scr-hr0106': '자격증관리', 'scr-hr0201': '근태신청', 'scr-hr0202': '근태승인', 'scr-hr0203': '휴가현황', 'scr-hr0204': '초과근무관리',
  'scr-hr0302': '수당관리', 'scr-hr0303': '공제관리', 'scr-hr0304': '급여명세조회', 'scr-hr0401': '조직도', 'scr-hr0901': '공통코드관리', 'scr-hr0902': '메뉴관리',
}

export const INCIDENTS: Incident[] = [
  {
    incidentId: 'inc-1', incidentNo: 'INC-0001', title: '인사시스템 로그인 불가 (SSO 연동 오류)', grade: 1, status: 'CLOSED',
    startedAt: '2026-10-01T08:42:00+09:00', detectedAt: '2026-10-01T08:50:00+09:00', recoveredAt: '2026-10-01T10:05:00+09:00',
    impact: '전 직원 인사시스템 로그인 불가 (약 1,200명)', systemIds: [SM_SYS],
    timeline: [
      { at: '2026-10-01T08:42:00+09:00', content: '모니터링 로그인 실패율 급증' },
      { at: '2026-10-01T08:50:00+09:00', content: '고객 담당자 전화로 장애 인지, 1등급 선언' },
      { at: '2026-10-01T09:20:00+09:00', content: 'SSO 인증서 갱신 누락 확인' },
      { at: '2026-10-01T10:05:00+09:00', content: '인증서 교체 후 정상화 확인' },
    ],
    cause: 'SSO 연동 인증서 만료', rca: '인증서 만료일 관리 대장 부재, 갱신 담당자 미지정', prevention: '운영 자산 대장에 인증서 등록 및 만료 30·7·1일 전 알림 설정',
    srIds: ['sr-16'], durationMinutes: 83,
  },
  {
    incidentId: 'inc-2', incidentNo: 'INC-0002', title: '급여명세조회 응답 지연', grade: 3, status: 'RECOVERED',
    startedAt: '2026-10-14T09:10:00+09:00', detectedAt: '2026-10-14T09:30:00+09:00', recoveredAt: '2026-10-14T11:00:00+09:00',
    impact: '급여명세조회 화면 30초 이상 지연, 급여 담당자 업무 지연', systemIds: [SM_SYS],
    timeline: [
      { at: '2026-10-14T09:30:00+09:00', content: 'SR-0006 접수로 인지' },
      { at: '2026-10-14T10:20:00+09:00', content: 'TB_PAY 인덱스 통계 갱신 누락 확인' },
      { at: '2026-10-14T11:00:00+09:00', content: '통계 갱신 후 2초 이내로 회복' },
    ],
    cause: '월말 대량 적재 후 통계 미갱신', rca: null, prevention: null, srIds: ['sr-6'], durationMinutes: 110,
  },
  {
    incidentId: 'inc-3', incidentNo: 'INC-0003', title: '사원관리 저장 오류(500)', grade: 2, status: 'OPEN',
    startedAt: new Date(Date.now() - 215 * 60_000).toISOString(), detectedAt: new Date(Date.now() - 200 * 60_000).toISOString(), recoveredAt: null,
    impact: '사원 정보 수정 불가 (인사팀)', systemIds: [SM_SYS],
    timeline: [{ at: new Date(Date.now() - 200 * 60_000).toISOString(), content: '고객 포털 SR-0001 로 인지' }],
    cause: null, rca: null, prevention: null, srIds: ['sr-1'], durationMinutes: null,
  },
]

export const PROBLEMS: Problem[] = [
  {
    problemId: 'prb-1', problemNo: 'PRB-0001', title: '[반복] 사원관리 저장 처리 오류', rootCause: 'fn_save() 중복 호출 방지 로직 부재, 서버 트랜잭션 경계 불명확',
    workaround: '저장 후 재조회 전까지 버튼 비활성화 안내', knownError: true, status: 'ANALYZING', srIds: ['sr-10', 'sr-20'], incidentIds: [],
  },
  { problemId: 'prb-2', problemNo: 'PRB-0002', title: '월말 배치 후 조회 성능 저하', rootCause: null, workaround: '배치 후 통계 수동 갱신', knownError: false, status: 'OPEN', srIds: ['sr-6'], incidentIds: ['inc-2'] },
]

export const CHANGES: Change[] = [
  {
    changeId: 'chg-1', changeNo: 'CHG-0001', title: '사원관리 저장 중복 방지 패치', type: 'NORMAL', risk: 'M',
    plan: '1) HR0101.js fn_save 버튼 잠금 추가\n2) EmpServiceImpl.saveEmp 중복 키 검사', rollbackPlan: '이전 war 재배포 (10분)',
    scheduledAt: '2026-10-17T19:00:00+09:00', deployedAt: null, status: 'REQUESTED', srIds: ['sr-12'], screenIds: ['scr-hr0101'],
    cab: [{ userId: 'u-pl', name: '이PL', decision: 'APPROVE', comment: '영향 범위 확인함', at: '2026-10-15T10:00:00+09:00' }], commits: ['a1b2c3d fn_save 잠금'],
  },
  {
    changeId: 'chg-2', changeNo: 'CHG-0002', title: 'SSO 인증서 교체', type: 'EMERGENCY', risk: 'H',
    plan: '인증서 교체 및 WAS 재기동', rollbackPlan: '기존 인증서 복원', scheduledAt: '2026-10-01T09:30:00+09:00', deployedAt: '2026-10-01T10:00:00+09:00',
    status: 'CLOSED', srIds: ['sr-16'], screenIds: [], cab: [{ userId: 'u-admin', name: '관리자', decision: 'APPROVE', comment: '긴급 승인', at: '2026-10-01T09:25:00+09:00' }], commits: [],
  },
  {
    changeId: 'chg-3', changeNo: 'CHG-0003', title: '사원관리 조회 조건 직위 추가', type: 'NORMAL', risk: 'L',
    plan: 'HR0101.jsp 조회 조건 추가, EmpMapper.selectEmpList 조건 추가', rollbackPlan: '이전 버전 재배포', scheduledAt: '2026-10-08T19:00:00+09:00', deployedAt: '2026-10-08T19:20:00+09:00',
    status: 'DEPLOYED', srIds: ['sr-25'], screenIds: ['scr-hr0101'], cab: [{ userId: 'u-pl', name: '이PL', decision: 'APPROVE', comment: null, at: '2026-10-06T14:00:00+09:00' }], commits: ['9f8e7d6 직위 조건'],
  },
  {
    changeId: 'chg-4', changeNo: 'CHG-0004', title: '급여 테이블 통계 자동 갱신 배치 추가', type: 'STANDARD', risk: 'M',
    plan: '월말 배치 후 DBMS_STATS 수행 단계 추가', rollbackPlan: '배치 단계 비활성화', scheduledAt: '2026-10-24T20:00:00+09:00', deployedAt: null,
    status: 'CAB_APPROVED', srIds: ['sr-6'], screenIds: ['scr-hr0304'], cab: [{ userId: 'u-pl', name: '이PL', decision: 'APPROVE', comment: null, at: '2026-10-15T09:00:00+09:00' }, { userId: 'u-cust01', name: '최고객', decision: 'APPROVE', comment: '월말 전 적용 요청', at: '2026-10-15T09:30:00+09:00' }], commits: [],
  },
  {
    changeId: 'chg-5', changeNo: 'CHG-0005', title: '메뉴 권한 캐시 갱신 로직 수정', type: 'NORMAL', risk: 'M',
    plan: '메뉴 저장 시 캐시 무효화 호출', rollbackPlan: '이전 버전 재배포', scheduledAt: '2026-09-25T19:00:00+09:00', deployedAt: '2026-09-25T19:40:00+09:00',
    status: 'ROLLED_BACK', srIds: ['sr-27'], screenIds: ['scr-hr0902'], cab: [{ userId: 'u-pl', name: '이PL', decision: 'APPROVE', comment: null, at: '2026-09-24T10:00:00+09:00' }], commits: ['5c4b3a2 캐시 무효화'],
  },
]

export const ROUTINE_JOBS: RoutineJob[] = [
  { jobId: 'rj-1', name: '일일 백업 결과 확인', rule: { freq: 'DAILY' }, owner: SM_PEOPLE.op, checklist: ['DB 백업 로그 성공 여부', '백업 파일 크기 확인'] },
  { jobId: 'rj-2', name: '주간 서버 점검', rule: { freq: 'WEEKLY', dow: [1] }, owner: SM_PEOPLE.op2, checklist: ['CPU·메모리·디스크 사용률', 'WAS 로그 오류 건수'] },
  { jobId: 'rj-3', name: '월마감 급여 배치 모니터링', rule: { freq: 'MONTHLY', day: 25 }, owner: SM_PEOPLE.op, checklist: ['급여 계산 배치 완료', '통계 갱신 수행'] },
  { jobId: 'rj-4', name: '월간 계정 정리', rule: { freq: 'MONTHLY', day: 31 }, owner: SM_PEOPLE.op2, checklist: ['퇴직자 계정 잠금', '장기 미사용 계정 점검'] },
]

const iso = (daysFromNow: number) => {
  const d = new Date(Date.now() + daysFromNow * 86_400_000)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
export const OPS_ASSETS: Omit<OpsAsset, 'daysLeft'>[] = [
  { assetId: 'oa-1', type: 'SERVER', name: 'HR-WAS-01', host: '10.10.1.21', owner: '정운영', expiresAt: null, note: 'WAS (JEUS 8)' },
  { assetId: 'oa-2', type: 'SERVER', name: 'HR-DB-01', host: '10.10.1.31', owner: '이개발', expiresAt: null, note: 'Oracle 19c' },
  { assetId: 'oa-3', type: 'CERT', name: 'hr.example.go.kr SSL', host: 'hr.example.go.kr', owner: '정운영', expiresAt: iso(5), note: '갱신 요청 진행 중' },
  { assetId: 'oa-4', type: 'CERT', name: 'SSO 연동 인증서', host: 'sso.example.go.kr', owner: '정운영', expiresAt: iso(350), note: '10/01 교체' },
  { assetId: 'oa-5', type: 'LICENSE', name: 'JEUS 유지보수 라이선스', host: null, owner: '관리자', expiresAt: iso(24), note: null },
  { assetId: 'oa-6', type: 'LICENSE', name: '리포팅 툴 라이선스', host: null, owner: '관리자', expiresAt: iso(-3), note: '갱신 계약 지연' },
  { assetId: 'oa-7', type: 'ACCOUNT', name: 'DB 운영 계정 hr_app', host: 'HR-DB-01', owner: '이개발', expiresAt: iso(60), note: '비밀번호 90일 주기' },
  { assetId: 'oa-8', type: 'ACCOUNT', name: '배치 서버 OS 계정', host: 'HR-BAT-01', owner: '정운영', expiresAt: iso(1), note: null },
  { assetId: 'oa-9', type: 'DOMAIN', name: 'hr.example.go.kr', host: null, owner: '관리자', expiresAt: iso(140), note: null },
  { assetId: 'oa-10', type: 'SERVER', name: 'HR-BAT-01', host: '10.10.1.41', owner: '정운영', expiresAt: null, note: '배치 서버' },
]

export const KB_ARTICLES: KbArticle[] = [
  { kbId: 'kb-1', kbNo: 'KB-0001', title: '사원관리 저장 시 이력 2건 생성 (중복 클릭)', content: '## 증상\n저장 버튼을 빠르게 두 번 누르면 TB_EMP_HIST 에 이력이 2건 생긴다.\n\n## 원인\n`fn_save()` 에 중복 호출 방지가 없다.\n\n## 조치\n- 임시: 중복 이력 삭제 SQL 실행\n- 근본: CHG-0001 반영', tags: ['사원관리', '저장', '이력'], systemId: SM_SYS, screenId: 'scr-hr0101', sourceSrId: 'sr-10', updatedAt: '2026-10-02T15:00:00+09:00' },
  { kbId: 'kb-2', kbNo: 'KB-0002', title: 'SSO 인증서 만료로 로그인 불가', content: '## 확인\n`openssl s_client` 로 만료일 확인\n\n## 조치\n인증서 교체 후 WAS 재기동', tags: ['SSO', '로그인', '인증서'], systemId: SM_SYS, screenId: null, sourceSrId: 'sr-16', updatedAt: '2026-10-01T12:00:00+09:00' },
  { kbId: 'kb-3', kbNo: 'KB-0003', title: '급여명세조회 지연 시 통계 갱신', content: '월말 대량 적재 후 `DBMS_STATS.GATHER_TABLE_STATS(\'HR\', \'TB_PAY\')` 수행', tags: ['급여', '성능'], systemId: SM_SYS, screenId: 'scr-hr0304', sourceSrId: 'sr-6', updatedAt: '2026-10-14T12:00:00+09:00' },
  { kbId: 'kb-4', kbNo: 'KB-0004', title: '퇴직자 명단 추출 쿼리', content: '```sql\nSELECT EMP_NO, EMP_NM, RETIRE_DT FROM TB_EMP WHERE USE_YN = \'N\' AND RETIRE_DT BETWEEN :from AND :to\n```', tags: ['데이터 추출', '퇴직'], systemId: SM_SYS, screenId: null, sourceSrId: 'sr-13', updatedAt: '2026-09-20T10:00:00+09:00' },
  { kbId: 'kb-5', kbNo: 'KB-0005', title: '근태승인 버튼이 비활성인 경우', content: '결재선에 본인이 없거나 이미 승인된 건이면 비활성. TB_ATTEND.APPR_STATUS 확인.', tags: ['근태', '승인'], systemId: SM_SYS, screenId: 'scr-hr0202', sourceSrId: null, updatedAt: '2026-09-11T10:00:00+09:00' },
  { kbId: 'kb-6', kbNo: 'KB-0006', title: '첨부 업로드 10MB 제한 변경 방법', content: '`globals.properties` 의 `file.maxSize` 와 WAS 업로드 제한을 함께 바꾼다.', tags: ['첨부', '설정'], systemId: SM_SYS, screenId: 'scr-hr0201', sourceSrId: null, updatedAt: '2026-08-30T10:00:00+09:00' },
  { kbId: 'kb-7', kbNo: 'KB-0007', title: '메뉴 저장 후 권한 반영 안 될 때', content: '권한 캐시를 수동 갱신: 관리자 > 캐시 초기화. 근본 조치는 CHG-0005 재배포 예정.', tags: ['메뉴', '권한', '캐시'], systemId: SM_SYS, screenId: 'scr-hr0902', sourceSrId: 'sr-27', updatedAt: '2026-09-26T10:00:00+09:00' },
  { kbId: 'kb-8', kbNo: 'KB-0008', title: '신규 입사자 권한 부여 절차', content: '1. 인사발령 확정 확인\n2. 권한 그룹 ROLE_HR_USER 부여\n3. 부서 관리자 확인 메일', tags: ['권한', '입사'], systemId: SM_SYS, screenId: null, sourceSrId: null, updatedAt: '2026-08-12T10:00:00+09:00' },
]

export const MONTHLY_SEP: MonthlyReport = {
  reportId: 'mr-2026-09', month: '2026-09',
  summary: '9월은 SR 41건 접수, 해결 준수율 95% 로 목표(95%)를 달성했습니다. 메뉴 권한 캐시 변경이 롤백되어 10월 재배포 예정입니다.',
  plan: '사원관리 저장 중복 방지 패치(CHG-0001) 배포, 급여 배치 통계 자동화(CHG-0004).',
  sr: { total: 41, byType: { INQUIRY: 12, IMPROVE: 9, DATA: 11, INCIDENT: 6, AUTH: 3 }, byStatus: { CLOSED: 36, RESOLVED: 2, IN_PROGRESS: 3 }, carriedOver: 3 },
  sla: { complianceRate: 0.951, responseRate: 0.976, breaches: [{ srNo: 'SR-0109', title: '근태 집계 오류', reason: '원인 분석에 고객 데이터 필요(대기)' }, { srNo: 'SR-0114', title: '급여 재계산 요청', reason: null }] },
  incidents: [],
  changes: [{ changeNo: 'CHG-0005', title: '메뉴 권한 캐시 갱신 로직 수정', deployedAt: '2026-09-25T19:40:00+09:00', status: 'ROLLED_BACK' }],
  effort: [{ userId: 'u-op01', name: '정운영', hours: 168, mm: 1 }, { userId: 'u-dev01', name: '이개발', hours: 152, mm: 0.9 }, { userId: 'u-pl', name: '이PL', hours: 40, mm: 0.24 }],
  topScreens: [{ screenId: 'scr-hr0101', name: '사원관리', srCount: 9 }, { screenId: 'scr-hr0201', name: '근태신청', srCount: 5 }, { screenId: 'scr-hr0304', name: '급여명세조회', srCount: 4 }],
}

/** 영향도 결과 템플릿 — 대상 노드 이름만 바꿔 쓴다 */
export const IMPACT_TB_EMP: Omit<ImpactResult, 'target'> = {
  screens: [
    {
      screenId: 'scr-hr0101', screenCode: 'HR0101', name: '사원관리', menuPath: ['인사시스템', '인사관리', '사원관리'], owner: '김분석', elements: [
        { elementId: 'el-hr0101-btnSearch', label: '조회', crud: 'R', path: ['fn_search()', 'POST /hr/emp/list.do', 'EmpController.selectEmpList', 'EmpMapper.selectEmpList'] },
        { elementId: 'el-hr0101-btnSave', label: '저장', crud: 'CU', path: ['fn_save()', 'POST /hr/emp/save.do', 'EmpServiceImpl.saveEmp', 'EmpMapper.insertEmp / updateEmp'] },
        { elementId: 'el-hr0101-btnDelete', label: '삭제', crud: 'U', path: ['fn_delete()', 'POST /hr/emp/delete.do', 'EmpMapper.deleteEmp'] },
        { elementId: 'el-hr0101-btnExcel', label: '엑셀', crud: 'R', path: ['fn_excel()', 'GET /hr/emp/excel.do', 'EmpMapper.selectEmpList'] },
      ],
    },
    {
      screenId: 'scr-hr0103', screenCode: 'HR0103', name: '발령관리', menuPath: ['인사시스템', '인사관리', '발령관리'], owner: '최분석', elements: [
        { elementId: 'el-hr0103-btnSave', label: '발령 저장', crud: 'RU', path: ['fn_saveAppt()', 'POST /hr/appt/save.do', 'ApptServiceImpl.save', 'ApptMapper.updateEmpDept'] },
      ],
    },
    {
      screenId: 'scr-hr0105', screenCode: 'HR0105', name: '교육이력관리', menuPath: ['인사시스템', '인사관리', '교육이력관리'], owner: null, elements: [
        { elementId: 'el-hr0105-btnSearch', label: '조회', crud: 'R', path: ['EduMgr.search()', 'POST /hr/edu/emp.do', 'EduMapper.selectEmpList'] },
      ],
    },
    {
      screenId: 'scr-hr0304', screenCode: 'HR0304', name: '급여명세조회', menuPath: ['인사시스템', '급여관리', '급여명세조회'], owner: null, elements: [
        { elementId: 'el-hr0304-btnSearch', label: '조회', crud: 'R', path: ['fn_search()', 'POST /hr/pay/slip.do', 'PayMapper.selectPaySlip'] },
      ],
    },
  ],
  apis: [{ nodeId: 'nd-api-list', name: 'POST /hr/emp/list.do' }, { nodeId: 'nd-api-save', name: 'POST /hr/emp/save.do' }, { nodeId: 'nd-api-delete', name: 'POST /hr/emp/delete.do' }, { nodeId: 'nd-api-appt', name: 'POST /hr/appt/save.do' }],
  serverFunctions: [
    { nodeId: 'nd-fn-emp-list', name: 'EmpServiceImpl.selectEmpList', file: 'src/main/java/kr/go/hr/emp/service/impl/EmpServiceImpl.java' },
    { nodeId: 'nd-fn-emp-save', name: 'EmpServiceImpl.saveEmp', file: 'src/main/java/kr/go/hr/emp/service/impl/EmpServiceImpl.java' },
    { nodeId: 'nd-fn-appt-save', name: 'ApptServiceImpl.save', file: 'src/main/java/kr/go/hr/appt/service/impl/ApptServiceImpl.java' },
  ],
  batches: [{ nodeId: 'nd-fn-batch-retire', name: 'RetireBatchJob.execute', file: 'src/main/java/kr/go/hr/batch/RetireBatchJob.java' }],
  srs: [
    { srId: 'sr-12', srNo: 'SR-0012', title: '사원관리 저장 시 미래 입사일 입력 가능', status: 'IN_PROGRESS', createdAt: '2026-10-15T09:00:00+09:00' },
    { srId: 'sr-10', srNo: 'SR-0010', title: '사원관리 저장 버튼 중복 클릭 시 이력 2건 생성', status: 'CLOSED', createdAt: '2026-10-08T09:00:00+09:00' },
    { srId: 'sr-3', srNo: 'SR-0003', title: '2026년 3분기 퇴직자 명단 추출 요청', status: 'IN_PROGRESS', createdAt: '2026-10-15T08:00:00+09:00' },
  ],
  defects: [{ defectId: 'def-1', defectNo: 'DEF-0001', title: '사원 저장 시 미래 입사일 저장됨', status: 'ASSIGNED' }],
  owners: [
    { userId: 'u-analyst', name: '김분석', reason: '사원관리 화면 분석 담당' },
    { userId: 'u-op01', name: '정운영', reason: '최근 SR 3건 처리' },
  ],
  summary: { screens: 4, elements: 7, apis: 4, srs: 3 },
}

const col = (field: string, header: string) => ({ field, header, required: false, type: 'string' as const })
export const R3_DATASETS: ExcelDataset[] = [
  { datasetId: 'srs', name: 'SR 목록', importable: false, params: ['projectId'], columns: [col('srNo', 'SR 번호'), col('title', '제목'), col('status', '상태')] },
  { datasetId: 'incident-report', name: '장애보고서', importable: false, params: ['incidentId'], columns: [col('incidentNo', '장애 번호'), col('title', '제목')] },
  { datasetId: 'changes', name: '변경·배포', importable: false, params: ['projectId'], columns: [col('changeNo', '변경 번호'), col('title', '제목')] },
  { datasetId: 'monthly-ops-report', name: '월간 운영보고', importable: false, params: ['reportId'], columns: [col('section', '구분'), col('value', '내용')] },
  { datasetId: 'impact', name: '영향도 분석', importable: false, params: ['systemId', 'targetId', 'column'], columns: [col('screen', '화면'), col('element', '요소'), col('crud', 'CRUD'), col('path', '경로')] },
]
