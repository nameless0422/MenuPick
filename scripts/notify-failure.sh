#!/usr/bin/env bash
#
# systemd 유닛 실패를 웹훅으로 알린다. `OnFailure=menupick-alert@%n.service`가 부른다.
#
# 왜 필요한가: 2026-09-30·10-01 백업이 두 번 연속 실패했는데(컨테이너가 꺼져 있어
# `menupick-mysql 가 healthy가 아니다`), 그 사실은 `journalctl`에만 남아 아무도 몰랐다.
# 마지막 성공 백업이 10-01이라는 것은 10-02에 사람이 직접 열어 보고서야 알았다.
# 백업에서 가장 무서운 것은 실패가 아니라 **아무도 모르는 실패**다.
#
# 설계 원칙 세 가지:
#   1. **알림 스크립트는 조용히 죽지 않는다.** set -e를 쓰지 않고 단계마다 직접 처리한다 —
#      중간에서 멈추면 알리려던 실패가 두 번 묻힌다.
#   2. **웹훅 URL이 없으면 실패가 아니다.** 아직 URL을 발급하지 않은 서버에서 알림 유닛까지
#      빨간불이 되면, 진짜 실패와 "알림 미설정"을 구분할 수 없다. 경고만 남기고 0으로 끝낸다.
#   3. **URL은 어디에도 출력하지 않는다.** 웹훅 URL 자체가 자격증명이다(그것만 있으면 누구나
#      그 채널에 글을 쓴다). journal에도 남기지 않는다.
#
# 설정: /etc/menupick/alert.env (0600, root 소유)
#   ALERT_WEBHOOK_URL=https://discord.com/api/webhooks/...
#   ALERT_WEBHOOK_FORMAT=discord|slack|raw   (기본 discord)
#
# 사용:
#   menupick-alert menupick-backup.service   # 실패한 유닛 알림
#   menupick-alert --test                    # 설치 확인용 한 줄 발송
#   menupick-alert --message "임의 문구"      # 다른 스크립트가 쓰는 경로
set -uo pipefail

CONF="${ALERT_ENV_FILE:-/etc/menupick/alert.env}"
JOURNAL_LINES="${ALERT_JOURNAL_LINES:-12}"
# 디스코드 본문 상한이 2000자다. 여유를 두고 자른다 — 자르는 위치는 jq가 문자 단위로
# 잡으므로 UTF-8 한글이 깨지지 않는다(바이트로 자르면 깨진 문자가 들어가 JSON이 거부된다).
MAX_CHARS="${ALERT_MAX_CHARS:-1800}"

log() { printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }

# 값처럼 생긴 자격증명을 가린다. 지금 보내는 내용(우리 스크립트의 출력, 앱 로그 꼬리)에는
# 자격증명이 없지만, 언젠가 환경변수를 그대로 찍는 코드가 섞여 들어오면 그게 채팅방으로
# 나간다. 그래서 journal 꼬리만이 아니라 **보내는 모든 문구**가 이 함수를 거친다.
redact() {
  sed -E 's/(([A-Za-z_]*(PASSWORD|PASSWD|SECRET|TOKEN|APIKEY|API_KEY|WEBHOOK|KEY)[A-Za-z_]*)[[:space:]]*[=:][[:space:]]*)[^[:space:]"]+/\1***/g'
}

