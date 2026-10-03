#!/usr/bin/env bash
#
# 떠 있지만 unhealthy인 컨테이너를 다시 세운다
# (deploy/oci/menupick-heal.timer가 2분마다 부른다).
#
# 왜 필요한가: compose의 `restart: unless-stopped`는 **프로세스가 끝났을 때만** 반응한다.
# 앱이 살아는 있는데 요청을 못 받는 상태(스레드 풀 고갈, DB 커넥션 누수, nginx worker 멈춤)는
# `Up (unhealthy)`로 그대로 머문다 — 헬스체크가 빨간불인데 아무도 아무것도 하지 않는 구간이다.
# 2026-10-03 점검에서 확인된 공백이고, 이 서버에는 오케스트레이터가 없어 대신할 것이 없다.
#
# **꺼진 컨테이너는 건드리지 않는다.** 이 서버는 검증이 끝나면 컨테이너를 내려 두는 운영
# 방식이라, "꺼짐"을 고장으로 보고 되살리면 사람이 내린 것을 기계가 계속 되돌린다.
# `docker ps --filter health=unhealthy`는 **실행 중인** 컨테이너만 내놓으므로 그 규칙이
# 조회 자체에 들어 있다.
#
# 세 가지 제동장치:
#   1. **연속 2회 관찰**(STRIKES_REQUIRED). 헬스체크 한 번 튄 것으로 재시작하지 않는다 —
#      특히 mysql은 일시적인 ping 실패가 재시작보다 싸다.
#   2. **재시작 간 쿨다운**(HEAL_COOLDOWN, 기본 10분). 같은 컨테이너를 2분마다 두들기지 않는다.
#   3. **3회 실패 후 포기**(HEAL_MAX_ATTEMPTS). 재시작으로 안 되는 고장(예: DB가 죽어서 앱이
#      unhealthy)을 무한히 되풀이하는 대신 사람을 부른다. 복구되면 카운터가 풀린다.
#
# 모든 조치와 포기는 웹훅으로 알린다(scripts/notify-failure.sh). URL이 비어 있으면 journal에만 남는다.
#
# 환경변수:
#   HEAL_CONTAINERS      — 다룰 컨테이너 이름(공백 구분). 기본은 운영 5개
#   HEAL_COOLDOWN(600)   — 같은 컨테이너를 다시 재시작하기까지의 최소 간격(초)
#   HEAL_MAX_ATTEMPTS(3) — 이 횟수를 넘기면 재시작을 멈추고 사람을 부른다
#   STRIKES_REQUIRED(2)  — 재시작 전에 필요한 연속 unhealthy 관찰 횟수
set -uo pipefail

CONTAINERS="${HEAL_CONTAINERS:-menupick-app menupick-web menupick-mysql menupick-redis menupick-mailpit}"
COOLDOWN="${HEAL_COOLDOWN:-600}"
MAX_ATTEMPTS="${HEAL_MAX_ATTEMPTS:-3}"
STRIKES_REQUIRED="${STRIKES_REQUIRED:-2}"
STATE_DIR="${HEAL_STATE_DIR:-/var/lib/menupick/heal}"
NOTIFY="${HEAL_NOTIFY:-/usr/local/bin/menupick-alert}"

log() { printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }

notify() {
  [ -x "$NOTIFY" ] || { log "WARN: $NOTIFY 가 없어 알리지 못했다"; return 0; }
  "$NOTIFY" --message "$1" >/dev/null 2>&1 \
    || log "WARN: 알림 전송이 실패했다 (조치 자체는 끝났다)"
}

# 상태 파일: <state>/<이름>.strikes  연속 unhealthy 관찰 횟수
#            <state>/<이름>.attempts 재시작 시도 누적(복구되면 0)
#            <state>/<이름>.last     마지막 재시작 시각(epoch)
#            <state>/<이름>.gaveup   포기 알림을 이미 보냈다는 표식
read_num() {
  local file="$1" value
  value="$(cat "$file" 2>/dev/null)"
  case "$value" in
    ''|*[!0-9]*) echo 0 ;;
    *) echo "$value" ;;
  esac
}

mkdir -p "$STATE_DIR" 2>/dev/null

# 실행 중이면서 unhealthy인 것만. 꺼진 것·기동 중(starting)인 것은 여기 안 들어온다.
unhealthy="$(docker ps --filter health=unhealthy --format '{{.Names}}' 2>/dev/null)"
now="$(date +%s)"

