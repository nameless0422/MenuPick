#!/usr/bin/env bash
#
# 앱 컨테이너의 ERROR 로그를 주기적으로 세어 웹훅으로 알린다
# (deploy/oci/menupick-error-watch.timer가 15분마다 부른다).
#
# 왜 systemd OnFailure로는 부족한가: 앱 안에서 도는 스케줄러 세 개(집단 통계 04:10,
# 방 정리 04:20, 탈퇴 정리)는 실패를 **잡아서 로그만 남기고 다음 회차를 유지한다**.
# 유닛이 실패하는 것이 아니므로 OnFailure가 걸릴 자리가 없다. 5xx 응답도 마찬가지로
# 컨테이너 로그에만 남는다. 그래서 로그를 직접 본다.
#
# 전제: 도커 로그가 journald로 간다(docker-compose.prod.yml의 x-logging). json-file이면
# 이 스크립트는 아무것도 못 찾고 조용히 끝난다 — 그 상태를 알 수 있게 그때도 한 줄 남긴다.
#
# 컨테이너가 꺼져 있으면 ERROR가 없으므로 아무 일도 하지 않는다. 사용자가 검증 후
# 컨테이너를 내려 두는 운영 방식이라, "꺼져 있다"를 장애로 알리지 않는 것이 중요하다.
#
# 환경변수:
#   ERROR_WATCH_SINCE(16min)  — 볼 구간. 타이머 주기보다 조금 넓게 잡아 경계를 흘리지 않는다
#   ERROR_WATCH_COOLDOWN(3600) — 같은 알림을 다시 보내기까지의 최소 간격(초)
#   ERROR_WATCH_CONTAINER(menupick-app)
set -uo pipefail

CONTAINER="${ERROR_WATCH_CONTAINER:-menupick-app}"
SINCE="${ERROR_WATCH_SINCE:-16min}"
COOLDOWN="${ERROR_WATCH_COOLDOWN:-3600}"
STATE_DIR="${ERROR_WATCH_STATE_DIR:-/var/lib/menupick}"
STAMP="$STATE_DIR/error-watch.last"
NOTIFY="${ERROR_WATCH_NOTIFY:-/usr/local/bin/menupick-alert}"

# ERROR 줄의 모양. **줄 어딘가에 ERROR가 있는가**가 아니라 **레벨 칸이 ERROR인가**를 본다.
#
# 전에는 ' ERROR ' 부분 문자열로 찾았다. 그런데 접근 로그는 요청의 파라미터 이름을 찍고
# (AccessLogFilter), 그 이름은 인증 없이도 누구나 정한다 — `?x%20ERROR%20y=1` 한 번이면
# INFO 줄에 " ERROR "가 들어가 이 감시에 걸렸고, 그 줄이 그대로 운영 채널로 나갔다.
# 즉 아무나 가짜 장애 알림을 띄우고 본문까지 고를 수 있었다(2026-11-13 점검).
#
# Boot의 줄은 `시각 레벨 [traceId,userId] PID --- ...` 순서다. 시각으로 시작하고, 레벨이
# 바로 뒤에 오며, 레벨 뒤에 `[`가 따라온다(application.yml의 logging.pattern.level).
# 이 세 가지를 모두 요구하면 메시지 안의 문자열은 여기에 닿지 못한다. 줄바꿈으로 줄 머리를
# 흉내 내는 것은 앱이 메시지의 줄바꿈을 이스케이프해서 막는다(CrlfSafeMessageConverter).
ERROR_LINE='^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9:.]+(Z|[+-][0-9]{2}:?[0-9]{2})? +ERROR +\['

log() { printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }

lines="$(journalctl CONTAINER_NAME="$CONTAINER" --since "-$SINCE" --no-pager -o cat 2>/dev/null \
    | grep -E "$ERROR_LINE" || true)"
count="$(printf '%s' "$lines" | grep -c . || true)"

if [ "${count:-0}" -eq 0 ]; then
  # 조용한 것이 정상이다. 한 줄은 남긴다 — 아무 기록도 없으면 "감시가 도는지"를
  # 확인할 방법이 없고, 그러면 침묵이 안전인지 고장인지 구분되지 않는다.
  log "최근 $SINCE 동안 $CONTAINER 의 ERROR 0건"
  exit 0
fi

log "최근 $SINCE 동안 $CONTAINER 의 ERROR ${count}건"

now="$(date +%s)"
if [ -r "$STAMP" ]; then
  last="$(cat "$STAMP" 2>/dev/null || echo 0)"
  case "$last" in
    ''|*[!0-9]*) last=0 ;;
  esac
  if [ $((now - last)) -lt "$COOLDOWN" ]; then
    # 같은 장애가 계속 나는 동안 15분마다 알리면 채널이 막히고, 그러면 알림 자체를 끄게 된다.
    log "쿨다운 중이라 알리지 않는다 (${COOLDOWN}초 간격, journal에는 남는다)"
    exit 0
  fi
fi

sample="$(printf '%s\n' "$lines" | head -n 5 | cut -c1-300)"
if [ ! -x "$NOTIFY" ]; then
  log "ERROR: $NOTIFY 가 없어 알리지 못했다"
  exit 1
fi

if "$NOTIFY" --message "🟠 MenuPick 앱 ERROR ${count}건 (최근 $SINCE)
컨테이너: $CONTAINER
확인: journalctl CONTAINER_NAME=$CONTAINER --since -1h | grep ERROR

$sample"; then
  mkdir -p "$STATE_DIR" 2>/dev/null
  printf '%s\n' "$now" > "$STAMP"
else
  # 전송이 실패하면 stamp를 갱신하지 않는다 — 다음 회차에 다시 시도해야 한다.
  log "ERROR: 알림 전송이 실패해 쿨다운을 갱신하지 않는다"
  exit 1
fi
