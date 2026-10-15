package kr.pmsx.common.security;

import java.util.List;

/** 인증된 사용자 (JWT access token 클레임). roles 는 시스템 역할만 담는다. */
public record AuthUser(String userId, String loginId, String name, List<String> systemRoles) {

    public boolean isSysAdmin() {
        return systemRoles.contains("SYS_ADMIN");
    }

    public boolean isPmo() {
        return systemRoles.contains("PMO");
    }
}
