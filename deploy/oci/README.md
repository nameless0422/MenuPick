# OCI 서버 전용 구성

운영 서버(`opc@146.56.116.44`, Oracle Linux 9 / aarch64)에만 쓰이는 파일들이다.
**여기가 원본이고, 서버에 있는 것은 배포된 사본이다.**

## 왜 리포에 두는가

2026-08-28에 `nginx-tls.conf`와 `docker-compose.oci.yml`을 서버에서 직접 고쳤다.
`:80 → https` 강제 리다이렉트, 포트 중복 제거, `AUTH_COOKIE_SECURE` 오버라이드 제거가
그 결과다. 그런데 두 파일은 **버전 관리 밖에 있었다.** 인스턴스를 재생성하면 그 수정이
전부 사라지고, 무엇을 왜 그렇게 해 뒀는지는 `docs/DecisionLog.md`의 D-034 한 항목에만
남는다. 실제로 2026-08-19에 인스턴스가 재생성되며 DB 데이터가 통째로 사라진 전례가 있다.

그래서 서버에서 손으로 고치던 것들을 여기로 옮겼다. 앞으로 **서버 설정을 바꿀 일이
생기면 여기를 먼저 고치고 서버에 배포한다.** 반대 방향(서버에서 고치고 나중에 옮기기)은
잊어버리면 그대로 유실된다.

## 파일

| 파일 | 배포 위치 | 설명 |
| --- | --- | --- |
| `docker-compose.oci.yml` | `~/menupick/docker-compose.oci.yml` | prod compose에 겹치는 서버 전용 오버레이(TLS, Mailpit) |
| `nginx-tls.conf` | `~/menupick/nginx-tls.conf` (컨테이너의 `/etc/nginx/conf.d/default.conf`로 마운트) | 자체 서명 TLS + `:80 → https` 리다이렉트 |
| `menupick-backup.service` | `/etc/systemd/system/` | `scripts/backup-db.sh` 실행 |
| `menupick-backup.timer` | `/etc/systemd/system/` | 매일 KST 04:00 백업 |
| `menupick-alert@.service` | `/etc/systemd/system/` | 유닛 실패를 웹훅으로 알린다(`OnFailure=`) |
| `menupick-error-watch.service` | `/etc/systemd/system/` | `scripts/check-app-errors.sh` 실행 |
| `menupick-error-watch.timer` | `/etc/systemd/system/` | 15분마다 앱 ERROR 로그 감시 |
| `journald-menupick.conf` | `/etc/systemd/journald.conf.d/menupick.conf` | 영구 보관 + 상한(컨테이너 로그가 여기로 온다) |

**여기 없는 것**(서버에만 있고 앞으로도 커밋하지 않는다):
- `.env` — 자격증명. 형식은 리포 루트의 `.env.prod.example` 참고.
- `certs/` — 자체 서명 인증서와 개인키. `.gitignore`가 `*.key`/`*.pem`을 막는다.
- `/etc/menupick/alert.env` — 웹훅 URL. **URL 자체가 자격증명이다**(그것만 있으면 누구나 그
  채널에 글을 쓴다). 0600 root 소유로 두고 커밋하지 않는다.

## 배포

소스 동기화(`git archive` + `scp` + `tar -xf`)는 추적 파일만 덮어쓰므로, 아래 두 파일은
전개해도 자동으로 갱신되지 **않는다**(서버의 것은 `~/menupick/` 바로 아래에 있고
리포의 것은 `deploy/oci/` 안에 있다). 바꿨으면 직접 복사한다.

```bash
cd ~/menupick
cp deploy/oci/docker-compose.oci.yml ./docker-compose.oci.yml
cp deploy/oci/nginx-tls.conf         ./nginx-tls.conf
docker exec menupick-web nginx -t && docker exec menupick-web nginx -s reload
docker compose -f docker-compose.prod.yml -f docker-compose.oci.yml up -d
```

CI가 `main`의 백엔드·프론트 이미지를 같은 커밋 SHA로 GHCR에 게시한다. 운영 서버에서는
`.env`의 `APP_VERSION`을 그 **전체 SHA**로 바꾸고 두 이미지를 명시적으로 받은 뒤 app/web만
재생성한다. MySQL·Redis를 함께 재시작할 이유는 없다.

