#!/usr/bin/env bash
# 폐쇄망 검사 (NFR-ENV-002): 웹 번들·서버 설정에 외부 호출 URL 이 있는지 본다.
#  - CDN·웹폰트·외부 API 호스트(차단 목록)가 하나라도 있으면 실패(exit 1)
#  - XML 네임스페이스(w3.org 등) 같은 무해한 식별자 URL 은 허용 목록으로 무시
#  - 그 밖의 URL 은 경고로만 출력 (라이브러리 주석·오류 메시지 속 문서 링크 등)
# 사용법: check-external-urls.sh <폴더> [<폴더> ...]
set -uo pipefail

BLOCK='(fonts\.googleapis\.com|fonts\.gstatic\.com|cdn\.jsdelivr\.net|unpkg\.com|cdnjs\.cloudflare\.com|code\.jquery\.com|ajax\.googleapis\.com|cdn\.tailwindcss\.com|use\.fontawesome\.com|api\.iconify\.design|esm\.sh|googletagmanager\.com|google-analytics\.com|sentry\.io)'
ALLOW='^https?://(www\.w3\.org|w3\.org|json-schema\.org|purl\.org|xmlns\.com|schemas\.openxmlformats\.org|schemas\.microsoft\.com|ns\.adobe\.com|localhost|127\.0\.0\.1|0\.0\.0\.0)([/:]|$)'

found_block=0
tmp="$(mktemp)"
for dir in "$@"; do
  [ -d "$dir" ] || continue
  grep -rhoE "https?://[A-Za-z0-9._~:%-]+(/[^\"' )\`<>]*)?" "$dir" \
    --include='*.js' --include='*.mjs' --include='*.css' --include='*.html' --include='*.json' \
    --include='*.yml' --include='*.yaml' --include='*.properties' --include='*.svg' 2>/dev/null >> "$tmp" || true
done

sort -u "$tmp" -o "$tmp"
blocked="$(grep -E "$BLOCK" "$tmp" || true)"
others="$(grep -vE "$ALLOW" "$tmp" | grep -vE "$BLOCK" || true)"
rm -f "$tmp"

if [ -n "$blocked" ]; then
  echo "❌ 외부 CDN/웹폰트/분석 호출 URL 발견 (폐쇄망에서 동작하지 않음):"
  echo "$blocked" | sed 's/^/   /'
  found_block=1
fi
if [ -n "$others" ]; then
  cnt="$(echo "$others" | wc -l | tr -d ' ')"
  echo "⚠ 참고: 기타 URL $cnt 건 (대부분 라이브러리 주석·문서 링크 — 실제 호출 여부 확인 권장)"
  echo "$others" | head -20 | sed 's/^/   /'
fi
if [ "$found_block" = 0 ]; then
  echo "✅ 외부 호출 차단 대상 URL 없음"
fi
exit $found_block
