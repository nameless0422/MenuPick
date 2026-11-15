#!/usr/bin/env bash
#
# 가장 최근 DB 백업을 OCI Object Storage로 올린다. menupick-backup.service의
# ExecStartPost가 부른다 — 덤프가 검증까지 통과했을 때만 돈다.
#
# 왜 필요한가: `~/backups/`는 인스턴스의 부트 볼륨 위에 있다. 2026-08-19에 인스턴스가
# 재생성되며 데이터가 통째로 사라졌을 때 백업도 같은 디스크에 있었다면 함께 사라졌을
# 것이다. 서버 밖에 한 벌이 있어야 백업이다.
#
# 인증은 **쓰기 전용 사전 인증 요청(PAR)** 하나다. OCI CLI·API 키·인스턴스 프린시펄이
# 필요 없고, 무엇보다 권한이 좁다 — 버킷에 객체를 쓸 수만 있고 읽기·목록·삭제는 못 한다.
# 서버가 털려도 침입자가 이 URL로 **기존 백업을 내려받거나 지울 수 없다.** (덮어쓰기는
# 가능하므로 버킷의 객체 버전 관리를 켜 둔다 — deploy/oci/README.md.)
#
# 무결성: Content-MD5를 같이 보낸다. 전송 중에 한 바이트라도 바뀌면 Object Storage가
# 400으로 거절하므로, 200을 받았다는 것이 곧 "같은 내용이 저장됐다"는 뜻이 된다.
# 쓰기 전용이라 올린 뒤 다시 읽어 비교할 수 없어서 이 방법을 쓴다.
#
# 설정: /etc/menupick/offsite.env (0600, root 소유 — 유닛이 이 단계만 root로 부른다)
#   OFFSITE_PAR_URL=https://objectstorage.<region>.oraclecloud.com/p/<token>/n/<ns>/b/<bucket>/o/
#   OFFSITE_PREFIX=db/            (선택, 기본 db/)
# URL 자체가 자격증명이다. 어디에도 출력하지 않는다.
#
# 설정이 없으면 실패가 아니다 — 경고만 남기고 0으로 끝낸다. 아직 버킷을 만들지 않은
# 서버에서 백업 유닛까지 빨간불이 되면 진짜 백업 실패와 구분할 수 없다.
set -uo pipefail

CONF="${OFFSITE_ENV_FILE:-/etc/menupick/offsite.env}"
BACKUP_DIR="${BACKUP_DIR:-$HOME/backups}"

log() { printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

if [ ! -e "$CONF" ]; then
  log "WARN: $CONF 가 없어 오프사이트 업로드를 건너뛴다 — 설치 절차는 deploy/oci/README.md"
  exit 0
fi
# 있는데 못 읽는 것은 "설정 없음"이 아니라 고장이다. 조용히 건너뛰면 오프사이트가
# 돌고 있다고 믿는 동안 한 벌도 쌓이지 않는다.
[ -r "$CONF" ] || die "$CONF 가 있지만 읽을 수 없다 (실행 사용자: $(id -un))"
set -a
# shellcheck disable=SC1090
. "$CONF"
set +a

url="${OFFSITE_PAR_URL:-}"
prefix="${OFFSITE_PREFIX:-db/}"
if [ -z "$url" ]; then
  log "WARN: OFFSITE_PAR_URL 이 비어 있어 오프사이트 업로드를 건너뛴다"
  exit 0
fi
case "$url" in
  https://*/o/) ;;
  # 끝의 /o/ 가 빠지면 객체 이름이 URL에 붙지 않아 엉뚱한 곳에 쓰거나 404가 난다.
  # URL을 찍지 않고 형식만 알린다.
  *) die "OFFSITE_PAR_URL 형식이 아니다 (https://… 로 시작하고 /o/ 로 끝나야 한다)" ;;
esac
case "$prefix" in
  */|"") ;;
  *) prefix="$prefix/" ;;
esac

# 가장 최근의 **완성된** 백업. .part 는 backup-db.sh가 검증 전에 쓰는 이름이라 제외된다.
latest="$(find "$BACKUP_DIR" -maxdepth 1 -name 'menupick-*.sql.gz' -printf '%T@ %p\n' 2>/dev/null \
  | sort -rn | head -n 1 | cut -d' ' -f2-)"
[ -n "$latest" ] || die "$BACKUP_DIR 에 올릴 백업이 없다"
# 방금 만든 백업이어야 한다. 덤프가 조용히 안 만들어졌는데 며칠 전 파일을 올리고 "성공"하면
# 오프사이트가 낡은 것을 아무도 모른다.
age=$(( $(date +%s) - $(stat -c %Y "$latest") ))
[ "$age" -le 3600 ] || die "최신 백업이 ${age}초 전 것이다 — 이번 덤프가 만들어지지 않은 것 같다"

name="$(basename "$latest")"
md5="$(openssl dgst -md5 -binary "$latest" | openssl base64)"
[ -n "$md5" ] || die "MD5를 계산하지 못했다"

body="$(mktemp)"
trap 'rm -f "$body"' EXIT
http="$(curl -sS -o "$body" -w '%{http_code}' \
    --max-time 300 --retry 3 --retry-delay 5 \
    -X PUT -T "$latest" \
    -H "Content-MD5: $md5" \
    -H 'Content-Type: application/gzip' \
    "${url}${prefix}${name}" 2>/dev/null)"
rc=$?
[ "$rc" -eq 0 ] || die "업로드 실패 (curl exit $rc)"
case "$http" in
  2*) log "오프사이트 업로드 완료: ${prefix}${name} ($(du -h "$latest" | cut -f1), HTTP $http)" ;;
  # 응답 본문은 Object Storage의 오류 설명이다(URL은 들어 있지 않다).
  *)  die "Object Storage가 HTTP $http 를 돌려줬다: $(head -c 300 "$body" 2>/dev/null)" ;;
esac
