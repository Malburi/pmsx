package kr.pmsx.common;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "pmsx")
public record PmsxProperties(Jwt jwt, Storage storage, Cors cors, Seed seed, FileRule file, License license,
                             Auth auth, Notify notification, Mm mm, Sm sm, Demo demo) {

    /**
     * SM 운영 (docs/13). inboundMailKey: 메일 게이트웨이 접수 API 키(비면 접수 API 비활성).
     * demoAsisPackage: 데모 시드 시 인사시스템에 적재할 수집 결과 zip(또는 그 zip 들이 있는 폴더). local 전용.
     */
    public record Sm(String inboundMailKey, String demoAsisPackage, Integer cabApprovalsRequired) {
    }

    /**
     * 시연용 데모 (demo·local 프로필). asisPackages: 데모 As-Is 수집 결과 zip 또는 zip 들이 있는 폴더 목록.
     * 비우면 ./demo (설치 패키지) → 없으면 ../samples/*&#47;out (개발 환경) 을 찾는다.
     */
    public record Demo(List<String> asisPackages) {
    }

    /** 설정된 데모 As-Is 패키지 경로 (구 설정 pmsx.sm.demo-asis-package 포함). 비어 있으면 기본 탐색 */
    public List<String> demoAsisPackages() {
        List<String> out = new java.util.ArrayList<>();
        if (demo != null && demo.asisPackages() != null) {
            demo.asisPackages().stream().filter(x -> x != null && !x.isBlank()).forEach(out::add);
        }
        if (demoAsisPackage() != null) {
            out.add(demoAsisPackage());
        }
        return out;
    }

    /** 변경 CAB 승인 필요 건수 (PM 승인은 1건으로 충분). 기본 2 */
    public int cabApprovalsRequired() {
        return sm == null || sm.cabApprovalsRequired() == null || sm.cabApprovalsRequired() < 1 ? 2
                : sm.cabApprovalsRequired();
    }

    public String inboundMailKey() {
        return sm == null || sm.inboundMailKey() == null || sm.inboundMailKey().isBlank() ? null : sm.inboundMailKey();
    }

    public String demoAsisPackage() {
        return sm == null || sm.demoAsisPackage() == null || sm.demoAsisPackage().isBlank() ? null
                : sm.demoAsisPackage();
    }

    public record Jwt(String secret, Duration accessTtl, Duration refreshTtl, boolean cookieSecure) {
    }

    public record Storage(String dir) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    /** demo=true 면 샘플 프로젝트에 R2 데모 데이터(WBS·인력·이슈 등)를 넣는다 (local 전용) */
    public record Seed(boolean enabled, boolean demo) {
    }

    /** 알림 채널 (docs/12 §7): 웹훅 URL(JSON POST), 메일 발신 주소 */
    public record Notify(String webhookUrl, String mailFrom) {
    }

    /** 실적 MM 환산 기준 시간 (기본 168) */
    public record Mm(Double hoursPerMonth) {
    }

    /** 첨부 제한 (docs/10 §2) */
    public record FileRule(DataSize maxSize, List<String> allowedExt) {
    }

    /**
     * 라이선스 (docs/10 §6). enforce=false 면 검사 생략(local·test).
     * publicKey: Base64(X.509) Ed25519 공개키. 비우면 classpath:license/public.key 사용.
     * key: 설정 파일로 키를 넣는 경우(DB 적용 키가 우선).
     */
    public record License(boolean enforce, String publicKey, String key) {
    }

    public record Auth(Ldap ldap, Integer maxFailures) {
    }

    public record Ldap(boolean enabled, String url, String baseDn, String userSearchFilter, String bindDn,
                       String bindPassword) {
    }

    // ── null 안전 접근자 ──

    public DataSize fileMaxSize() {
        return file == null || file.maxSize() == null ? DataSize.ofMegabytes(50) : file.maxSize();
    }

    public List<String> fileAllowedExt() {
        return file == null || file.allowedExt() == null ? List.of() : file.allowedExt();
    }

    public boolean licenseEnforced() {
        return license != null && license.enforce();
    }

    public Ldap ldap() {
        return auth == null || auth.ldap() == null ? new Ldap(false, null, null, null, null, null) : auth.ldap();
    }

    public boolean demoSeed() {
        return seed != null && seed.enabled() && seed.demo();
    }

    public String webhookUrl() {
        return notification == null || notification.webhookUrl() == null || notification.webhookUrl().isBlank() ? null
                : notification.webhookUrl();
    }

    public String mailFrom() {
        return notification == null || notification.mailFrom() == null || notification.mailFrom().isBlank() ? "pmsx@localhost"
                : notification.mailFrom();
    }

    public double hoursPerMonth() {
        return mm == null || mm.hoursPerMonth() == null || mm.hoursPerMonth() <= 0 ? 168.0 : mm.hoursPerMonth();
    }

    public int maxLoginFailures() {
        return auth == null || auth.maxFailures() == null ? 5 : auth.maxFailures();
    }
}
