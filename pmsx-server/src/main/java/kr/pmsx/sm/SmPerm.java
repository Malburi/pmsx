package kr.pmsx.sm;

import java.util.Set;

/** R3 SM 권한 역할 집합 (docs/06 권한 매트릭스 SM 행). 조회는 프로젝트 접근(멤버·SYS_ADMIN·PMO)으로 충분하다. */
public final class SmPerm {

    private SmPerm() {
    }

    /** SR 등록 (CUSTOMER 는 포털 또는 본인 건) */
    public static final Set<String> SR_CREATE = Set.of("PM", "PL", "OPERATOR", "CUSTOMER");
    /** SR 처리 전체 (OPERATOR 는 본인 담당 건만 — 서비스에서 추가 확인) */
    public static final Set<String> SR_PROCESS = Set.of("PM", "PL", "OPERATOR");
    public static final Set<String> SR_PROCESS_ALL = Set.of("PM", "PL");
    /** 장애·문제·변경·정기 작업·운영 자산·KB 편집 */
    public static final Set<String> OPS_EDIT = Set.of("PM", "PL", "OPERATOR");
    /** 변경 CAB 결정 */
    public static final Set<String> CAB = Set.of("PM", "PL", "CUSTOMER");
    /** SLA 정책 편집 */
    public static final Set<String> SLA_EDIT = Set.of("SYS_ADMIN", "PM");
    /** 월간 운영보고 작성 */
    public static final Set<String> REPORT_EDIT = Set.of("PM", "PL", "OPERATOR");
    /** SI→SM 인수인계 */
    public static final Set<String> HANDOVER = Set.of("SYS_ADMIN", "PM");

    public static final Set<String> SR_TYPES = Set.of("INQUIRY", "IMPROVE", "DATA", "INCIDENT", "AUTH");
    public static final Set<String> PRIORITIES = Set.of("P1", "P2", "P3", "P4");
}