for name in $CONTAINERS; do
  strikes_file="$STATE_DIR/$name.strikes"
  attempts_file="$STATE_DIR/$name.attempts"
  last_file="$STATE_DIR/$name.last"
  gaveup_file="$STATE_DIR/$name.gaveup"

  if ! printf '%s\n' "$unhealthy" | grep -qx "$name"; then
    # 지금 unhealthy가 아니다. 셋으로 갈린다 — **무엇을 지우는지가 중요하다.**
    #
    # 재시작 직후에는 반드시 `starting`을 거친다(헬스체크 start_period). 그때 누적 카운터를
    # 지우면 재시작할 때마다 시도 횟수가 0으로 돌아가, 3회 제한과 쿨다운이 영구히 리셋된다.
    # 고치지 않으면 재시작으로 안 되는 고장에서 영원히 4분마다 재시작만 반복하고 사람을
    # 부르지 않는다(2026-10-04 검증에서 실제로 그렇게 동작했다).
    state="$(docker inspect -f '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}no-health{{end}}' "$name" 2>/dev/null || echo missing)"
    case "$state" in
      running/starting)
        # 아직 판정할 수 없다. 연속 관찰만 끊고 누적은 그대로 둔다.
        log "$name 기동 중($state) — 지켜본다"
        rm -f "$strikes_file"
        ;;
      running/healthy|running/no-health)
        if [ "$(read_num "$attempts_file")" -gt 0 ]; then
          # 조치만 알리고 결과를 알리지 않으면, 채널을 보는 사람은 지금 괜찮은지를 알 수 없다.
          log "$name 복구됨 — 카운터를 지운다"
          notify "🟢 MenuPick $name 복구됨 (재시작 후 $state)"
        fi
        rm -f "$strikes_file" "$attempts_file" "$last_file" "$gaveup_file"
        ;;
      *)
        # 꺼졌거나 사라졌다. 사람이 내린 것일 수 있으므로 알리지 않고 카운터만 지운다 —
        # 다음에 다시 떴을 때는 깨끗한 상태에서 센다.
        #
        # 지울 것이 있을 때만 한 줄 남긴다. 컨테이너를 내려 둔 기간에는 이 분기가 2분마다
        # 도는데, 매번 적으면 하루 수백 줄이 쌓여 정작 조치 기록을 가린다.
        if [ -f "$strikes_file" ] || [ -f "$attempts_file" ] \
            || [ -f "$last_file" ] || [ -f "$gaveup_file" ]; then
          log "$name 상태 $state — 대상이 아니므로 카운터를 지운다"
          rm -f "$strikes_file" "$attempts_file" "$last_file" "$gaveup_file"
        fi
        ;;
    esac
    continue
  fi

  strikes="$(( $(read_num "$strikes_file") + 1 ))"
  printf '%s\n' "$strikes" > "$strikes_file"
  attempts="$(read_num "$attempts_file")"
  log "$name unhealthy (연속 ${strikes}회 관찰, 재시작 누적 ${attempts}회)"

  if [ "$strikes" -lt "$STRIKES_REQUIRED" ]; then
    log "$name — 아직 ${STRIKES_REQUIRED}회가 아니라 지켜본다"
    continue
  fi

  if [ "$attempts" -ge "$MAX_ATTEMPTS" ]; then
    # 재시작으로 안 되는 고장이다. 더 두들겨도 상태만 더 나빠진다.
    if [ ! -f "$gaveup_file" ]; then
      log "$name — ${attempts}회 재시작했는데 계속 unhealthy. 재시작을 멈추고 사람을 부른다"
      notify "🔴 MenuPick $name 이 ${attempts}번 재시작해도 unhealthy — 자동 복구를 멈췄다
사람이 봐야 한다. 확인:
  docker inspect $name --format '{{json .State.Health}}'
  journalctl CONTAINER_NAME=$name --since -30min
복구되면 자동으로 다시 감시한다."
      : > "$gaveup_file"
    else
      log "$name — 이미 포기를 알렸다. 조용히 둔다"
    fi
    continue
  fi

  last="$(read_num "$last_file")"
  if [ "$last" -gt 0 ] && [ "$((now - last))" -lt "$COOLDOWN" ]; then
    log "$name — 쿨다운 중($(( COOLDOWN - (now - last) ))초 남음). 재시작하지 않는다"
    continue
  fi

  health="$(docker inspect -f '{{if .State.Health}}{{range $i, $e := .State.Health.Log}}{{if eq $i 0}}{{$e.Output}}{{end}}{{end}}{{end}}' "$name" 2>/dev/null | head -c 300)"
  log "$name 재시작한다"
  if docker restart "$name" >/dev/null 2>&1; then
    attempts="$((attempts + 1))"
    printf '%s\n' "$attempts" > "$attempts_file"
    printf '%s\n' "$now" > "$last_file"
    : > "$strikes_file"
    notify "🟡 MenuPick $name 이 unhealthy라 재시작했다 (누적 ${attempts}/${MAX_ATTEMPTS}회)
헬스체크 출력: ${health:-(없음)}"
  else
    log "ERROR: $name 재시작 실패"
    notify "🔴 MenuPick $name 재시작이 실패했다 — docker가 거부했다. 사람이 봐야 한다."
  fi
done
