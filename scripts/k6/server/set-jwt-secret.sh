#!/usr/bin/env bash
# 운영 서버 대상 부하 회차용(docs/LoadTestPlan.md "운영 서버 대상 회차").
# usage: bash scripts/k6/server/set-jwt-secret.sh <secret-file>
#
# .env의 JWT_SECRET을 파일의 값으로 바꾸고 컨테이너를 다시 만든다. 값은 출력하지 않고 파일은 지운다.
# 기존 토큰은 전부 무효가 된다. 회차 전에는 회차 전용 값으로, 회차 뒤에는 새 값으로 한 번 더 부른다.
set -euo pipefail
cd ~/menupick
NEW=$(cat "$1")
[ "${#NEW}" -ge 32 ] || { echo "ERROR: 시크릿이 너무 짧다(32자 이상)" >&2; exit 1; }
cp .env .env.bak-lt
awk -v v="$NEW" 'BEGIN{FS=OFS="="} /^JWT_SECRET=/{print "JWT_SECRET=" v; next} {print}' .env.bak-lt > .env
chmod --reference=.env.bak-lt .env
rm -f .env.bak-lt "$1"
[ "$(grep -c '^JWT_SECRET=' .env)" = 1 ] || { echo "ERROR: .env의 JWT_SECRET 줄이 하나가 아니다" >&2; exit 1; }

C="docker compose -f docker-compose.prod.yml -f docker-compose.oci.yml"
$C up -d >/dev/null 2>&1
for i in $(seq 1 60); do
  s=$(docker inspect -f '{{.State.Health.Status}}' menupick-app 2>/dev/null || echo none)
  [ "$s" = healthy ] && break; sleep 3
done
echo "app=$s"
