#!/usr/bin/env bash
# PMS-X 종료 (Linux) — SIGTERM 으로 정상 종료, 30초 후에도 살아 있으면 강제 종료
set -euo pipefail
PMSX_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PIDF="$PMSX_HOME/run/pmsx.pid"
[ -f "$PIDF" ] || { echo "실행 중이 아닙니다 (run/pmsx.pid 없음)"; exit 0; }
PID="$(cat "$PIDF")"
kill "$PID" 2>/dev/null || true
for _ in $(seq 1 30); do kill -0 "$PID" 2>/dev/null || break; sleep 1; done
kill -0 "$PID" 2>/dev/null && kill -9 "$PID" || true
rm -f "$PIDF"
echo "PMS-X 종료"
