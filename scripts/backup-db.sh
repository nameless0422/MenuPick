#!/usr/bin/env bash
#
# MySQL 논리 백업. systemd 타이머(deploy/oci/menupick-backup.timer)가 매일 부른다.
#
# 백업에서 가장 무서운 실패는 "실패"가 아니라 **성공한 척**이다. 컨테이너가 꺼져 있거나
# 덤프가 중간에 끊겨도 파일은 하나 생기고, 아무도 열어 보지 않으면 복구가 필요한 날까지
# 알 수 없다. 2026-08-19에 인스턴스가 재생성되며 데이터가 통째로 사라졌을 때 남아 있던
# 것이 아무것도 없었던 것이 이 스크립트를 만든 이유다.
#
# 그래서 세 겹으로 막는다.
#   1. MySQL이 healthy가 아니면 시작조차 하지 않는다.
#   2. 임시 이름(.part)으로 받아 **검증에 통과한 뒤에만** 최종 이름으로 옮긴다.
#      실패한 시도는 백업 디렉터리에 "백업처럼 생긴 파일"로 남지 않는다.
#   3. gzip 무결성과 mysqldump의 완료 표지를 둘 다 본다. 파이프 중간에 끊긴 덤프는
#      gzip으로는 멀쩡해 보일 수 있어서, 끝까지 쓰였다는 증거를 따로 확인한다.
#
# 환경변수로 조정 가능(기본값은 이 서버 기준):
#   APP_DIR(~/menupick) BACKUP_DIR(~/backups) BACKUP_KEEP_DAYS(14)
#   MYSQL_CONTAINER(menupick-mysql) MYSQL_DB(menupick)
set -euo pipefail

APP_DIR="${APP_DIR:-$HOME/menupick}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
CONTAINER="${MYSQL_CONTAINER:-menupick-mysql}"
DB="${MYSQL_DB:-menupick}"

log() { printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# --check: 유닛의 ExecCondition이 부른다. "일부러 내려 둔 상태"면 1로 끝내 이번 실행을
# 건너뛰게 한다(systemd는 1~254를 실패가 아니라 조건 불충족으로 본다 — ExecStart도 오프사이트
# 업로드도 돌지 않고, OnFailure 알림도 가지 않는다).
#
# 운영 방식이 "검증이 끝나면 컨테이너를 stop 해 둔다"라서, 그 기간 매일 백업이 실패로
# 찍히면 진짜 실패와 구분할 수 없다. 데이터가 바뀌지 않는 기간이라 건너뛰어도 잃는 것이 없다.
#
# 건너뛰는 것은 docker가 "정상적으로 멈춘 컨테이너"라고 말할 때(exited·created)뿐이다.
# 컨테이너가 없거나, 돌고 있는데 healthy가 아니거나, 재시작을 반복하는 것은 고장이라
# 0으로 통과시켜 본 실행이 실패하고 알림이 가게 둔다.
if [ "${1:-}" = --check ]; then
  state="$(docker inspect -f '{{.State.Status}}' "$CONTAINER" 2>/dev/null || echo missing)"
  case "$state" in
    exited|created)
      log "SKIP: $CONTAINER 가 멈춰 있다(상태: $state) — 컨테이너를 내려 둔 기간이라 백업을 건너뛴다"
      exit 1 ;;
    *) exit 0 ;;
  esac
fi

[ -f "$APP_DIR/.env" ] || die "$APP_DIR/.env 가 없다"

# 자격증명은 .env에서만 읽는다. 스크립트에도 타이머 유닛에도 두지 않는다.
set -a
# shellcheck disable=SC1091
. "$APP_DIR/.env"
set +a
[ -n "${MYSQL_ROOT_PASSWORD:-}" ] || die ".env에 MYSQL_ROOT_PASSWORD가 없다"

# 꺼져 있으면 빈 파일을 만들지 말고 여기서 멈춘다 — "백업이 있다"는 착각이 제일 위험하다.
health="$(docker inspect -f '{{.State.Health.Status}}' "$CONTAINER" 2>/dev/null || echo missing)"
[ "$health" = healthy ] || die "$CONTAINER 가 healthy가 아니다 (현재: $health) — 백업하지 않는다"

mkdir -p "$BACKUP_DIR"
stamp="$(date -u '+%Y%m%d-%H%M%SZ')"
out="$BACKUP_DIR/menupick-$stamp.sql.gz"
tmp="$out.part"
# 도중에 죽어도 .part 를 남기지 않는다.
trap 'rm -f "$tmp"' EXIT

log "덤프 시작 → $out"
# --single-transaction: InnoDB를 잠그지 않고 일관된 스냅샷을 얻는다(서비스 중단 없음).
# set -o pipefail 이 걸려 있어 mysqldump가 실패하면 gzip이 성공해도 여기서 멈춘다.
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" "$CONTAINER" \
    mysqldump -u root --single-transaction --routines --triggers --events "$DB" \
    2> >(grep -v 'Using a password' >&2) \
  | gzip > "$tmp"

gzip -t "$tmp" || die "gzip 무결성 검사 실패"
# mysqldump는 정상 종료 시 마지막 줄에 완료 표지를 남긴다. 파이프가 중간에 끊기면
# gzip 스트림은 유효한데 내용만 잘린 파일이 나올 수 있어 이걸 따로 본다.
zcat "$tmp" | tail -5 | grep -q '^-- Dump completed' || die "덤프가 끝까지 쓰이지 않았다"

mv "$tmp" "$out"
trap - EXIT
log "완료: $(du -h "$out" | cut -f1)  테이블 $(zcat "$out" | grep -c '^CREATE TABLE')개"

deleted="$(find "$BACKUP_DIR" -maxdepth 1 -name 'menupick-*.sql.gz' -mtime +"$KEEP_DAYS" -print -delete | wc -l)"
log "보관 정리: ${KEEP_DAYS}일 초과 ${deleted}건 삭제, 현재 $(find "$BACKUP_DIR" -maxdepth 1 -name 'menupick-*.sql.gz' | wc -l)건 보관"