```bash
cd ~/menupick
docker compose -f docker-compose.prod.yml -f docker-compose.oci.yml --env-file .env pull app web
docker compose -f docker-compose.prod.yml -f docker-compose.oci.yml --env-file .env up -d app web

# 검증이 끝난 뒤, 쓰지 않는 이미지를 정리한다. 이 줄이 없으면 배포마다 SHA 태그가 하나씩
# 쌓인다 — 2026-10-03에 72개(앱 36 + web 36, 5주치)가 쌓여 9.2GB를 먹고 있었고 루트가 77%였다.
docker image prune -a -f
```

**정리에 대해 알아 둘 것:**

- **검증 전에 돌리지 않는다.** `prune -a`는 컨테이너가 참조하지 않는 이미지를 전부 지우므로,
  롤백하려던 직전 버전도 함께 사라진다. 되돌릴 수는 있다 — GHCR에 그대로 있어 태그를 알면
  다시 받으면 된다(`docker compose pull`은 `.env`의 `APP_VERSION`을 본다).
- **볼륨은 건드리지 않는다.** `docker system prune --volumes`나 `docker volume prune`은 쓰지
  말 것 — MySQL 데이터가 named volume에 있다. 이미지만 지우는 `image prune`을 쓴다.
- 컨테이너를 정지해 둔 상태에서도 안전하다. 정지된 컨테이너도 자기 이미지를 참조하므로
  현재 버전은 지워지지 않는다.
- `flyway/flyway` 같은 일회성 복구용 이미지도 함께 지워진다. 필요한 순간에 다시 받으면 된다
  (`flyway repair`가 필요한 상황은 이미 비상이고, 그때 30초 pull은 비용이 아니다).
- 디스크가 모자라면 `sudo dnf clean all`(1GB 안팎)과 `journalctl --vacuum-size=500M`도 있다.
  현재 상한은 journal 1GB다.

배포 후에는 "컨테이너가 떠 있다"에서 끝내지 않고 아래 계약을 확인한다.

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.oci.yml --env-file .env ps
docker exec menupick-app curl -fsS http://localhost:9090/actuator/health/readiness
docker exec menupick-web sh -c 'grep worker_connections /etc/nginx/nginx.conf; ulimit -n'
curl -kfsS -o /dev/null -w '%{http_code}\n' https://localhost/
curl -sS -o /dev/null -w '%{http_code} %{redirect_url}\n' http://localhost/
```

기대값은 app/web `healthy`, readiness `UP`, `worker_connections 4096`, `ulimit -n 65535`,
HTTPS `200`, HTTP `301 → https`다. 이미지 아키텍처는 OCI Ampere A1에서 `arm64`여야 한다.

### 2026-09-04 용량 수정 배포 기록

- 배포 SHA: `dcede22b95830da6ccf6ea67a5c51f02f4ef7fca` (PR #212~#215)
- nginx `worker_connections`: `1024 → 4096`
- web `nofile`: `65535` 유지
- 프론트 빌드 이미지: Node `22.22.2`; 빌드 스테이지를 `$BUILDPLATFORM`에 고정해
  amd64 GitHub Actions 러너에서 정적 번들을 네이티브로 만들고 arm64 런타임 이미지에 복사
- CI: backend/frontend 테스트와 두 이미지의 `linux/amd64,linux/arm64` 게시 성공
- 운영 확인: app/web healthy, readiness UP, HTTPS 200, HTTP 301, 두 이미지 arm64,
  최근 nginx `worker_connections`/파일 디스크립터 오류 없음

첫 main 게시(`7c46df7`, Actions run `33819290871`)는 프론트 Node 빌드까지 arm64 QEMU에서
실행해 장시간 정체되어 취소했다. `frontend/Dockerfile`의 빌드 스테이지를
`FROM --platform=$BUILDPLATFORM`으로 고친 뒤 run `33822647518`에서 프론트 멀티아키텍처
이미지까지 정상 게시됐다. 이 때문에 Dockerfile 계약 테스트는 해당 줄을 고정한다.

nginx 설정을 바꿨으면 **반드시 `nginx -t`로 먼저 검증**한다. 잘못된 설정으로 reload하면
nginx는 옛 설정을 유지하지만, 재시작하면 그대로 죽는다.

## SSH 및 스캔 방어

공개 IP의 22번에는 계정명을 무작위 대입하는 봇이 계속 접근한다. OCI 보안 목록에서 22번
소스를 관리자 IP로 제한하는 것이 1차 방어이며, 서버 내부에는 다음 설정을 함께 적용한다.

```bash
sudo install -m 0600 deploy/oci/sshd-hardening.conf \
  /etc/ssh/sshd_config.d/10-menupick-hardening.conf
