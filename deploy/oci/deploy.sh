#!/bin/bash
# 운영 배포: DB 기동 → 백업 → 코드 풀기 → APP_VERSION 교체 → 백업 유닛 재설치 → 이미지 교체 → 확인.
#
# 쓰는 법(로컬, 저장소 루트에서):
#   git archive --format=tar origin/main -o main.tar
#   scp main.tar deploy/oci/deploy.sh opc@146.56.116.44:/tmp/
#   ssh opc@146.56.116.44 "bash /tmp/deploy.sh $(git rev-parse origin/main)"
#
# - SHA는 **40자 전체**를 넘긴다. CI가 이미지를 전체 SHA로 태그하므로 짧은 SHA는 pull이 실패한다.
# - 그 SHA의 main CI(이미지 push)가 끝난 뒤에 돌린다.
# - 백업이 실패하면 거기서 멈춘다(set -e). 마이그레이션이 든 배포는 이미지 교체만으로 되돌아가지 않는다.
# - 처음 서버를 세울 때가 아니라, 컨테이너가 한 번 만들어진 서버에서 쓰는 스크립트다.
set -euo pipefail

SHA="${1:-}"
ARCHIVE="${2:-/tmp/main.tar}"
if [[ ! "$SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "usage: $0 <40자 커밋 SHA> [main.tar 경로]" >&2
  exit 2
fi
[ -f "$ARCHIVE" ] || { echo "archive not found: $ARCHIVE" >&2; exit 2; }

cd ~/menupick
C="docker compose -f docker-compose.prod.yml -f docker-compose.oci.yml"

wait_healthy() {
  local name="$1" tries="$2" s
  for _ in $(seq 1 "$tries"); do
    s=$(docker inspect -f '{{.State.Health.Status}}' "$name" 2>/dev/null || echo none)
    [ "$s" = healthy ] && break
    sleep 3
  done
  echo "$name: $s"
  [ "$s" = healthy ]
}

echo "== start db"
$C start mysql redis >/dev/null
wait_healthy menupick-mysql 60

echo "== backup"
sudo systemctl start menupick-backup.service
LATEST=$(ls -t ~/backups/menupick-*.sql.gz | head -1)
gzip -t "$LATEST" && echo "backup ok: $LATEST"

echo "== code"
tar -xf "$ARCHIVE" -C ~/menupick
sed -i "s/^APP_VERSION=.*/APP_VERSION=$SHA/" .env
grep -q "^APP_VERSION=$SHA$" .env && echo "APP_VERSION=$SHA"

echo "== backup unit (#381)"
# 백업 스크립트와 유닛은 저장소가 원본이다. 코드만 풀고 끝내면 서버에는 옛 것이 남는다.
sudo install -m 0755 scripts/backup-db.sh /usr/local/bin/menupick-backup
sudo cp deploy/oci/menupick-backup.service /etc/systemd/system/
sudo systemctl daemon-reload

echo "== images"
$C pull -q app web
$C up -d 2>&1 | tail -6
wait_healthy menupick-app 80
docker ps --format '{{.Names}} {{.Status}}'
curl -sk -o /dev/null -w 'demo %{http_code}\n' https://127.0.0.1/api/v1/pick/demo
docker inspect -f '{{.Config.Image}}' menupick-app menupick-web

# 쓰지 않는 옛 이미지 정리는 따로 한다(docker image prune -a -f). 지우면 롤백할 때 다시 pull해야 한다.
