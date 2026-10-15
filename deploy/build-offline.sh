#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────
#  PMS-X 오프라인 설치 패키지 빌드 (docs/10 §7)
#
#  사용법 (pmsx/ 에서):  bash deploy/build-offline.sh [--skip-web] [--jdk <JDK21 경로>]
#
#  1) pmsx-web 빌드 (npm run build → deploy/out/web-dist)
#  2) 웹 번들 외부 URL 검사 (CDN·웹폰트 호출이 있으면 실패)
#  3) pmsx-server bootJar (웹 정적 리소스 포함, 단일 jar) + 라이선스 도구 jar
#  4) jlink 로 JRE 21 생성 (빌드 머신 OS 용. 다른 OS 는 --jdk 로 그 OS 의 JDK jmods 지정)
#  5) deploy/out/pmsx-<버전>/ 구성 → deploy/out/pmsx-<버전>-offline.zip
#
#  준비물: Node(npm), 인터넷(최초 npm/gradle 의존성 내려받기). 결과 zip 은 인터넷 없이 설치된다.
# ─────────────────────────────────────────────────────────────────────
set -euo pipefail

DEPLOY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$DEPLOY/.." && pwd)"
WEB="$ROOT/pmsx-web"
SERVER="$ROOT/pmsx-server"
OUT="$DEPLOY/out"
SKIP_WEB=0
JDK="${PMSX_JDK21:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-web) SKIP_WEB=1 ;;
    --jdk) JDK="$2"; shift ;;
    *) echo "알 수 없는 옵션: $1"; exit 1 ;;
  esac
  shift
done

VERSION="$(grep -E "^version *= *'" "$SERVER/build.gradle" | sed -E "s/.*'(.*)'.*/\1/")"
PKG="pmsx-$VERSION"
STAGE="$OUT/$PKG"
echo "▶ PMS-X $VERSION 오프라인 패키지 빌드"

# ── JDK 21 찾기 (Gradle 이 내려받은 툴체인 우선) ──
if [ -z "$JDK" ]; then
  for d in "$HOME"/.gradle/jdks/*21*; do
    if [ -x "$d/bin/jlink" ] || [ -x "$d/bin/jlink.exe" ]; then JDK="$d"; break; fi
  done
fi
if [ -z "$JDK" ] && [ -n "${JAVA_HOME:-}" ]; then JDK="$JAVA_HOME"; fi
JLINK="$JDK/bin/jlink"; [ -x "$JLINK.exe" ] && JLINK="$JLINK.exe"
JAR="$JDK/bin/jar";     [ -x "$JAR.exe" ] && JAR="$JAR.exe"
if [ ! -x "$JLINK" ]; then
  echo "❌ JDK 21 의 jlink 를 찾지 못했습니다. --jdk <경로> 또는 PMSX_JDK21 환경 변수를 지정하세요."
  echo "   (pmsx-server 에서 ./gradlew build 를 한 번 실행하면 ~/.gradle/jdks 에 JDK 21 이 내려받아집니다)"
  exit 1
fi
echo "  JDK: $JDK"

mkdir -p "$OUT"
rm -rf "$STAGE" "$OUT/$PKG-offline.zip"

# ── 1) 웹 빌드 ──
WEB_DIST="$OUT/web-dist"
if [ "$SKIP_WEB" = 1 ]; then
  [ -f "$WEB_DIST/index.html" ] || { echo "❌ --skip-web 인데 $WEB_DIST 가 없습니다."; exit 1; }
  echo "▶ [1/5] 웹 빌드 생략 (기존 $WEB_DIST 사용)"
else
  echo "▶ [1/5] 웹 빌드"
  ( cd "$WEB"
    [ -d node_modules ] || npm ci --no-audit --no-fund
    rm -rf "$WEB_DIST"
    npm run build -- --outDir "$WEB_DIST" --emptyOutDir )
fi

# ── 2) 외부 URL 검사 ──
echo "▶ [2/5] 웹 번들 외부 URL 검사"
bash "$DEPLOY/check-external-urls.sh" "$WEB_DIST" "$SERVER/src/main/resources"

# ── 3) 서버 jar ──
echo "▶ [3/5] 서버 jar (웹 포함) + 라이선스 도구"
( cd "$SERVER" && ./gradlew --quiet clean bootJar licenseToolJar -PwebDist="$WEB_DIST" )

# ── 4) JRE ──
echo "▶ [4/5] jlink 로 JRE 21 생성"
MODULES="java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.management,jdk.naming.dns,jdk.localedata,jdk.charsets"
"$JLINK" --add-modules "$MODULES" --include-locales=en,ko --strip-debug --no-header-files --no-man-pages \
  --compress=zip-6 --output "$STAGE/jre"

# ── 5) 패키지 구성 ──
echo "▶ [5/5] 패키지 구성"
cp "$SERVER/build/libs/pmsx-server.jar" "$STAGE/"
mkdir -p "$STAGE/license-tool"
cp "$SERVER/build/libs/pmsx-license-tool.jar" "$STAGE/license-tool/"
cp -r "$DEPLOY/template/." "$STAGE/"
cp "$DEPLOY/license-tool/README.md" "$STAGE/license-tool/"
mkdir -p "$STAGE/license-tool/dev-keys"
cp "$DEPLOY/license-tool/dev-keys/public.key" "$DEPLOY/license-tool/dev-keys/dev-license.key" \
   "$DEPLOY/license-tool/dev-keys/README.md" "$STAGE/license-tool/dev-keys/"   # 개인키는 넣지 않는다
mkdir -p "$STAGE/run" "$STAGE/logs" "$STAGE/data"
# 시연용 As-Is 수집 결과 (bin/start-demo 가 적재) — samples/<샘플>/out 의 최신 zip
mkdir -p "$STAGE/demo"
for s in hr-legacy hr-nexacro hr-spa; do
  z="$(ls "$ROOT/samples/$s/out"/pmsx-*.zip 2>/dev/null | sort | tail -1 || true)"
  if [ -n "$z" ]; then cp "$z" "$STAGE/demo/"; echo "  데모 As-Is: $(basename "$z")"; else echo "  (데모 As-Is 없음: samples/$s/out)"; fi
done
chmod +x "$STAGE"/bin/*.sh 2>/dev/null || true
printf 'PMS-X %s\nbuilt: %s\n' "$VERSION" "$(date '+%Y-%m-%d %H:%M:%S')" > "$STAGE/VERSION.txt"

( cd "$OUT" && "$JAR" --create --no-manifest --file "$PKG-offline.zip" -C "$OUT" "$PKG" )
SIZE="$(du -h "$OUT/$PKG-offline.zip" | cut -f1)"
echo "✅ 완료: $OUT/$PKG-offline.zip ($SIZE)"
