package kr.pmsx.cm;

import jakarta.validation.Valid;
import kr.pmsx.cm.AuthDtos.ChangePasswordRequest;
import kr.pmsx.cm.AuthDtos.LoginRequest;
import kr.pmsx.cm.AuthDtos.LoginResponse;
import kr.pmsx.cm.AuthDtos.RefreshResponse;
import kr.pmsx.cm.AuthDtos.UserDto;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.ErrorResponse;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.security.AuthUser;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    static final String COOKIE = "PMSX_RT";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final AuthService auth;
    private final PmsxProperties.Jwt props;

    public AuthController(AuthService auth, PmsxProperties properties) {
        this.auth = auth;
        this.props = properties.jwt();
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        AuthService.Tokens t = auth.login(req.loginId(), req.password());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(t.refreshToken(), props.refreshTtl()).toString())
                .body(new LoginResponse(t.accessToken(), t.user()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = COOKIE, required = false) String rt) {
        try {
            AuthService.Tokens t = auth.refresh(rt);
            return ResponseEntity.ok()
                    .header(HttpHeaders.SET_COOKIE, cookie(t.refreshToken(), props.refreshTtl()).toString())
                    .body(new RefreshResponse(t.accessToken()));
        } catch (ApiException e) {
            // 실패 시 쿠키도 지워 클라이언트가 재시도 루프에 빠지지 않게 한다
            return ResponseEntity.status(e.getStatus())
                    .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                    .body(ErrorResponse.of(e.getCode(), e.getMessage()));
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = COOKIE, required = false) String rt) {
        auth.logout(rt);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthUser user) {
        return auth.me(user);
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal AuthUser user,
            @Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(user, req.currentPassword(), req.newPassword());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(COOKIE, value)
                .httpOnly(true)
                .secure(props.cookieSecure())
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