sudo sshd -t
sudo systemctl reload sshd

sudo dnf install -y oracle-epel-release-el9
sudo dnf install -y --enablerepo=ol9_developer_EPEL fail2ban fail2ban-firewalld
sudo install -m 0644 deploy/oci/fail2ban-sshd.local \
  /etc/fail2ban/jail.d/menupick-sshd.local
sudo fail2ban-client -t
sudo systemctl enable --now fail2ban
sudo fail2ban-client status sshd
```

SSH는 `opc` 공개키 로그인만 허용하고 root·비밀번호·키보드 대화식 인증을 거부한다.
fail2ban은 10분 안에 3번 실패한 IP를 1시간 차단하며, 반복되면 최대 1일까지 늘린다.
적용할 때는 기존 SSH 세션을 유지한 채 **새 세션 로그인이 성공하는지 확인한 뒤** 종료한다.
nginx는 `/.env`, `/.git` 등 모든 숨김파일 경로를 404로 반환한다.

## 호스트 설정 (리포 밖, 2026-09-11 보안 점검 후속)

compose·nginx 설정과 달리 호스트에 직접 건 것들이다. 인스턴스를 재생성하면 **다시 해야 한다.**

### journald 영속화 — 이게 없으면 위 `logging: journald`가 무의미하다

web 컨테이너 로그를 journald로 보내도, journald 자체가 휘발성이면 재부팅 때 함께 사라진다.
점검 당시 이 서버가 그 상태였다(`/var/log/journal` 없음 → `/run/log/journal` 사용).

```bash
sudo mkdir -p /var/log/journal
sudo systemd-tmpfiles --create --prefix /var/log/journal
sudo sed -i 's/^#\?Storage=.*/Storage=persistent/' /etc/systemd/journald.conf
sudo sed -i 's/^#\?SystemMaxUse=.*/SystemMaxUse=500M/' /etc/systemd/journald.conf
sudo systemctl restart systemd-journald
journalctl --disk-usage          # /var/log/journal 아래인지 확인
```

상한을 두는 이유는 부트 볼륨이 30GB이고 접근 로그가 계속 쌓이기 때문이다.

### rpcbind 중지 — 쓰는 곳이 없다

`0.0.0.0:111`에 바인딩돼 있었다. firewalld가 `ssh`·`80`·`443`만 허용하므로 밖에서 닿지는
않았지만, 포트매퍼는 증폭 공격에 쓰이는 서비스라 안 쓰면 열어 둘 이유가 없다.

**끄기 전에 확인한 것**: `/etc/fstab`에 NFS 항목 없음, 실제 NFS 마운트 없음
(`mount | grep nfs`가 잡는 `rpc_pipefs`는 의사 파일시스템이다), `rpcinfo -p`에 포트매퍼
자기 자신 외 등록 없음, `nfs-server`·`rpc-statd` 모두 비활성.

```bash
sudo systemctl disable --now rpcbind.socket rpcbind.service
sudo ss -lntp | grep :111 || echo "111 닫힘"
```

NFS를 쓰게 되면 되살려야 한다.

## 실패 알림 (웹훅)

**왜**: 2026-09-30·10-01 백업이 두 번 연속 실패했는데(검증 후 컨테이너를 내려 둔 상태라
`menupick-mysql 가 healthy가 아니다`), 그 사실은 `journalctl`에만 남아 10-02에 사람이 열어
볼 때까지 아무도 몰랐다. 백업에서 가장 무서운 것은 실패가 아니라 **아무도 모르는 실패**다.

알림을 보내는 경로는 셋이다.

| 언제 | 보내는 것 |
| --- | --- |
| `menupick-backup.service` 실패 | `OnFailure=menupick-alert@%n.service` → 유닛 이름·결과·journal 꼬리 12줄 |
| 앱 컨테이너에 ERROR 로그 발생 | `menupick-error-watch.timer`(15분) → 건수와 샘플 5줄 |
| ERROR 감시 자체가 실패 | 감시 유닛에도 `OnFailure=`가 걸려 있다 |

앱 안에서 도는 스케줄러 세 개(집단 통계 04:10, 방 정리 04:20, 탈퇴 정리)는 실패를 잡아서
로그만 남기고 다음 회차를 유지한다 — 유닛이 실패하지 않으므로 `OnFailure`가 걸릴 자리가 없다.
그래서 ERROR 감시가 따로 필요하다.

```bash
# 설치 (최초 1회)
sudo install -m 0755 scripts/notify-failure.sh   /usr/local/bin/menupick-alert
sudo install -m 0755 scripts/check-app-errors.sh /usr/local/bin/menupick-error-watch
sudo cp deploy/oci/menupick-alert@.service /etc/systemd/system/
sudo cp deploy/oci/menupick-error-watch.{service,timer} /etc/systemd/system/
sudo cp deploy/oci/menupick-backup.service /etc/systemd/system/   # OnFailure= 추가됨
sudo systemctl daemon-reload
sudo systemctl enable --now menupick-error-watch.timer

