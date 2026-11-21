#!/usr/bin/env bash
# 부하 중 MySQL이 무엇을 기다리는지 1초 간격으로 샘플링한다(2026-11-21 4차 회차, D-089).
# usage: bash scripts/k6/server/mysql-diag.sh <초>
#
# 실행 중 구문·상태, 행 잠금 대기를 모아 빈도순으로 보여 주고, 끝에 InnoDB 지표와 내구성 설정을 찍는다.
# COMMIT/"waiting for handler commit"이 많고 잠금 대기가 0이면 커밋 fsync를 기다리는 것이다.
set -u
sql() {
  docker exec -i menupick-mysql sh -c \
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql --default-character-set=utf8mb4 -uroot "$MYSQL_DATABASE"'
}
dir=$(mktemp -d)
trap 'rm -rf "$dir"' EXIT
end=$(( $(date +%s) + ${1:?초를 준다} ))
while [ "$(date +%s)" -lt "$end" ]; do
  echo "SELECT COALESCE(STATE,'-'), COMMAND FROM information_schema.PROCESSLIST WHERE USER<>'system user' AND COMMAND<>'Daemon' AND ID<>CONNECTION_ID();" \
    | sql 2>/dev/null | tail -n +2 >> "$dir/proc"
  echo "SELECT COUNT(*) FROM performance_schema.data_lock_waits;" | sql 2>/dev/null | tail -1 >> "$dir/lock"
  echo "SELECT LEFT(REGEXP_REPLACE(INFO,'[0-9]+','N'),90) FROM information_schema.PROCESSLIST WHERE COMMAND='Query' AND INFO IS NOT NULL AND ID<>CONNECTION_ID();" \
    | sql 2>/dev/null | tail -n +2 >> "$dir/sql"
  sleep 1
done
echo "== processlist 상태(횟수)"; sort "$dir/proc" | uniq -c | sort -rn | head -12
echo "== 샘플당 잠금 대기";      sort -n "$dir/lock" | uniq -c | sort -k2 -n | tail -8
echo "== 실행 중 구문";          sort "$dir/sql" | uniq -c | sort -rn | head -12
echo "== innodb"
echo "SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_row_lock_waits','Innodb_row_lock_time_avg','Threads_running','Innodb_buffer_pool_reads','Innodb_log_waits','Innodb_os_log_fsyncs');" | sql
echo "SHOW VARIABLES WHERE Variable_name IN ('innodb_flush_log_at_trx_commit','sync_binlog','log_bin','innodb_buffer_pool_size');" | sql
