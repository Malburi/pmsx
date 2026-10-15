# ⚠ 개발·검증 전용 키 (DEV ONLY)

- `public.key` — 개발용 공개키. `pmsx-server/src/main/resources/license/public.key` 와 같다.
- `private.key` — 개발용 개인키. **저장소에만 있고 설치 패키지에는 들어가지 않는다.** 누구나 이 키로 개발용 라이선스를 만들 수 있으므로 운영에 쓰면 안 된다.
- `dev-license.key` — 개발용 라이선스 (고객사 "개발·검증용 (DEV)", 만료 2027-12-31, 사용자 50명).

**고객사 운영 설치에는 공급사가 새로 만든 운영 키쌍을 쓴다** → `../README.md` 의 "운영 키 관리 원칙".
