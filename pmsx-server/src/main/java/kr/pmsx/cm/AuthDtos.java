package kr.pmsx.cm;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record LoginRequest(@NotBlank(message = "아이디를 입력해주세요.") String loginId,
                               @NotBlank(message = "비밀번호를 입력해주세요.") String password) {
    }

    /** 08 명세 User. roles = 시스템 역할 + 참여 프로젝트 역할. */
    public record UserDto(String userId, String loginId, String name, List<String> roles) {
    }

    public record LoginResponse(String accessToken, UserDto user) {
    }

    public record RefreshResponse(String accessToken) {
    }

    public record ChangePasswordRequest(@NotBlank(message = "현재 비밀번호를 입력해주세요.") String currentPassword,
                                        @NotBlank(message = "새 비밀번호를 입력해주세요.") String newPassword) {
    }
}
