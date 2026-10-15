package kr.pmsx;

import kr.pmsx.cm.file.AttachmentTargetPolicy;
import kr.pmsx.common.security.AuthUser;
import org.springframework.stereotype.Component;

/** 테스트 전용 첨부 대상(TEST): 로그인 사용자 누구나. */
@Component
class TestAttachmentPolicy implements AttachmentTargetPolicy {

    @Override
    public String targetType() {
        return "TEST";
    }

    @Override
    public void check(AuthUser user, String targetId, boolean write) {
        // 제한 없음
    }
}
