package kr.pmsx.cm;

import java.util.List;
import kr.pmsx.common.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 비밀번호 정책 (NFR-SEC-005, docs/10 §4).
 * 9자 이상, 영문·숫자·특수문자 모두 포함, 현재/직전 비밀번호 재사용 금지.
 */
@Component
public class PasswordPolicy {

    private final PasswordEncoder encoder;

    public PasswordPolicy(PasswordEncoder encoder) {
        this.encoder = encoder;
    }

    public void check(String password) {
        if (password == null || password.length() < 9) {
            throw violation("비밀번호는 9자 이상이어야 합니다.");
        }
        boolean letter = password.chars().anyMatch(c -> c < 128 && Character.isLetter(c));
        boolean digit = password.chars().anyMatch(Character::isDigit);
        boolean special = password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
        if (!(letter && digit && special)) {
            throw violation("비밀번호는 영문·숫자·특수문자를 모두 포함해야 합니다.");
        }
    }

    /** 정책 + 이전 비밀번호(해시들)와 같지 않은지 */
    public void checkNew(String password, List<String> previousHashes) {
        check(password);
        for (String h : previousHashes) {
            if (h != null && encoder.matches(password, h)) {
                throw violation("직전에 사용한 비밀번호는 다시 쓸 수 없습니다.");
            }
        }
    }

    private static ApiException violation(String message) {
        return ApiException.badRequest("PASSWORD_POLICY", message);
    }
}
