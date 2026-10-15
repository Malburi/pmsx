#!/usr/bin/env bash
# PMS-X 시연용 시작 (Linux). demo 프로필 — 별도 DB(data-demo/), 동봉 데모 As-Is(demo/*.zip) 자동 적재.
# 데모 계정: admin / analyst / customer (비밀번호 pmsx1234!). 운영에 쓰지 말 것.
set -euo pipefail
PMSX_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PMSX_HOME
cd "$PMSX_HOME"
mkdir -p run/tmp logs
if [ -f run/pmsx.pid ] && kill -0 "$(cat run/pmsx.pid)" 2>/dev/null; then
  echo "PMS-X 가 이미 실행 중입니다 (PID $(cat run/pmsx.pid))"; exit 1
fi
JAVA="$PMSX_HOME/jre/bin/java"; [ -x "$JAVA" ] || JAVA="java"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g}"
nohup "$JAVA" $JAVA_OPTS -Dfile.encoding=UTF-8 -Dloader.path="$PMSX_HOME/lib" -jar "$PMSX_HOME/pmsx-server.jar" \
  --spring.profiles.active=demo \
  >> logs/console.log 2>&1 &
echo "PMS-X 데모 시작 (PID $!). 로그: $PMSX_HOME/logs/pmsx-demo.log"
echo "로그인: admin / pmsx1234!  (고객 포털: customer / pmsx1234!)"
