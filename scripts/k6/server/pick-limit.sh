#!/usr/bin/env bash
# 운영 서버 대상 부하 회차용(docs/LoadTestPlan.md "운영 서버 대상 회차").
# usage: bash scripts/k6/server/pick-limit.sh <분당 값|unset>
#
# 픽 레이트 리밋(D-077, IP당 분당 60)만 임시 override로 올려 app을 다시 만든다. compose는 명시한
# 환경 변수만 컨테이너에 넘기므로 .env에 적어서는 앱에 닿지 않는다. 회차가 끝나면 unset으로 되돌린다.
set -euo pipefail
cd ~/menupick
C="docker compose -f docker-compose.prod.yml -f docker-compose.oci.yml"
OVERRIDE=/tmp/lt-override.yml
if [ "$1" != "unset" ]; then
  [[ "$1" =~ ^[0-9]+$ ]] || { echo "ERROR: 숫자나 unset만 받는다" >&2; exit 1; }
  printf 'services:\n  app:\n    environment:\n      RATE_LIMIT_PICK_PER_MINUTE: "%s"\n' "$1" > "$OVERRIDE"
  $C -f "$OVERRIDE" up -d app >/dev/null 2>&1
else
  rm -f "$OVERRIDE"
  $C up -d app >/dev/null 2>&1
fi
for i in $(seq 1 60); do
  s=$(docker inspect -f '{{.State.Health.Status}}' menupick-app); [ "$s" = healthy ] && break; sleep 3
done
echo "app=$s"
docker exec menupick-app printenv RATE_LIMIT_PICK_PER_MINUTE || echo "RATE_LIMIT_PICK_PER_MINUTE 없음(기본 60)"