# 웹훅 URL 넣기 (사용자 작업 — 디스코드/슬랙에서 발급)
sudo install -d -m 0700 /etc/menupick
sudo install -m 0600 /dev/null /etc/menupick/alert.env
sudo tee /etc/menupick/alert.env >/dev/null <<'EOF'
ALERT_WEBHOOK_URL=https://discord.com/api/webhooks/...
ALERT_WEBHOOK_FORMAT=discord
EOF

# 점검 — 채널에 한 줄이 떠야 한다
sudo /usr/local/bin/menupick-alert --test
```

`scripts/`가 원본이고 `/usr/local/bin`의 것은 사본이다(백업 스크립트와 같은 이유 — SELinux
때문에 홈에서 직접 실행할 수 없다). **고치면 다시 설치해야 한다.**

알아 둘 것:

- **URL이 없으면 실패가 아니다.** `/etc/menupick/alert.env`가 없거나 URL이 비어 있으면 경고
  한 줄만 남기고 정상 종료한다. 알림 미설정과 진짜 실패가 구분되어야 하기 때문이다.
- **같은 ERROR는 1시간에 한 번만 보낸다**(`ERROR_WATCH_COOLDOWN`). 같은 장애가 계속 나는 동안
  15분마다 알리면 채널이 막히고, 그러면 알림 자체를 끄게 된다. journal에는 매번 남는다.
- **컨테이너가 꺼져 있으면 조용하다.** ERROR 로그가 없으므로 아무것도 보내지 않는다 —
  검증 후 컨테이너를 내려 두는 운영 방식에서 "꺼짐"을 장애로 알리면 쓸 수 없는 알림이 된다.
  대신 **그 상태에서 백업 타이머는 매일 실패하고, 그 실패는 이제 알림으로 온다.**
- 알림 스크립트는 **URL을 어디에도 출력하지 않는다.** 전송 결과는 HTTP 코드로만 남는다.

```bash
# 상태 확인
systemctl list-timers menupick-error-watch.timer
journalctl -u menupick-error-watch -n 20
systemctl --failed
```

## 로그

컨테이너 로그는 **호스트 journal로 보낸다**(`docker-compose.prod.yml`의 `x-logging`).
json-file은 로그를 컨테이너에 붙여 두므로 배포로 app·web을 재생성하면 사고 당시의 로그가
함께 사라진다 — 2026-09-11 보안 점검에서 실제로 "언제부터"를 답할 수 없었다.

```bash
journalctl CONTAINER_NAME=menupick-app --since -1h
journalctl CONTAINER_NAME=menupick-app --since -1h | grep ERROR
journalctl -t menupick-app -p err          # tag로도 찾을 수 있다
docker logs menupick-app --tail 50         # journald 드라이버에서도 그대로 동작한다
```

### 영구 journal — 실제로 켜져 있는지 반드시 확인한다

`Storage=persistent`를 적어 두는 것만으로는 부족하다. **journald는 그 부팅에서
`systemd-journal-flush`가 한 번 돈 뒤에야 `/var/log/journal`을 쓴다**(`/run/systemd/journal/flushed`
마커가 그 표식이다). 2026-10-02에 이 서버가 정확히 그 상태였다:

- `/var/log/journal`은 9-11에 만들어졌지만 **0바이트**였고,
- 450MB 전부가 `/run/log/journal`(tmpfs)에 있었다. 재부팅하면 그대로 사라진다.
- 원인: 마지막 부팅이 9-01이라 그 부팅의 flush는 `/var/log/journal`이 없던 시점에 끝났고,
  9-11에 디렉터리를 만들고 journald를 재시작한 뒤로는 flush가 다시 돈 적이 없었다.
  SELinux 라벨(`var_log_t`)과 쓰기 권한은 정상이었다 — 그래서 더 찾기 어렵다.

```bash
sudo cp deploy/oci/journald-menupick.conf /etc/systemd/journald.conf.d/menupick.conf
# 손으로 만들어 둔 옛 드롭인이 있으면 치운다. 드롭인은 **알파벳 순으로 읽혀 뒤가 이긴다** —
# persistent.conf(SystemMaxUse=200M)가 menupick.conf(1G) 뒤에 읽혀 상한이 되돌아갔다.
sudo mv /etc/systemd/journald.conf.d/persistent.conf \
        /etc/systemd/journald.conf.d/persistent.conf.superseded-by-menupick 2>/dev/null || true
