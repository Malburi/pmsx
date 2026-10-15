package kr.pmsx.dl;

import java.util.Set;
import kr.pmsx.cm.file.AttachmentTargetPolicy;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.security.AuthUser;
import kr.pmsx.pj.Perm;
import kr.pmsx.pj.PjSupport;
import kr.pmsx.pj.ProjectAccess;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R2 첨부 대상 권한 (docs/12): 조회는 프로젝트 접근, 업로드·삭제는 해당 대장의 편집 역할.
 * QA_RESULT 의 targetId 는 "{roundId}:{caseId}".
 */
@Configuration
public class R2AttachmentPolicies {

    /** 테이블·ID 컬럼으로 프로젝트를 찾는 단순 정책 */
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
    AttachmentTargetPolicy deliverableAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("DL_DELIVERABLE", "DL_DELIVERABLE", "DELIVERABLE_ID", Perm.DELIVERABLE_EDIT, jdbc,
                access);
    }

    @Bean
    AttachmentTargetPolicy requirementAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("DL_REQUIREMENT", "DL_REQUIREMENT", "REQ_ID", Perm.REQ_EDIT, jdbc, access);
    }

    @Bean
    AttachmentTargetPolicy issueAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("QA_ISSUE", "QA_ISSUE", "ISSUE_ID", Perm.ISSUE_EDIT, jdbc, access);
    }

    @Bean
    AttachmentTargetPolicy riskAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("QA_RISK", "QA_RISK", "RISK_ID", Perm.ISSUE_EDIT, jdbc, access);
    }

    @Bean
    AttachmentTargetPolicy crAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("QA_CR", "QA_CR", "CR_ID", Perm.ISSUE_EDIT, jdbc, access);
    }

    @Bean
    AttachmentTargetPolicy defectAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("QA_DEFECT", "QA_DEFECT", "DEFECT_ID", Perm.TEST_EDIT, jdbc, access);
    }

    @Bean
    AttachmentTargetPolicy meetingAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new TablePolicy("QA_MEETING", "QA_MEETING", "MEETING_ID", Perm.ISSUE_EDIT, jdbc, access);
    }

    /** 테스트 결과 증적: targetId = roundId:caseId */
    @Bean
    AttachmentTargetPolicy testResultAttachments(JdbcTemplate jdbc, ProjectAccess access) {
        return new AttachmentTargetPolicy() {
            public String targetType() {
                return "QA_RESULT";
            }

            public void check(AuthUser user, String targetId, boolean write) {
                var a = access.forProject(user, projectId(targetId));
                if (write) {
                    a.require(Perm.TEST_EDIT);
                }
            }

            public String projectId(String targetId) {
                String[] p = targetId == null ? new String[0] : targetId.split(":", 2);
                if (p.length != 2) {
                    throw PjSupport.validation("targetId", "QA_RESULT 대상은 {roundId}:{caseId} 형식입니다.");
                }
                return jdbc.query("""
                        SELECT D.PROJECT_ID FROM QA_TEST_ROUND D JOIN QA_TEST_CASE C ON C.PROJECT_ID = D.PROJECT_ID
                         WHERE D.ROUND_ID = ? AND C.CASE_ID = ?""", (rs, i) -> rs.getString(1), p[0], p[1]).stream()
                        .findFirst().orElseThrow(() -> ApiException.notFound("테스트 결과"));
            }
        };
    }
}
