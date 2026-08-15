#!/usr/bin/env bash
# 부하 테스트 회차 동안 관리 포트(actuator)의 지표를 주기적으로 폴링해 CSV로 남긴다.
# (docs/LoadTestPlan.md 6절 "동시에 봐야 할 지표", 9절 산출물 목록)
#
# k6가 보는 건 "밖에서 잰 응답 시간"뿐이다. 왜 느려졌는지(커넥션 풀 고갈인지, GC인지,
# 메일 큐 적체인지)는 앱 안쪽 지표를 같은 타임라인에 놓고 봐야 알 수 있다. 이 스크립트는
# k6 실행과 나란히 백그라운드로 돌려서, 이후 CSV의 timestamp를 k6 결과와 맞춰본다.
#
# 관리 포트는 컨테이너 루프백(127.0.0.1)에만 바인딩되고 compose가 publish하지 않는다
# (docs/DecisionLog.md D-027). 그래서 기본 접근 경로는 딱 하나, docker exec뿐이다:
#
#   docker exec menupick-app curl -s localhost:9090/actuator/metrics/<name>
#
# 사용법 (docker exec 모드, 기본값):
#   bash scripts/k6/metrics-poll.sh
#   CONTAINER_NAME=menupick-app MANAGEMENT_PORT=9090 POLL_INTERVAL=5 \
#     OUTPUT_FILE=results/stress-2026-08-16.csv bash scripts/k6/metrics-poll.sh
#
# 사용법 (직접 curl 모드 — 이미 컨테이너 안에 있거나 포트를 직접 열어둔 경우):
#   DIRECT_CURL=true MANAGEMENT_HOST=127.0.0.1 MANAGEMENT_PORT=9090 \
#     bash scripts/k6/metrics-poll.sh
#
# 회차 시간을 미리 아는 stress/soak이라면 DURATION_SECONDS로 자동 종료시킬 수 있다.
# 모르면(또는 k6와 정확히 같이 끝내고 싶으면) 비워두고 k6가 끝난 뒤 Ctrl-C로 멈춘다.
#
# 환경변수:
#   CONTAINER_NAME    관리 포트를 가진 컨테이너 이름 (기본: menupick-app)
#   MANAGEMENT_PORT   actuator 관리 포트 (기본: 9090, application-prod.yml MANAGEMENT_SERVER_PORT와 동일해야 함)
#   MANAGEMENT_HOST   DIRECT_CURL=true일 때만 사용하는 접속 호스트 (기본: localhost)
#   POLL_INTERVAL     폴링 주기(초) (기본: 5)
#   OUTPUT_FILE       CSV 출력 경로 (기본: metrics-poll-<시작시각>.csv)
#   DURATION_SECONDS  이 시간(초)이 지나면 자동 종료. 비우면 Ctrl-C까지 무한 폴링
#   DIRECT_CURL       true면 docker exec 없이 관리 포트를 직접 curl (기본: false)
#   CURL_TIMEOUT      개별 curl 호출의 최대 대기 시간(초) (기본: 3)

# set -e를 쓰지 않는다: 지표 하나가 없거나(예: 메일을 한 번도 안 보내 executor.queued가
# 없는 상태) 폴링 한 번이 순간적으로 실패해도 soak처럼 1시간짜리 회차 전체를 죽이면 안 된다.
# 대신 실패는 함수 단위에서 잡아 빈 칸으로 기록하고, 회차 시작 전 사전 점검에서만 강하게 실패시킨다.
set -uo pipefail

CONTAINER_NAME="${CONTAINER_NAME:-menupick-app}"
MANAGEMENT_PORT="${MANAGEMENT_PORT:-9090}"
MANAGEMENT_HOST="${MANAGEMENT_HOST:-localhost}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"
OUTPUT_FILE="${OUTPUT_FILE:-metrics-poll-$(date -u +%Y%m%dT%H%M%SZ).csv}"
DURATION_SECONDS="${DURATION_SECONDS:-}"
DIRECT_CURL="${DIRECT_CURL:-false}"
CURL_TIMEOUT="${CURL_TIMEOUT:-3}"

# jq가 있으면 정식으로 파싱한다. 없으면 grep/sed로 대체하는데, 부하 테스트를 실제로 쏘는
# 별도 VM(LoadTestPlan.md 7절 "k6를 대상 VM에서 돌리면 안 된다")은 최소 설치일 가능성이 높아
# jq가 없을 수 있다 — 그 경우에도 스크립트가 그냥 죽어버리면 회차를 통째로 못 건진다.
if command -v jq >/dev/null 2>&1; then
  HAVE_JQ=1