send() {
  local text
  text="$(printf '%s' "$1" | redact)"
  local format="${ALERT_WEBHOOK_FORMAT:-discord}"
  local url="${ALERT_WEBHOOK_URL:-}"
  local payload body http

  if [ -z "$url" ]; then
    log "WARN: ALERT_WEBHOOK_URL 이 비어 있어 알림을 보내지 않는다"
    return 0
  fi

  local key
  case "$format" in
    discord) key=content ;;
    slack)   key=text ;;
    raw)     key=message ;;
    *)
      log "ERROR: 알 수 없는 ALERT_WEBHOOK_FORMAT=$format"
      return 1
      ;;
  esac

  # 본문을 손으로 이스케이프하지 않는다. 메시지에는 journal 꼬리가 들어가고 거기에는
  # 따옴표·역슬래시·제어문자가 뭐든 들어올 수 있다 — 하나라도 놓치면 알림이 조용히
  # 400으로 떨어진다. 자르기도 여기서 한다: 둘 다 문자 단위라 UTF-8 한글이 깨지지 않는다
  # (바이트로 자르면 반쪽 문자가 들어가 JSON 자체가 거부된다).
  if command -v jq >/dev/null 2>&1; then
    payload="$(jq -n --arg t "$text" --arg k "$key" --argjson n "$MAX_CHARS"         '{($k): ($t[0:$n])}')"
  elif command -v python3 >/dev/null 2>&1; then
    payload="$(ALERT_TEXT="$text" ALERT_KEY="$key" ALERT_MAX="$MAX_CHARS" python3 -c         'import json,os; print(json.dumps({os.environ["ALERT_KEY"]: os.environ["ALERT_TEXT"][:int(os.environ["ALERT_MAX"])]}))')"
  else
    log "ERROR: jq도 python3도 없어 알림 본문을 만들 수 없다"
    return 1
  fi
  if [ -z "$payload" ]; then
    log "ERROR: 알림 본문을 만들지 못했다"
    return 1
  fi

  body="$(mktemp)"
  # --max-time: 알림 때문에 유닛이 오래 매달리지 않게 한다.
  # URL은 인자로 넘기되 출력하지 않는다 — 실패해도 HTTP 코드만 남긴다.
  http="$(curl -sS -o "$body" -w '%{http_code}' \
      --max-time 10 --retry 2 --retry-delay 2 \
      -H 'Content-Type: application/json' \
      -X POST -d "$payload" "$url" 2>/dev/null)"
  local curl_status=$?
  if [ "$curl_status" -ne 0 ]; then
    log "ERROR: 웹훅 전송 실패 (curl exit $curl_status)"
    rm -f "$body"
    return 1
  fi
  case "$http" in
    2*) log "알림 전송 완료 (HTTP $http)"; rm -f "$body"; return 0 ;;
    *)
      # 응답 본문은 디스코드의 오류 설명이라 남기는 편이 유용하다(URL은 들어 있지 않다).
      log "ERROR: 웹훅이 HTTP $http 를 돌려줬다: $(head -c 200 "$body" 2>/dev/null)"
      rm -f "$body"
      return 1
      ;;
  esac
}

if [ ! -r "$CONF" ]; then
  log "WARN: $CONF 가 없어 알림을 보내지 않는다 (대상: ${1:-?}) — 설치 절차는 deploy/oci/README.md"
  exit 0
fi
set -a
# shellcheck disable=SC1090
. "$CONF"
set +a

HOST="$(hostname -s 2>/dev/null || echo unknown)"
NOW_KST="$(TZ=Asia/Seoul date '+%Y-%m-%d %H:%M:%S KST')"
NOW_UTC="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

case "${1:-}" in
  --test)
    send "✅ MenuPick 알림 점검
서버: $HOST · $NOW_KST (UTC $NOW_UTC)
이 줄이 보이면 실패 알림 경로가 살아 있다."
    exit $?
    ;;
  --message)
    shift
    [ $# -gt 0 ] || { log "ERROR: --message 뒤에 문구가 없다"; exit 1; }
    send "$*"
    exit $?
    ;;
  "")
    log "ERROR: 알릴 유닛 이름이 없다 (사용법: menupick-alert <unit>|--test|--message <문구>)"
    exit 1
    ;;
esac

UNIT="$1"
RESULT="$(systemctl show -p Result --value "$UNIT" 2>/dev/null)"
EXIT_CODE="$(systemctl show -p ExecMainStatus --value "$UNIT" 2>/dev/null)"
TAIL="$(journalctl -u "$UNIT" -n "$JOURNAL_LINES" --no-pager -o cat 2>/dev/null)"

send "🔴 MenuPick 유닛 실패: $UNIT
서버: $HOST · $NOW_KST (UTC $NOW_UTC)
결과: ${RESULT:-unknown} (exit ${EXIT_CODE:-?})
확인: journalctl -u $UNIT -n 50

${TAIL:-(journal 출력 없음)}"
