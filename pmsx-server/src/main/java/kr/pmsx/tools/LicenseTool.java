package kr.pmsx.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PMS-X 라이선스 키 발급 도구 (docs/10 §6). 의존성 없는 단독 실행 jar.
 *
 * <pre>
 *   java -jar pmsx-license-tool.jar keygen  &lt;출력폴더&gt;
 *   java -jar pmsx-license-tool.jar issue   --private &lt;private.key&gt; --customer "○○공사" --expires 2027-12-31
 *                                          --max-users 100 [--features asis,pms] [--issued 2026-10-15] [--out license.key]
 *   java -jar pmsx-license-tool.jar verify  --public &lt;public.key&gt; --key &lt;license.key 파일 또는 키 문자열&gt;
 * </pre>
 *
 * 키 형식: base64url(JSON payload) + "." + base64url(Ed25519 서명). 서버에는 공개키만 들어간다.
 */
public final class LicenseTool {

    private LicenseTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(1);
        }
        Map<String, String> opts = options(args);
        switch (args[0]) {
            case "keygen" -> keygen(Path.of(args.length > 1 ? args[1] : "."));
            case "issue" -> issue(opts);
            case "verify" -> verify(opts);
            default -> {
                usage();
                System.exit(1);
            }
        }
    }

    private static void keygen(Path dir) throws Exception {
        Files.createDirectories(dir);
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path priv = dir.resolve("private.key");
        Path pub = dir.resolve("public.key");
        if (Files.exists(priv)) {
            throw new IllegalStateException("이미 private.key 가 있습니다. 덮어쓰지 않습니다: " + priv.toAbsolutePath());
        }
        Files.writeString(priv, Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()) + "\n");
        Files.writeString(pub, Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\n");
        System.out.println("키쌍 생성 완료");
        System.out.println("  개인키(외부 반출 금지): " + priv.toAbsolutePath());
        System.out.println("  공개키(서버 설정 pmsx.license.public-key 에 넣음): " + pub.toAbsolutePath());
    }

    private static void issue(Map<String, String> o) throws Exception {
        String customer = required(o, "customer");
        LocalDate expires = LocalDate.parse(required(o, "expires"));
        int maxUsers = Integer.parseInt(required(o, "max-users"));
        LocalDate issued = o.containsKey("issued") ? LocalDate.parse(o.get("issued")) : LocalDate.now();
        List<String> features = new ArrayList<>();
        if (o.containsKey("features")) {
            for (String f : o.get("features").split(",")) {
                if (!f.isBlank()) {
                    features.add(f.trim());
                }
            }
        }
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(
                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(read(Path.of(required(o, "private"))))));

        StringBuilder json = new StringBuilder("{");
        json.append("\"customer\":").append(quote(customer));
        json.append(",\"issuedAt\":").append(quote(issued.toString()));
        json.append(",\"expiresAt\":").append(quote(expires.toString()));
        json.append(",\"maxUsers\":").append(maxUsers);
        json.append(",\"features\":[");
        for (int i = 0; i < features.size(); i++) {
            json.append(i == 0 ? "" : ",").append(quote(features.get(i)));
        }
        json.append("]}");

        byte[] payload = json.toString().getBytes(StandardCharsets.UTF_8);
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(key);
        sig.update(payload);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String licenseKey = b64.encodeToString(payload) + "." + b64.encodeToString(sig.sign());

        if (o.containsKey("out")) {
            Files.writeString(Path.of(o.get("out")), licenseKey + "\n");
            System.out.println("라이선스 키 저장: " + Path.of(o.get("out")).toAbsolutePath());
        }
        System.out.println(json);
        System.out.println(licenseKey);
    }

    private static void verify(Map<String, String> o) throws Exception {
        PublicKey pub = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(read(Path.of(required(o, "public"))))));
        String k = required(o, "key");
        Path kp = Path.of(k);
        String licenseKey = k.contains(".") && !Files.exists(kp) ? k.trim() : read(kp);
        int dot = licenseKey.indexOf('.');
        byte[] payload = Base64.getUrlDecoder().decode(licenseKey.substring(0, dot));
        byte[] signature = Base64.getUrlDecoder().decode(licenseKey.substring(dot + 1));
        Signature sig = Signature.getInstance("Ed25519");
        sig.initVerify(pub);
        sig.update(payload);
        boolean ok = sig.verify(signature);
        System.out.println(new String(payload, StandardCharsets.UTF_8));
        System.out.println(ok ? "서명 유효" : "서명 불일치");
        System.exit(ok ? 0 : 2);
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p).trim();
    }

    private static String required(Map<String, String> o, String name) {
        String v = o.get(name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("--" + name + " 값이 필요합니다.");
        }
        return v;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--") && i + 1 < args.length) {
                m.put(args[i].substring(2), args[++i]);
            }
        }
        return m;
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    private static void usage() {
        System.out.println("""
                사용법:
                  keygen <출력폴더>
                  issue  --private <private.key> --customer <고객사> --expires <yyyy-MM-dd> --max-users <N>
                         [--features a,b] [--issued <yyyy-MM-dd>] [--out <파일>]
                  verify --public <public.key> --key <license.key 파일 또는 키 문자열>""");
    }
}
