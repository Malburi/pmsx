package kr.pmsx.pj;

import java.util.Set;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 멤버십 기반 권한 확인 (docs/06 권한 매트릭스).
 * SYS_ADMIN·PMO 는 전 프로젝트 조회 가능, 그 외는 PJ_MEMBER 여야 한다.
 */
@Component
public class ProjectAccess {

    /** 결과 패키지 업로드 (06: 수집 결과 업로드) */
    public static final Set<String> UPLOAD = Set.of("SYS_ADMIN", "PM", "PL", "ANALYST", "OPERATOR");
    /** 시스템 등록 */
    public static final Set<String> SYSTEM_ADMIN = Set.of("SYS_ADMIN", "PM");
    /** 분석 상태 변경(확정 포함)·담당자 배정 */
    public static final Set<String> ANALYSIS_FULL = Set.of("PM", "PL");
    /** 핫스팟·연결 수동 보정 (06: PM·PL 전체, ANALYST 본인 담당, DEV·OPERATOR·PARTNER) */
    public static final Set<String> ASIS_EDIT = Set.of("PM", "PL", "ANALYST", "DEV", "OPERATOR", "PARTNER");
    /** 업무 규칙 메모 작성 */
    public static final Set<String> NOTE_WRITE = Set.of("PM", "PL", "ANALYST", "DEV", "QA", "OPERATOR", "PARTNER");
    /** 추정 관계 확정/기각·테이블 보정 */
    public static final Set<String> RELATION_EDIT = Set.of("PM", "PL", "ANALYST", "DEV", "OPERATOR");
    /** 산출물 출력 (CUSTOMER 는 확정 화면만) */
    public static final Set<String> DELIVERABLE = Set.of("PMO", "PM", "PL", "ANALYST", "OPERATOR", "CUSTOMER");

    private final JdbcTemplate jdbc;

    public ProjectAccess(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** projectRole: 해당 프로젝트에서의 역할(PJ_MEMBER.ROLE_ID), 비멤버면 null. */
    public record Access(String projectId, String projectRole, boolean sysAdmin, boolean pmo) {

        public boolean has(Set<String> allowed) {
            return (sysAdmin && allowed.contains("SYS_ADMIN"))
                    || (pmo && allowed.contains("PMO"))
                    || (projectRole != null && allowed.contains(projectRole));
        }

        public void require(Set<String> allowed) {
            if (!has(allowed)) {
                throw ApiException.forbidden();
            }
        }
    }

    public Access forProject(AuthUser user, String projectId) {
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM PJ_PROJECT WHERE PROJECT_ID = ?", Integer.class,
                projectId);
        if (exists == null || exists == 0) {
            throw ApiException.notFound("프로젝트");
        }
        String role = jdbc.query("SELECT ROLE_ID FROM PJ_MEMBER WHERE PROJECT_ID = ? AND USER_ID = ?",
                (rs, i) -> rs.getString(1), projectId, user.userId()).stream().findFirst().orElse(null);
        Access a = new Access(projectId, role, user.isSysAdmin(), user.isPmo());
        if (role == null && !a.sysAdmin() && !a.pmo()) {
            throw ApiException.forbidden();
        }
        return a;
    }

    public Access forSystem(AuthUser user, String systemId) {
        String projectId = jdbc.query("SELECT PROJECT_ID FROM AS_SYSTEM WHERE SYSTEM_ID = ?",
                (rs, i) -> rs.getString(1), systemId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("시스템"));
        Access a = forProject(user, projectId);
        if ("PARTNER".equals(a.projectRole())) {
            java.util.List<String> systems = partnerScope(projectId, user.userId(), "SYSTEM_IDS");
            if (!systems.isEmpty() && !systems.contains(systemId)) {
                throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "열람 범위 밖의 시스템입니다.");
            }
        }
        return a;
    }

    /** PARTNER 열람 범위 (docs/12 §7). column = SYSTEM_IDS / MENU_IDS, 비어 있으면 제한 없음. */
    public java.util.List<String> partnerScope(String projectId, String userId, String column) {
        if (!"SYSTEM_IDS".equals(column) && !"MENU_IDS".equals(column)) {
            throw new IllegalArgumentException(column);
        }
        return jdbc.query("SELECT " + column + " FROM PJ_MEMBER_SCOPE WHERE PROJECT_ID = ? AND USER_ID = ?",
                (rs, i) -> PjSupport.csv(rs.getString(1)), projectId, userId).stream().findFirst()
                .orElse(java.util.List.of());
    }

    /** 사용자에게 보이는 프로젝트 ID 조건 (SQL 조각과 파라미터는 호출자가 사용). */
    public boolean seesAll(AuthUser user) {
        return user.isSysAdmin() || user.isPmo();
    }
}
