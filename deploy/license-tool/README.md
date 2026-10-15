# PMS-X 라이선스 도구

키 형식: `base64url(JSON payload) + "." + base64url(Ed25519 서명)` — payload `{customer, issuedAt, expiresAt, maxUsers, features[]}`.
서버에는 **공개키만** 들어가고(jar 의 `license/public.key` 또는 설정 `pmsx.license.public-key`), 키 발급은 **개인키를 가진 공급사만** 한다. 검증은 완전 오프라인.

```bash
# 동봉 JRE 로 실행 (설치 폴더에서)
jre/bin/java -jar license-tool/pmsx-license-tool.jar keygen <출력폴더>          # 키쌍 생성 (공급사 내부)
jre/bin/java -jar license-tool/pmsx-license-tool.jar issue --private <private.key> \
    --customer "○○공사" --expires 2027-12-31 --max-users 100 --features asis,pms --out license.key
jre/bin/java -jar license-tool/pmsx-license-tool.jar verify --public <public.key> --key license.key
```

## 운영 키 관리 원칙
- `dev-keys/` 는 **개발·검증 전용**이다. 고객사 납품 시에는 공급사 내부에서 `keygen` 으로 **운영 키쌍을 새로 만들고**,
  그 공개키를 서버 설정 `pmsx.license.public-key`(Base64) 로 넣은 뒤 운영 개인키로 발급한 키를 적용한다.
- 운영 개인키는 패키지·저장소·고객사 서버 어디에도 두지 않는다 (오프라인 빌드 스크립트는 개인키를 패키지에 넣지 않는다).
- `maxUsers` 는 활성 사용자(사용 중·잠기지 않음) 수 기준. 초과 시 관리자만 로그인 가능.
