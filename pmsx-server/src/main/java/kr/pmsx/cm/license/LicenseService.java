package kr.pmsx.cm.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import kr.pmsx.common.ApiException;
import kr.pmsx.common.PmsxProperties;
import kr.pmsx.common.Times;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 오프라인 라이선스 검증 (FR-CM-011, docs/10 §6).
 * 키 = base64url(JSON) + "." + base64url(Ed25519 서명). 서버에는 공개키만 있다.
 * 적용 키 우선순위: DB(CM_LICENSE) → 설정(pmsx.license.key).
 */
@Service
public class LicenseService {

    public record LicenseInfo(String customer, LocalDate issuedAt, LocalDate expiresAt, int maxUsers,
                              List<String> features, boolean valid, String reason) {
    }

    public record LicenseStatus(boolean valid, String reason, LocalDate expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final PmsxProperties props;
    private final PublicKey publicKey;

    public LicenseService(JdbcTemplate jdbc, ObjectMapper om, PmsxProperties props) {
        this.jdbc = jdbc;
        this.om = om;
        this.props = props;
        this.publicKey = loadPublicKey(props);
    }

    public boolean enforced() {
        return props.licenseEnforced();
    }

    /** 현재 적용된 라이선스 평가 결과. 키가 없으면 null. */
    public LicenseInfo current() {
        String key = currentKey();
        return key == null ? null : evaluate(key);
    }

    public LicenseStatus status() {
        LicenseInfo info = current();
        if (!enforced()) {
            return new LicenseStatus(true, null, info == null ? null : info.expiresAt());
        }
        if (info == null) {
            return new LicenseStatus(false, "라이선스 키가 적용되지 않았습니다.", null);
        }
        return new LicenseStatus(info.valid(), info.reason(), info.expiresAt());
    }

    /** 라이선스가 무효이면 SYS_ADMIN 외에는 로그인 불가 (docs/10 §6). */
    public void checkLogin(boolean sysAdmin) {
        if (!enforced() || sysAdmin) {
            return;
        }
        LicenseStatus s = status();
        if (!s.valid()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "LICENSE_INVALID",
                    "라이선스가 유효하지 않아 관리자만 로그인할 수 있습니다. (" + s.reason() + ")");
        }
    }

    /** 새 키 적용. 서명·형식·기간이 유효해야 저장한다. */
    public LicenseInfo apply(String key, String userId) {
        String trimmed = key == null ? "" : key.trim();
        LicenseInfo info = evaluate(trimmed);
        if (info.customer() == null || !info.valid() && !info.reason().startsWith("사용자 수")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "LICENSE_INVALID", info.reason());
        }
        int updated = jdbc.update("UPDATE CM_LICENSE SET LICENSE_KEY = ?, APPLIED_AT = ?, APPLIED_BY = ?"
                + " WHERE LICENSE_ID = 'CURRENT'", trimmed, Times.now(), userId);
        if (updated == 0) {
            jdbc.update("INSERT INTO CM_LICENSE (LICENSE_ID, LICENSE_KEY, APPLIED_AT, APPLIED_BY)"
                    + " VALUES ('CURRENT', ?, ?, ?)", trimmed, Times.now(), userId);
        }
        return info;
    }

    public LicenseInfo evaluate(String key) {
        int dot = key.indexOf('.');
        if (dot <= 0 || dot == key.length() - 1) {
            return invalid("라이선스 키 형식이 올바르지 않습니다.");
        }
        byte[] payload;
        byte[] signature;
        try {
            payload = Base64.getUrlDecoder().decode(key.substring(0, dot));
            signature = Base64.getUrlDecoder().decode(key.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return invalid("라이선스 키 형식이 올바르지 않습니다.");
        }
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(publicKey);
            sig.update(payload);
            if (!sig.verify(signature)) {
                return invalid("라이선스 서명이 올바르지 않습니다.");
            }
        } catch (Exception e) {
            return invalid("라이선스 서명이 올바르지 않습니다.");
        }
        JsonNode p;
        try {
            p = om.readTree(new String(payload, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return invalid("라이선스 내용을 읽을 수 없습니다.");
        }
        String customer = p.path("customer").asText(null);
        LocalDate issuedAt = date(p, "issuedAt");
        LocalDate expiresAt = date(p, "expiresAt");
        int maxUsers = p.path("maxUsers").asInt(0);
        List<String> features = new ArrayList<>();
        p.path("features").forEach(f -> features.add(f.asText()));
        if (customer == null || expiresAt == null) {
            return invalid("라이선스 내용이 올바르지 않습니다.");
        }
        String reason = null;
        if (Times.now().toLocalDate().isAfter(expiresAt)) {
            reason = "라이선스가 만료되었습니다 (" + expiresAt + ").";
        } else {
            Integer active = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM CM_USER WHERE ENABLED = TRUE AND LOCKED = FALSE", Integer.class);
            if (maxUsers > 0 && active != null && active > maxUsers) {
                reason = "사용자 수(" + active + "명)가 라이선스 허용 수(" + maxUsers + "명)를 넘었습니다.";
            }
        }
        return new LicenseInfo(customer, issuedAt, expiresAt, maxUsers, features, reason == null, reason);
    }

    private String currentKey() {
        String db = jdbc.query("SELECT LICENSE_KEY FROM CM_LICENSE WHERE LICENSE_ID = 'CURRENT'",
                (rs, i) -> rs.getString(1)).stream().findFirst().orElse(null);
        if (db != null && !db.isBlank()) {
            return db.trim();
        }
        String cfg = props.license() == null ? null : props.license().key();
        return cfg == null || cfg.isBlank() ? null : cfg.trim();
    }

    private static LicenseInfo invalid(String reason) {
        return new LicenseInfo(null, null, null, 0, List.of(), false, reason);
    }

    private static LocalDate date(JsonNode p, String field) {
        try {
            return p.hasNonNull(field) ? LocalDate.parse(p.get(field).asText()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PublicKey loadPublicKey(PmsxProperties props) {
        try {
            String b64 = props.license() == null ? null : props.license().publicKey();
            if (b64 == null || b64.isBlank()) {
                try (InputStream in = new ClassPathResource("license/public.key").getInputStream()) {
                    b64 = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64.trim())));
        } catch (Exception e) {
            throw new IllegalStateException("라이선스 공개키를 읽을 수 없습니다.", e);
        }
    }
}
