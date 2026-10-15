package kr.pmsx.pj;

import java.util.Set;

/**
 * R2 권한 역할 집합 (docs/06 권한 매트릭스 §2). 조회는 프로젝트 접근(멤버·SYS_ADMIN·PMO)만 확인하고,
 * 별도 조회 제한이 있는 기능만 *_READ 를 둔다.
 */
public final class Perm {

    private Perm() {
    }

    /** WBS 편집 (PM 전체, PL 등록·수정) */
    public static final Set<String> WBS_EDIT = Set.of("PM", "PL");
    /** 베이스라인 저장 */
    public static final Set<String> BASELINE = Set.of("PM");
    /** 주간보고 작성·출력 */
    public static final Set<String> WEEKLY_EDIT = Set.of("PM", "PL");
    public static final Set<String> WEEKLY_READ = Set.of("PMO", "PM", "PL", "CUSTOMER");
    /** 인력 풀·투입 계획 */
    public static final Set<String> RESOURCE_EDIT = Set.of("SYS_ADMIN", "PM");
    public static final Set<String> RESOURCE_READ = Set.of("SYS_ADMIN", "PMO", "PM", "PL");
    /** 작업 배분 */
    public static final Set<String> ASSIGN = Set.of("PM", "PL");
    public static final Set<String> ASSIGN_READ = Set.of("PMO", "PM", "PL");
    /** 작업일지 입력(본인) */
    public static final Set<String> TIMESHEET_WRITE = Set.of("PM", "PL", "ANALYST", "DEV", "QA", "OPERATOR", "PARTNER");
    /** 작업일지 승인 */
    public static final Set<String> TIMESHEET_APPROVE = Set.of("PM", "PL");
    /** MM 실적·정산 */
    public static final Set<String> MM_READ = Set.of("PMO", "PM", "PL");
    public static final Set<String> MM_EDIT = Set.of("PM");
    /** 산출물 등록 */
    public static final Set<String> DELIVERABLE_EDIT = Set.of("PM", "PL", "ANALYST", "DEV", "QA", "OPERATOR", "PARTNER");
    /** 요구사항·추적성 편집 */
    public static final Set<String> REQ_EDIT = Set.of("PM", "PL", "ANALYST");
    /** 추적 링크 편집 (요구사항 편집자 + 테스트·결함 담당) */
    public static final Set<String> TRACE_EDIT = Set.of("PM", "PL", "ANALYST", "DEV", "QA", "OPERATOR", "PARTNER");
    /** 이슈·리스크·회의록 (CUSTOMER 는 본인 등록 건) */
    public static final Set<String> ISSUE_EDIT = Set.of("PM", "PL", "ANALYST", "DEV", "QA", "OPERATOR", "CUSTOMER", "PARTNER");
    /** 변경요청 승인 */
    public static final Set<String> CR_APPROVE = Set.of("PM", "CUSTOMER");
    /** 테스트·결함 (CUSTOMER 는 본인 등록 건) */
    public static final Set<String> TEST_EDIT = Set.of("PM", "PL", "DEV", "QA", "OPERATOR", "CUSTOMER", "PARTNER");
    /** 프로젝트 관리(멤버·열람 범위·체크리스트) */
    public static final Set<String> PROJECT_MANAGE = Set.of("SYS_ADMIN", "PM");
}