sudo systemctl restart systemd-journald
sudo journalctl --flush                    # 런타임 → 영구로 옮기고 마커를 만든다
ls -la /run/systemd/journal/flushed        # 있어야 한다
journalctl --disk-usage                    # /var/log/journal 아래여야 한다
sudo du -sh /var/log/journal /run/log/journal
```

상한은 1GB(`SystemMaxUse`)에 여유 2GB 확보(`SystemKeepFree`)다. 컨테이너 로그가 들어오면서
양이 늘기 때문이고, 루트 디스크가 이미 74% 차 있어(30G 중 22G) 가득 차면 MySQL이 먼저 죽는다.

## 백업

```bash
# 설치 (최초 1회)
sudo install -m 0755 scripts/backup-db.sh /usr/local/bin/menupick-backup
sudo cp deploy/oci/menupick-backup.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now menupick-backup.timer

# 상태 확인
systemctl list-timers menupick-backup.timer
journalctl -u menupick-backup -n 30

# 즉시 한 번 돌리기
sudo systemctl start menupick-backup.service
```

cron이 아니라 systemd 타이머를 쓰는 이유는 **실패가 보이기 때문**이다. cron은 출력을
메일로 보내는데 이 서버에는 MTA가 없어 실패가 그냥 사라진다. 타이머는
`systemctl status` / `journalctl -u`에 남는다.

**스크립트를 고치면 `/usr/local/bin`에 다시 설치해야 한다.** 홈에 둔 파일을 그대로
실행하지 않는 이유는 SELinux다 — Oracle Linux 9는 Enforcing이고 홈의 파일은
`user_home_t`라, systemd(`init_t`)가 실행하려 하면 `203/EXEC Permission denied`로 죽는다.
셸에서 손으로 돌리면 멀쩡히 동작하기 때문에 원인을 스크립트에서 찾기 쉬운 함정이다.

```bash
sudo install -m 0755 scripts/backup-db.sh /usr/local/bin/menupick-backup
```

`Persistent=true`라, 예정 시각에 인스턴스가 꺼져 있었으면 켜진 뒤 한 번 따라잡는다 —
이 서버는 상시 가동이 아니라 이게 없으면 꺼져 있던 날의 백업이 그냥 없어진다.

### 복원

```bash
# 컨테이너는 떠 있어야 한다
zcat ~/backups/menupick-<타임스탬프>.sql.gz \
  | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" menupick-mysql mysql -u root menupick
```

복원 후에는 **Flyway 이력도 함께 돌아온다**(덤프에 `flyway_schema_history`가 포함된다).
그 시점보다 새로운 마이그레이션이 앱 이미지에 들어 있으면 다음 기동에서 적용된다.

### 남은 위험 — 백업이 서버 안에만 있다

`~/backups/`는 **인스턴스의 부트 볼륨 위**에 있다. 인스턴스를 재생성하면 백업도 함께
사라진다 — 2026-08-19에 데이터가 사라졌을 때 정확히 그래서 남은 것이 없었다.
진짜 오프사이트로 만들려면 OCI Object Storage에 올려야 하고, 그러려면 콘솔에서
동적 그룹과 정책을 만들어 인스턴스 프린시펄 인증을 켜야 한다(사용자 소유 작업).
그 전까지는 주기적으로 로컬로 내려받아 두는 것이 최선이다:

```bash
scp -i ssh-key-*.key opc@146.56.116.44:backups/'*.sql.gz' ./local-backups/
```
