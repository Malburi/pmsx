#!/usr/bin/env bash
# PMS-X 시작 (Linux). 동봉 JRE(있으면) 사용, prod 프로필, config/application-prod.yml 적용
set -euo pipefail
PMSX_HOME="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export PMSX_HOME
cd "$PMSX_HOME"
mkdir -p run logs
if [ -f run/pmsx.pid ] && kill -0 "$(cat run/pmsx.pid)" 2>/dev/null; then
  echo "PMS-X 가 이미 실행 중입니다 (PID $(cat run/pmsx.pid))"; exit 1
fi
JAVA="$PMSX_HOME/jre/bin/java"; [ -x "$JAVA" ] || JAVA="java"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx2g}"
nohup "$JAVA" $JAVA_OPTS -Dfile.encoding=UTF-8 -Dloader.path="$PMSX_HOME/lib" -jar "$PMSX_HOME/pmsx-server.jar" \
  --spring.profiles.active=prod --spring.config.additional-location=optional:file:./config/ \
  >> logs/console.log 2>&1 &
echo "PMS-X 시작 (PID $!). 로그: $PMSX_HOME/logs/pmsx.log"
