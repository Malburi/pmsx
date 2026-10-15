# deploy — 오프라인 설치 패키지 (docs/10 §7)

```bash
# pmsx/ 에서 (Git Bash / Linux)
bash deploy/build-offline.sh                 # 웹 빌드 → 외부 URL 검사 → 서버 jar(웹 포함) → jlink JRE → zip
bash deploy/build-offline.sh --skip-web      # 이전 웹 빌드(deploy/out/web-dist) 재사용

# Windows PowerShell
powershell -ExecutionPolicy Bypass -File deploy\build-offline.ps1 [-SkipWeb]
```
결과: `deploy/out/pmsx-<버전>-offline.zip` (+ 풀린 폴더 `deploy/out/pmsx-<버전>/`). `deploy/out/` 은 커밋하지 않는다.

| 경로 | 내용 |
|---|---|
| `build-offline.sh` / `.ps1` | 패키지 빌드 |
| `check-external-urls.sh` | 웹 번들·서버 설정의 외부 호출 URL 검사 (CDN·웹폰트가 있으면 실패) |
| `template/` | 패키지에 그대로 들어가는 파일: `bin/`, `config/`, `nginx/`, `docker/`(미검증), `docs/설치가이드.md` |
| `license-tool/` | 라이선스 도구 안내, `dev-keys/`(개발 전용 키쌍·라이선스) |

- JRE 는 빌드 머신 OS 용으로 만든다(현재 Windows x64). Linux 용은 Linux JDK 21 을 받아 `--jdk <경로>` 로 지정한다.
- 패키지에는 개발용 **공개키와 개발 라이선스만** 들어가고 개인키는 들어가지 않는다.