else
  HAVE_JQ=0
  echo "참고: jq가 없어 grep/sed 기반 파싱으로 대체한다 (actuator JSON의 필드 순서가" \
       "바뀌면 깨질 수 있음 — 가능하면 jq 설치를 권장)." >&2
fi

# 중단(Ctrl-C 등) 신호를 받아도 이미 파일에 쓰인 행들은 완결된 CSV 줄이라 그대로 유효하다.
# trap은 "지금부터 더 안 쓴다"만 보장하면 되므로 메시지만 남기고 정상 종료(0)한다 —
# 부하 테스트 도중 의도적으로 멈춘 것이지 실패가 아니기 때문.
_on_interrupt() {
  echo "" >&2
  echo "중단 신호 수신 — 폴링을 멈춘다. 지금까지 기록된 $OUTPUT_FILE 은 유효한 CSV다." >&2
  exit 0
}
trap _on_interrupt INT TERM

# actuator metrics 응답 하나를 가져온다. 두 가지 실패를 구분해서 다룬다:
#   1) 이 함수 자체가 실패(nonzero return) — docker exec가 컨테이너에 닿지 못했거나
#      curl이 타임아웃/연결거부. "관리 포트에 접근할 수 없다"는 인프라 문제라
#      호출부에서 사전 점검 시 강하게 실패시키는 근거로 쓴다.
#   2) 함수는 성공(exit 0)했지만 JSON에 measurements가 없음 — 그 지표가 아직 없을 뿐
#      (예: 메일을 한 번도 안 보낸 executor.queued)이라 extract_stat이 빈 문자열을 낸다.
# docker exec 모드는 컨테이너 안에서 127.0.0.1로 붙는다 — Dockerfile의 HEALTHCHECK와 같은 이유로,
# 컨테이너의 /etc/hosts가 localhost를 ::1(IPv6)로도 풀 수 있고 관리 포트는 IPv4 루프백에만
# 바인딩돼 있어 localhost를 쓰면 IPv6 시도가 먼저 실패하고 나서야 IPv4로 넘어가는 헛걸음이 생긴다.
fetch_metric() {
  local metric="$1" tag="$2" path json rc

  path="/actuator/metrics/${metric}"
  if [ -n "$tag" ]; then
    path="${path}?tag=${tag}"
  fi

  if [ "$DIRECT_CURL" = "true" ]; then
    json="$(curl -s -m "$CURL_TIMEOUT" "http://${MANAGEMENT_HOST}:${MANAGEMENT_PORT}${path}" 2>/dev/null)"
    rc=$?
  else
    json="$(docker exec "$CONTAINER_NAME" curl -s -m "$CURL_TIMEOUT" "http://127.0.0.1:${MANAGEMENT_PORT}${path}" 2>/dev/null)"
    rc=$?
  fi

  if [ $rc -ne 0 ]; then
    echo "경고: $(date -u +%Y-%m-%dT%H:%M:%SZ) ${metric} 폴링 실패(exit ${rc}) — 이번 행은 빈 값으로 기록한다." >&2
  fi

  printf '%s' "$json"
  return $rc
}

# actuator measurements 배열에서 특정 statistic(VALUE/COUNT/TOTAL_TIME)의 값을 뽑는다.
# 없으면(지표 자체가 없거나 404 응답이거나) 빈 문자열을 낸다 — 호출부가 그대로 CSV 빈 칸에 쓴다.
extract_stat() {
  local json="$1" stat="$2"

  if [ -z "$json" ]; then
    return
  fi

  if [ "$HAVE_JQ" = "1" ]; then
    printf '%s' "$json" \
      | jq -r --arg s "$stat" '([.measurements[]? | select(.statistic == $s) | .value] | first) // empty' 2>/dev/null
    return
  fi

  # jq 없을 때의 대체 경로: 정식 JSON 파싱이 아니라, Spring Boot Actuator가 항상
  # {"statistic":"X","value":Y} 순서로 내보내는 것에 기대는 편법이다. 필드 순서가
  # 바뀌면(스프링 부트 버전이 올라가는 등) 조용히 빈 값만 나오게 되니 주기적으로
  # jq 경로와 결과를 한 번씩 대조해 보는 편이 안전하다.
  printf '%s' "$json" \
    | grep -oE "\"statistic\":\"${stat}\",\"value\":[-0-9.eE+]+" \
    | head -1 \
    | grep -oE '[-0-9.eE+]+$'
}

CSV_HEADER='timestamp,hikaricp_connections_pending,hikaricp_connections_active,jvm_memory_used_heap_bytes,jvm_gc_pause_count,jvm_gc_pause_total_time_seconds,http_server_requests_count,http_server_requests_total_time_seconds,executor_queued_mailTaskExecutor'

