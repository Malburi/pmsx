package kr.pmsx.cm.file;

import kr.pmsx.common.security.AuthUser;

/**
 * 첨부 대상 유형(targetType)별 권한 규칙. 새 기능이 첨부를 쓰려면 이 인터페이스를 구현한 빈을 등록한다.
 * 등록되지 않은 targetType 의 업로드·조회는 거부된다.
 */
public interface AttachmentTargetPolicy {

    /** 예: AS_SCREEN, DL_DELIVERABLE, QA_RESULT, SM_SR */
    String targetType();

    /** 대상이 없으면 NOT_FOUND, 권한이 없으면 FORBIDDEN 예외. write=true 는 업로드·삭제. */
    void check(AuthUser user, String targetId, boolean write);

    /** 감사 로그 PROJECT_ID 용 (선택) */
    default String projectId(String targetId) {
        return null;
    }
}
