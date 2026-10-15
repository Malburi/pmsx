// 화면 단위 권한 판단 (docs/06 권한 매트릭스). 최종 판단은 서버가 하고(403), 화면은 버튼 노출만 결정한다.
import type { ScreenAnalysis } from '@/types/api'

type HasAnyRole = (roles: string[]) => boolean

/** 핫스팟·연결 수동 보정: PM·PL·ANALYST(본인 담당)·DEV·OPERATOR·PARTNER */
export const EDIT_HOTSPOT_ROLES = ['PM', 'PL', 'ANALYST', 'DEV', 'OPERATOR', 'PARTNER']
/** 담당자 배정·분석 확정: PM·PL */
export const ASSIGN_ROLES = ['PM', 'PL']
/** 업무 규칙 메모 작성: PM·PL·ANALYST·DEV·QA·OPERATOR·PARTNER */
export const NOTE_ROLES = ['PM', 'PL', 'ANALYST', 'DEV', 'QA', 'OPERATOR', 'PARTNER']
/** 추정 관계 확정/기각: PM·PL·ANALYST·DEV·OPERATOR */
export const RELATION_ROLES = ['PM', 'PL', 'ANALYST', 'DEV', 'OPERATOR']
/** 산출물 출력: PM·PL·ANALYST·OPERATOR·CUSTOMER(조회) */
export const EXPORT_ROLES = ['PM', 'PL', 'ANALYST', 'OPERATOR', 'CUSTOMER', 'PMO']

/**
 * ANALYST 는 본인 담당이거나 미배정 화면만 편집할 수 있다 (06 각주 3).
 * 다른 편집 역할(PM·PL·DEV…)은 담당과 관계없이 편집 가능.
 */
export const canEditScreen = (hasAnyRole: HasAnyRole, analysis: ScreenAnalysis | null, userId: string | null) => {
  if (hasAnyRole(EDIT_HOTSPOT_ROLES.filter((r) => r !== 'ANALYST'))) return true
  if (!hasAnyRole(['ANALYST'])) return false
  return !analysis?.owner || analysis.owner.userId === userId
}

/** 분석 상태 변경 가능 목록 — 확정(DONE)은 PM·PL 만 */
export const allowedStatuses = (hasAnyRole: HasAnyRole) =>
  hasAnyRole(ASSIGN_ROLES) ? (['AUTO', 'WORKING', 'REVIEW', 'DONE'] as const) : (['WORKING', 'REVIEW'] as const)

/* ── R2 (docs/06 권한 매트릭스) ───────────────────────────── */
/** WBS 편집: PM·PL / 베이스라인 저장: PM */
export const WBS_EDIT_ROLES = ['PM', 'PL']
export const BASELINE_ROLES = ['PM']
/** 주간보고 작성: PM·PL */
export const WEEKLY_EDIT_ROLES = ['PM', 'PL']
/** 인력 풀·투입 계획: 조회 SYS_ADMIN·PMO·PM·PL / 편집 SYS_ADMIN·PM */
export const RESOURCE_VIEW_ROLES = ['SYS_ADMIN', 'PMO', 'PM', 'PL']
export const RESOURCE_EDIT_ROLES = ['SYS_ADMIN', 'PM']
/** 작업 배분: 조회 PMO / 배정 PM·PL */
export const WORK_ASSIGN_VIEW_ROLES = ['PMO', 'PM', 'PL']
export const WORK_ASSIGN_ROLES = ['PM', 'PL']
/** 작업일지 입력(본인) / 승인 */
export const TIMESHEET_ROLES = ['PM', 'PL', 'ANALYST', 'DEV', 'QA', 'OPERATOR', 'PARTNER']
export const TIMESHEET_APPROVE_ROLES = ['PM', 'PL']
/** MM 실적·정산 */
export const MM_VIEW_ROLES = ['PMO', 'PM', 'PL']
/** 산출물 등록 / 승인(고객 승인 단계 포함) */
export const DELIVERABLE_EDIT_ROLES = ['PM', 'PL', 'ANALYST', 'DEV', 'QA', 'OPERATOR', 'PARTNER']
export const DELIVERABLE_APPROVE_ROLES = ['PM', 'CUSTOMER']
/** 요구사항·추적성 편집 */
export const REQUIREMENT_EDIT_ROLES = ['PM', 'PL', 'ANALYST']
/** 이슈·리스크 등록 */
export const ISSUE_EDIT_ROLES = ['PM', 'PL', 'ANALYST', 'DEV', 'QA', 'OPERATOR', 'CUSTOMER', 'PARTNER']
/** CR 승인: PM·고객 */
export const CR_APPROVE_ROLES = ['PM', 'CUSTOMER']
/** 테스트·결함 편집 (QA 는 전체) */
export const TEST_EDIT_ROLES = ['PM', 'PL', 'DEV', 'QA', 'OPERATOR', 'CUSTOMER', 'PARTNER']
/** 방법론 템플릿 편집 */
export const METHODOLOGY_EDIT_ROLES = ['SYS_ADMIN']
/** PMO 포트폴리오 */
export const PORTFOLIO_ROLES = ['SYS_ADMIN', 'PMO']

/* ── R3 (docs/06 권한 매트릭스 — SM 운영) ─────────────────── */
/** 운영(SM) 메뉴 조회: SM 프로젝트 내부 인력 + PMO (CUSTOMER 는 포털 사용) */
export const SM_VIEW_ROLES = ['SYS_ADMIN', 'PMO', 'PM', 'PL', 'OPERATOR', 'DEV', 'QA', 'ANALYST']
/** SR 등록: PM·PL·OPERATOR (CUSTOMER 는 포털) */
export const SR_CREATE_ROLES = ['PM', 'PL', 'OPERATOR']
/** SR 처리(전이·공수): PM·PL 전체, OPERATOR 본인 담당 */
export const SR_PROCESS_ROLES = ['PM', 'PL', 'OPERATOR']
export const SR_ASSIGN_ROLES = ['PM', 'PL']
/** 장애·변경·배포·정기 작업·운영 자산·KB 편집 */
export const SM_EDIT_ROLES = ['PM', 'PL', 'OPERATOR']
/** CAB 승인: PM·PL·고객 */
export const CAB_ROLES = ['PM', 'PL', 'CUSTOMER']
/** SLA 정책: 조회 SYS_ADMIN·PMO·PM·OPERATOR / 편집 SYS_ADMIN·PM */
export const SLA_POLICY_VIEW_ROLES = ['SYS_ADMIN', 'PMO', 'PM', 'OPERATOR']
export const SLA_POLICY_EDIT_ROLES = ['SYS_ADMIN', 'PM']
/** 월간 운영보고 작성 */
export const MONTHLY_EDIT_ROLES = ['PM', 'PL', 'OPERATOR']
/** SI→SM 인수인계 실행 */
export const HANDOVER_ROLES = ['SYS_ADMIN', 'PM']

/** OPERATOR 는 본인 담당(또는 미배정) SR 만 처리한다 (06 "SR 처리" O) */
export const canProcessSr = (hasAnyRole: HasAnyRole, assigneeId: string | null, userId: string | null) => {
  if (hasAnyRole(['PM', 'PL'])) return true
  return hasAnyRole(['OPERATOR']) && (!assigneeId || assigneeId === userId)
}
