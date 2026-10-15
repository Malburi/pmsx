package kr.pmsx.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import javax.crypto.SecretKey;
import kr.pmsx.common.PmsxProperties;
import org.springframework.stereotype.Service;

/** Access token(JWT HS256) 발급·검증. Refresh token 은 불투명 토큰으로 AuthService 가 관리한다. */
@Service
public class JwtService {

    private final SecretKey key;
    private final PmsxProperties.Jwt props;

    public JwtService(PmsxProperties properties) {
        this.props = properties.jwt();
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(resolveSecret(props.secret())));
    }

    /**
     * 비밀키가 비어 있으면(운영 설치 기본) ${PMSX_HOME}/data/jwt-secret.key 를 읽고, 없으면 무작위로 만들어 저장한다.
     * 설치마다 다른 키가 쓰이고, 재기동해도 유지된다.
     */
    static String resolveSecret(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        Path file = Path.of(System.getenv().getOrDefault("PMSX_HOME", "."), "data", "jwt-secret.key");
        try {
            if (Files.exists(file)) {
                return Files.readString(file).trim();
            }
            byte[] raw = new byte[48];
            new SecureRandom().nextBytes(raw);
            String secret = Base64.getEncoder().encodeToString(raw);
            Files.createDirectories(file.getParent());
            Files.writeString(file, secret + System.lineSeparator());
            return secret;
        } catch (IOException e) {
            throw new UncheckedIOException("JWT 비밀키 파일을 읽거나 만들 수 없습니다: " + file.toAbsolutePath(), e);
        }
    }

    public String issueAccessToken(AuthUser user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.userId())
                .claim("loginId", user.loginId())
                .claim("name", user.name())
                .claim("roles", user.systemRoles())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(props.accessTtl())))
                .signWith(key)
                .compact();
    }

    public Optional<AuthUser> parse(String token) {
        try {
            Claims c = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            @SuppressWarnings("unchecked")
            List<String> roles = c.get("roles", List.class);
            return Optional.of(new AuthUser(c.getSubject(), c.get("loginId", String.class),
                    c.get("name", String.class), roles == null ? List.of() : List.copyOf(roles)));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
