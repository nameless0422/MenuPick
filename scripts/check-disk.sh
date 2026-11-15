#!/usr/bin/env bash
#
# 디스크 사용률을 보고 임계값을 넘으면 웹훅으로 알린다
# (deploy/oci/menupick-disk-watch.timer가 30분마다 부른다).
#
# 왜 필요한가: 2026-10-03에 배포마다 쌓인 이미지 72개가 9.2GB를 먹어 루트가 77%였고,
# 2026-11에는 23GB까지 찼는데 둘 다 사람이 우연히 `df`를 보고서야 알았다. 이 서버는
# 루트 하나(30GB)에 MySQL 데이터·도커 이미지·journal·백업이 전부 올라가 있어서, 가득 차면
# MySQL이 쓰기를 못 해 먼저 죽고 백업도 같이 실패한다. 꽉 차기 **전에** 알아야 한다.
#
# 알림 규칙 — 시끄러우면 사람은 알림을 끈다:
#   - 단계는 ok < warn(80%) < crit(90%) 셋이다.
#   - 단계가 **오를 때** 바로 알린다.
#   - 같은 단계에 머무르면 쿨다운(6시간)마다 한 번만 다시 알린다.
#   - ok로 **돌아오면** 한 번 알린다. 정리했는데 확인 알림이 없으면 감시가 죽은 것과 구분이 안 된다.
#   - 단계가 내려가기만 한 경우(crit → warn)는 알리지 않는다. 좋아지는 중이다.
#
# 블록과 inode를 둘 다 본다. inode가 바닥나면 공간이 남아도 파일을 못 만든다.
#
# 환경변수:
#   DISK_WATCH_PATHS("/ /var/lib/docker /home/opc/backups") — 볼 경로. 같은 파일시스템은 한 번만 본다
#   DISK_WATCH_WARN(80) DISK_WATCH_CRIT(90) — 퍼센트
#   DISK_WATCH_COOLDOWN(21600) — 같은 단계 재알림 간격(초)
#   DISK_WATCH_STATE_DIR(/var/lib/menupick) DISK_WATCH_NOTIFY(/usr/local/bin/menupick-alert)
set -uo pipefail

PATHS="${DISK_WATCH_PATHS:-/ /var/lib/docker /home/opc/backups}"
WARN="${DISK_WATCH_WARN:-80}"
CRIT="${DISK_WATCH_CRIT:-90}"
COOLDOWN="${DISK_WATCH_COOLDOWN:-21600}"
STATE_DIR="${DISK_WATCH_STATE_DIR:-/var/lib/menupick}"
NOTIFY="${DISK_WATCH_NOTIFY:-/usr/local/bin/menupick-alert}"

log() { printf '%s  %s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')" "$*"; }

rank() {
  case "$1" in
    crit) echo 2 ;;
    warn) echo 1 ;;
    *)    echo 0 ;;
  esac
}

level_of() {
  if [ "$1" -ge "$CRIT" ]; then echo crit
  elif [ "$1" -ge "$WARN" ]; then echo warn
  else echo ok
  fi
}

# 무엇이 차지하고 있는지 알림에 같이 싣는다. 받는 사람이 서버에 들어가기 전에
# "이미지 정리인지, 로그인지, DB인지" 짐작할 수 있어야 한다. 실패해도 알림은 나간다.
hints() {
  if command -v docker >/dev/null 2>&1; then
    docker system df --format '{{.Type}}: {{.Size}} (회수 가능 {{.Reclaimable}})' 2>/dev/null
  fi
  journalctl --disk-usage 2>/dev/null | sed 's/^/journal: /'
  du -sh /home/opc/backups 2>/dev/null | awk '{print "backups: " $1}'
}

mkdir -p "$STATE_DIR" 2>/dev/null
now="$(date +%s)"
seen=""
status=0

for path in $PATHS; do
  [ -e "$path" ] || continue
  # 같은 파일시스템을 여러 번 알리지 않는다. 이 서버는 전부 루트 하나다.
  mount="$(df -P "$path" 2>/dev/null | awk 'NR==2 {print $6}')"
  [ -n "$mount" ] || { log "WARN: $path 의 사용률을 읽지 못했다"; continue; }
  case " $seen " in *" $mount "*) continue ;; esac
  seen="$seen $mount"

  block="$(df -P "$mount" | awk 'NR==2 {gsub("%","",$5); print $5}')"
  inode="$(df -Pi "$mount" 2>/dev/null | awk 'NR==2 {gsub("%","",$5); print $5}')"
  # 일부 파일시스템(btrfs 등)은 inode 사용률을 '-'로 준다. 그때는 블록만 본다.
  case "$inode" in ''|*[!0-9]*) inode=0 ;; esac
  case "$block" in ''|*[!0-9]*) log "WARN: $mount 의 사용률이 숫자가 아니다: $block"; continue ;; esac

  worst=$block
  [ "$inode" -gt "$worst" ] && worst=$inode
  level="$(level_of "$worst")"
  size="$(df -Ph "$mount" | awk 'NR==2 {print $3 " / " $2 " 사용, " $4 " 남음"}')"
  log "$mount 블록 ${block}% · inode ${inode}% → $level"

  key="$(printf '%s' "$mount" | tr -c 'A-Za-z0-9' '_')"
  state="$STATE_DIR/disk-watch.$key"
  prev=ok
  last=0
  if [ -r "$state" ]; then
    read -r prev last < "$state" || true
    case "$prev" in ok|warn|crit) ;; *) prev=ok ;; esac
    case "$last" in ''|*[!0-9]*) last=0 ;; esac
  fi

  send=no
  if [ "$(rank "$level")" -gt "$(rank "$prev")" ]; then
    send=yes
  elif [ "$level" != ok ] && [ "$level" = "$prev" ] && [ $((now - last)) -ge "$COOLDOWN" ]; then
    send=yes
  elif [ "$level" = ok ] && [ "$prev" != ok ]; then
    send=yes
  fi

  if [ "$send" = no ]; then
    # 내려가는 중이면 단계만 기록하고 알림 시각은 그대로 둔다.
    printf '%s %s\n' "$level" "$last" > "$state"
    continue
  fi

  case "$level" in
    crit) icon="🔴"; title="디스크 위험" ;;
    warn) icon="🟠"; title="디스크 경고" ;;
    *)    icon="🟢"; title="디스크 회복" ;;
  esac
  msg="$icon MenuPick $title: $mount 블록 ${block}% · inode ${inode}% (경고 ${WARN}% / 위험 ${CRIT}%)
$size"
  if [ "$level" != ok ]; then
    msg="$msg

$(hints)
정리: deploy/oci/README.md '디스크' 절 — 볼륨은 지우지 말 것"
  fi

  if [ -x "$NOTIFY" ] && "$NOTIFY" --message "$msg"; then
    printf '%s %s\n' "$level" "$now" > "$state"
  else
    # 보내지 못했으면 단계를 갱신하지 않는다 — 다음 회차에 다시 오른 것으로 보고 재시도한다.
    log "ERROR: $mount 알림을 보내지 못했다 ($NOTIFY)"
    status=1
  fi
done

exit "$status"