# 한 번의 폴링 = 한 행. LoadTestPlan.md 6절에 나열된 지표를 그대로 따른다.
# jvm.gc.pause와 http.server.requests는 통계(statistic)가 여럿이라(COUNT, TOTAL_TIME)
# JSON은 한 번만 받고 그 안에서 두 값을 각각 뽑는다 — curl 왕복을 아끼기 위함이자,
# 두 값이 같은 순간의 스냅샷이어야 TOTAL_TIME/COUNT로 평균을 구하는 게 의미가 있기 때문이다.
poll_once() {
  local ts json
  local hik_pending hik_active heap_used gc_count gc_total http_count http_total mail_queued

  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

  json="$(fetch_metric hikaricp.connections.pending "")"
  hik_pending="$(extract_stat "$json" VALUE)"

  json="$(fetch_metric hikaricp.connections.active "")"
  hik_active="$(extract_stat "$json" VALUE)"

  json="$(fetch_metric jvm.memory.used "area:heap")"
  heap_used="$(extract_stat "$json" VALUE)"

  json="$(fetch_metric jvm.gc.pause "")"
  gc_count="$(extract_stat "$json" COUNT)"
  gc_total="$(extract_stat "$json" TOTAL_TIME)"

  json="$(fetch_metric http.server.requests "")"
  http_count="$(extract_stat "$json" COUNT)"
  http_total="$(extract_stat "$json" TOTAL_TIME)"

  # 메일을 한 번도 안 보냈으면 이 지표 자체가 아직 없다(404) — 로그인 시나리오가 아니면
  # 대부분의 회차에서 이 칸은 계속 빈 채로 남는 게 정상이다(LoadTestPlan.md 6절 표 참고).
  json="$(fetch_metric executor.queued "name:mailTaskExecutor")"
  mail_queued="$(extract_stat "$json" VALUE)"

  printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
    "$ts" "$hik_pending" "$hik_active" "$heap_used" \
    "$gc_count" "$gc_total" "$http_count" "$http_total" "$mail_queued" >> "$OUTPUT_FILE"
}

# 사전 점검: 본 폴링을 시작하기 전에 관리 포트에 한 번은 닿는지 확인한다.
# 여기서 실패하면 설정(컨테이너 이름/포트/모드)이 틀렸다는 뜻이라, 그대로 폴링을
# 시작해봐야 처음부터 끝까지 빈 칸뿐인 CSV만 남는다 — 회차를 낭비하기 전에 여기서 멈춘다.
# (반면 본 루프 중간의 개별 실패는 fetch_metric의 경고만 남기고 계속 돈다 — 파일 상단 주석 참고.)
echo "사전 점검: 관리 포트 접근 확인 중... (mode=$([ "$DIRECT_CURL" = "true" ] && echo direct || echo docker-exec))" >&2
_preflight_json="$(fetch_metric hikaricp.connections.active "")"
_preflight_rc=$?
if [ $_preflight_rc -ne 0 ]; then
  echo "오류: 관리 포트에 접근할 수 없다 (exit ${_preflight_rc})." >&2
  echo "  설정: DIRECT_CURL=${DIRECT_CURL} CONTAINER_NAME=${CONTAINER_NAME} MANAGEMENT_HOST=${MANAGEMENT_HOST} MANAGEMENT_PORT=${MANAGEMENT_PORT}" >&2
  if [ "$DIRECT_CURL" = "true" ]; then
    echo "  확인: 이 머신에서 ${MANAGEMENT_HOST}:${MANAGEMENT_PORT}가 실제로 열려 있는가 (관리 포트는 기본적으로 컨테이너 루프백에만 바인딩됨 — docs/DecisionLog.md D-027)" >&2
  else
    echo "  확인: docker ps --filter name=${CONTAINER_NAME} 로 컨테이너가 떠 있고 이름이 맞는지 확인하라" >&2
  fi
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT_FILE")" 2>/dev/null || true
printf '%s\n' "$CSV_HEADER" > "$OUTPUT_FILE"

echo "폴링 시작: interval=${POLL_INTERVAL}s output=${OUTPUT_FILE}$([ -n "$DURATION_SECONDS" ] && echo " duration=${DURATION_SECONDS}s")" >&2

_start_epoch=$(date +%s)
while true; do
  poll_once

  if [ -n "$DURATION_SECONDS" ]; then
    _now_epoch=$(date +%s)
    if [ $(( _now_epoch - _start_epoch )) -ge "$DURATION_SECONDS" ]; then
      echo "지정된 DURATION_SECONDS(${DURATION_SECONDS}s) 경과 — 폴링 종료." >&2
      break
    fi
  fi

  sleep "$POLL_INTERVAL"
done
