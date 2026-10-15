package kr.pmsx.sm;

import java.util.Set;
import kr.pmsx.cm.file.AttachmentTargetPolicy;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R3 첨부 대상 권한: SM_SR(고객은 본인·같은 고객사 SR 만, 업로드도 가능), SM_INCIDENT·SM_KB·SM_CHANGE(조회 = 프로젝트 접근,
 * 업로드·삭제 = 운영 편집 역할).
 */
@Configuration
public class SmAttachmentPolicies {

    record TablePolicy(String targetType, String table, String idColumn, Set<String> writers, JdbcTemplate jdbc,
                       ProjectAccess access) implements AttachmentTargetPolicy {

        @Override
        public void check(AuthUser user, String targetId, boolean write) {
            var a = access.forProject(user, projectId(targetId));
            if (write) {
                a.require(writers);
            }
        }

        @Override
        public String projectId(String targetId) {
            return jdbc.query("SELECT PROJECT_ID FROM " + table + " WHERE " + idColumn + " = ?",
                    (rs, i) -> rs.getString(1), targetId).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("첨부 대상"));
        }
    }

    @Bean
    AttachmentTargetPolicy srAttachments(JdbcTemplate jdbc, ProjectAccess access, SrService srs) {
        return new AttachmentTargetPolicy() {
            @Override
            public String targetType() {
                return "SM_SR";
            }

            @Override
            public void check(AuthUser user, String targetId, boolean write) {
                SrService.Row r = srs.rowOf(targetId);
                var a = access.forProject(user, r.projectId());
                if ("CUSTOMER".equals(a.projectRole()) && !a.sysAdmin() && !a.pmo()) {
                    if (!SrController.inScope(srs.customerScope(user), r)) {
                        throw ApiException.notFound("SR");
                    }
                    return;
                }
                if (write) {
                    a.require(Set.of("PM", "PL", "OPERATOR"));
                }
            }

            @Override
            public String projectId(String targetId) {
                return srs.rowOf(targetId).projectId();
            }
        };
    }

    @Bean
    AttachmentTargetPolicy incidentAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("SM_INCIDENT", "SM_INCIDENT", "INCIDENT_ID", SmPerm.OPS_EDIT, jdbc, access);
    }

    @Bean
    AttachmentTargetPolicy kbAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("SM_KB", "SM_KB", "KB_ID", SmPerm.OPS_EDIT, jdbc, access);
    }

    @Bean
    AttachmentTargetPolicy changeAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("SM_CHANGE", "SM_CHANGE", "CHANGE_ID", SmPerm.OPS_EDIT, jdbc, access);
    }
}
