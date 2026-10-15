package kr.pmsx.as;

import kr.pmsx.cm.file.AttachmentTargetPolicy;
import kr.pmsx.common.security.AuthUser;
import org.springframework.stereotype.Component;

/** As-Is 화면 첨부(AS_SCREEN): 해당 시스템 프로젝트 멤버만 조회·업로드 (SYS_ADMIN·PMO 는 조회 가능, 06 기준). */
@Component
public class AsAttachmentPolicies implements AttachmentTargetPolicy {

    private final AsScope scope;

    public AsAttachmentPolicies(AsScope scope) {
        this.scope = scope;
    }

    @Override
    public String targetType() {
        return "AS_SCREEN";
    }

    @Override
    public void check(AuthUser user, String targetId, boolean write) {
        AsScope.ScreenRef s = scope.screen(targetId);
        var a = scope.read(user, s.systemId());
        if (write && a.projectRole() == null) {
            throw kr.pmsx.common.ApiException.forbidden();   // 비멤버 SYS_ADMIN·PMO 는 조회만
        }
    }

    @Override
    public String projectId(String targetId) {
        return scope.projectId(scope.screen(targetId).systemId());
    }
}
