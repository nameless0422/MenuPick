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
| `deploy.sh` | `/tmp/deploy.sh` (배포 때마다 복사) | 백업 → 코드 → 이미지 교체 → 확인을 한 번에 |
| `docker-compose.oci.yml` | `~/menupick/docker-compose.oci.yml` | prod compose에 겹치는 서버 전용 오버레이(TLS, Mailpit) |
| `nginx-tls.conf` | `~/menupick/nginx-tls.conf` (컨테이너의 `/etc/nginx/conf.d/default.conf`로 마운트) | 자체 서명 TLS + `:80 → https` 리다이렉트 |
| `menupick-backup.service` | `/etc/systemd/system/` | `scripts/backup-db.sh` 실행 |
| `menupick-backup.timer` | `/etc/systemd/system/` | 매일 KST 04:00 백업 |
| `menupick-alert@.service` | `/etc/systemd/system/` | 유닛 실패를 웹훅으로 알린다(`OnFailure=`) |
| `menupick-error-watch.service` | `/etc/systemd/system/` | `scripts/check-app-errors.sh` 실행 |
| `menupick-error-watch.timer` | `/etc/systemd/system/` | 15분마다 앱 ERROR 로그 감시 |
| `menupick-heal.service` | `/etc/systemd/system/` | `scripts/heal-unhealthy.sh` 실행 |
| `menupick-heal.timer` | `/etc/systemd/system/` | 2분마다 unhealthy 컨테이너 복구 |
| `menupick-disk-watch.service` | `/etc/systemd/system/` | `scripts/check-disk.sh` 실행 |
| `menupick-disk-watch.timer` | `/etc/systemd/system/` | 30분마다 디스크 사용률 감시 |
| `journald-menupick.conf` | `/etc/systemd/journald.conf.d/menupick.conf` | 영구 보관 + 상한(컨테이너 로그가 여기로 온다) |

**여기 없는 것**(서버에만 있고 앞으로도 커밋하지 않는다):
- `.env` — 자격증명. 형식은 리포 루트의 `.env.prod.example` 참고.
- `certs/` — 자체 서명 인증서와 개인키. `.gitignore`가 `*.key`/`*.pem`을 막는다.
- `/etc/menupick/alert.env` — 웹훅 URL. **URL 자체가 자격증명이다**(그것만 있으면 누구나 그
  채널에 글을 쓴다). 0600 root 소유로 두고 커밋하지 않는다.
- `/etc/menupick/offsite.env` — 오프사이트 백업용 쓰기 전용 PAR URL. 역시 자격증명이다(0600 root).

## 배포

보통의 배포는 [`deploy.sh`](deploy.sh) 하나로 한다. DB 기동 → 백업(실패하면 멈춤) → 코드 풀기 →
`APP_VERSION` 교체 → 백업 스크립트·유닛 재설치 → app/web 교체 → 헬스·데모 API 확인 순이다(D-091).
그 SHA의 main CI가 이미지를 올린 뒤에 돌리고, SHA는 **40자 전체**를 넘긴다.

```bash
# 로컬, 저장소 루트에서
git fetch && git archive --format=tar origin/main -o main.tar
scp main.tar deploy/oci/deploy.sh opc@146.56.116.44:/tmp/
ssh opc@146.56.116.44 "bash /tmp/deploy.sh $(git rev-parse origin/main)"
# 확인이 끝난 뒤 옛 이미지 정리(아래 "정리에 대해 알아 둘 것")
ssh opc@146.56.116.44 'docker image prune -a -f'
```

아래는 스크립트가 하는 일을 손으로 나눠 할 때, 그리고 스크립트가 다루지 않는 서버 전용 파일의 절차다.

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

`nginx-tls.conf`는 쿼리 없는 접근 로그와 강제 CSP를 담고 있다(2026-11). 이미지만 올리면 서버에는
반영되지 않으므로 위 복사를 함께 한다. V21 배포의 로그인·메일 링크 전환 동작은 D-070을 본다.

CI가 `main`의 백엔드·프론트 이미지를 같은 커밋 SHA로 GHCR에 게시한다. 운영 서버에서는
`.env`의 `APP_VERSION`을 그 **전체 SHA**로 바꾸고 두 이미지를 명시적으로 받은 뒤 app/web만
재생성한다. MySQL·Redis를 함께 재시작할 이유는 없다.

MySQL·Redis·Mailpit 이미지는 compose에 `@sha256:` digest로 고정돼 있다(2026-11, D-072). 이 중
MySQL은 8.0 EOL로 **8.4 LTS**, Redis는 Alpine EOSL 탈출로 **8.2-alpine**이다. 서버를 이미 8.0/7로
띄워 둔 상태라면 이 digest로 바꾼 compose를 올릴 때 MySQL·Redis 컨테이너가 재생성된다 —
데이터는 named volume에 있어 보존되지만, **배포 전에 `scripts/backup-db.sh`로 덤프를 한 번 받고**
engine 업그레이드(8.0→8.4)를 적용한다. digest 갱신은 `security.yml`의 주간 `infra-image-scan`이
새 CVE를 보고하면 사람이 올린다.

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

알림을 보내는 경로는 다섯이다.

| 언제 | 보내는 것 |
| --- | --- |
| `menupick-backup.service` 실패 | `OnFailure=menupick-alert@%n.service` → 유닛 이름·결과·journal 꼬리 12줄 |
| 앱 컨테이너에 ERROR 로그 발생 | `menupick-error-watch.timer`(15분) → 건수와 샘플 5줄 |
| ERROR 감시 자체가 실패 | 감시 유닛에도 `OnFailure=`가 걸려 있다 |
| 디스크 80%·90% 도달, 회복 | `menupick-disk-watch.timer`(30분) → 사용률과 무엇이 차지하는지 |
| 오프사이트 업로드 실패 | 백업 유닛의 `ExecStartPost`가 실패 → 위 `OnFailure` |

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

## unhealthy 자동 복구

**왜**: compose의 `restart: unless-stopped`는 **프로세스가 끝났을 때만** 반응한다. 살아는
있는데 요청을 못 받는 상태(스레드 풀 고갈, 커넥션 누수, nginx worker 멈춤)는 `Up (unhealthy)`로
그대로 머물고, 이 서버에는 그걸 보고 조치할 오케스트레이터가 없다. 2026-10-03 점검에서
"헬스체크가 빨간불인데 아무도 아무것도 하지 않는 구간"으로 확인된 공백이다.

```bash
# 설치 (최초 1회)
sudo install -m 0755 scripts/heal-unhealthy.sh /usr/local/bin/menupick-heal
sudo cp deploy/oci/menupick-heal.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now menupick-heal.timer

# 상태 확인
systemctl list-timers menupick-heal.timer
journalctl -u menupick-heal -n 20
ls /var/lib/menupick/heal/            # 컨테이너별 카운터(복구되면 지워진다)
```

규칙은 네 줄이다.

- **꺼진 컨테이너는 건드리지 않는다.** 판정에 쓰는 `docker ps --filter health=unhealthy`가
  실행 중인 것만 내놓는다. 검증 후 컨테이너를 내려 두는 운영 방식에서 "꺼짐"을 고장으로 보면,
  사람이 내린 것을 기계가 계속 되돌린다.
- **연속 2회 관찰해야 손을 댄다**(2분 주기 → 실제 조치까지 최대 4분 남짓). 헬스체크가 한 번
  튄 것으로 재시작하지 않는다 — 특히 mysql은 일시적인 ping 실패가 재시작보다 싸다.
- **같은 컨테이너는 10분에 한 번만** 재시작한다(쿨다운).
- **3회 실패하면 멈추고 사람을 부른다.** 재시작으로 안 되는 고장(예: DB가 죽어 앱이 unhealthy)을
  무한히 되풀이하지 않는다. 포기 알림은 한 번만 가고, 복구되면 카운터가 풀린 뒤 다시 감시한다.

조치·포기·복구는 모두 웹훅으로 알린다(`/etc/menupick/alert.env`의 URL이 비어 있으면 journal에만
남는다). 재시작은 `docker restart`라 컨테이너가 바뀌지 않으므로 로그도 끊기지 않는다.

## 디스크 감시

**왜**: 2026-10-03에 배포마다 쌓인 이미지 72개가 9.2GB를 먹어 루트가 77%였고, 2026-11에는
23GB까지 찼다. 둘 다 사람이 우연히 `df`를 보고서야 알았다. 이 서버는 루트 하나(30GB)에 MySQL
데이터·도커 이미지·journal·백업이 전부 올라가 있어, 가득 차면 MySQL이 쓰기를 못 해 먼저 죽고
백업도 같이 실패한다.

```bash
# 설치 (최초 1회)
sudo install -m 0755 scripts/check-disk.sh /usr/local/bin/menupick-disk-watch
sudo cp deploy/oci/menupick-disk-watch.{service,timer} /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now menupick-disk-watch.timer

# 상태 확인
systemctl list-timers menupick-disk-watch.timer
journalctl -u menupick-disk-watch -n 10
cat /var/lib/menupick/disk-watch.*      # 마운트별 "단계 마지막알림시각"
```

30분마다 `/`, `/var/lib/docker`, `~/backups`가 올라간 파일시스템을 본다(같은 것은 한 번만 —
지금은 전부 루트다). 블록과 inode 중 높은 쪽으로 판정한다.

- **80% 경고, 90% 위험.** 단계가 **오를 때** 바로 알린다.
- 같은 단계에 머무르면 **6시간에 한 번만** 다시 알린다(`DISK_WATCH_COOLDOWN`).
- **정상으로 돌아오면 한 번 알린다.** 정리했는데 확인이 안 오면 감시가 죽은 것과 구분되지 않는다.
- 위험 → 경고처럼 내려가기만 한 것은 알리지 않는다.
- 알림에는 `docker system df`(회수 가능 용량 포함), journal 사용량, 백업 폴더 크기가 같이 실린다.
  대부분은 위 "배포" 절의 `docker image prune -a -f`로 해결된다. **볼륨은 지우지 않는다.**
- 알림 전송이 실패하면 단계를 기록하지 않고 유닛이 실패한다 — 다음 회차에 다시 보낸다.

## 칼로리 기준 데이터

기준 음식 검색(D-082)은 `food_references` 테이블을 읽는다. 데이터는 저장소에 없고 서버에서 직접 넣는다.

1. 공공데이터포털의 "전국통합식품영양성분정보(음식)표준데이터"(data.go.kr/data/15100070)를
   서버에서 `scripts/fetch-food-references.py`로 받는다. 로그인·인증키가 필요 없다.
   스크립트가 깨지면(포털 화면 변경) 브라우저에서 CSV 버튼으로 받아 `/tmp/food.csv`로 올려도 같다.
   - **이용 조건**: 데이터셋에 공공누리 유형 표시가 없다. 식약처가 업무상 작성·공표한 데이터라 자유 이용으로
     보고, 기준 음식 검색 화면에 출처(식품의약품안전처 식품영양성분 데이터베이스)를 적어 둔다.
2. 넣기 전에 백업을 뜬다(`sudo systemctl start menupick-backup.service`). SQL로 바꾸고, 건너뛴 행 수를 확인한 뒤 넣는다.

```bash
# 서버에서
cd ~/menupick
python3 scripts/fetch-food-references.py /tmp/food.csv                    # 2026-11-22 판: 19,738행
python3 scripts/import-food-references.py /tmp/food.csv > /tmp/food.sql   # 표준 오류에 건너뛴 행 수(같은 판: 1,266)
head -c 600 /tmp/food.sql                                                  # 눈으로 한 번 본다
set -a; . ./.env; set +a
docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" menupick-mysql mysql -uroot menupick < /tmp/food.sql
docker exec -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" menupick-mysql \
  mysql -uroot menupick -e "SELECT COUNT(*), MAX(imported_at) FROM food_references"
rm /tmp/food.csv /tmp/food.sql
```

- **다시 넣어도 된다.** 식품코드 기준 upsert라 새 판을 받으면 같은 절차를 반복한다. 끝에 연결된 메뉴의
  열량도 새 값으로 맞춘다.
- 이번 CSV에 없는 행은 **메뉴가 가리키지 않을 때만** 지운다(D-090). 읽힌 행이 1만 개 미만이면 지우지 않는다.
- 1인분 무게(`식품중량`)나 기준량을 읽을 수 없는 행, 둘의 단위가 다른 행(g/ml), 식품중량이 기준량과 같은
  행(100g당 값, 2026-11 판 1,214행)은 건너뛴다. 1인분 열량을 지어내지 않기 위해서다.
- 데이터를 넣기 전에도 메뉴에 직접 적은 열량은 그대로 동작한다.
- 탄단지·식이섬유(D-084)도 같은 CSV에서 읽는다. 식이섬유 열이 없거나 비어 있으면 그 값만 모름으로 둔다.
- 당류(g)·나트륨(mg)(D-085)도 같은 CSV에서 읽는다. 1인분 나트륨이 100,000mg 이상이면 원본 오류로 보고 모름으로 둔다.


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

**컨테이너를 내려 둔 기간에는 건너뛴다.** `menupick-mysql`이 `exited`·`created`(= `compose stop`으로
멈춤)이면 `ExecCondition`이 1로 끝나 덤프·오프사이트 업로드·실패 알림이 모두 돌지 않는다.
journal에는 `SKIP: … 백업을 건너뛴다` 한 줄이 남는다. 데이터가 바뀌지 않는 기간이라 잃는 것이 없고,
매일 빨간불이 켜져 진짜 실패를 가리는 것을 막는다. 컨테이너가 **없거나**, 돌고 있는데 **healthy가
아니면** 고장이므로 예전처럼 실패하고 알림이 간다. 컨테이너를 다시 올리면 배포 절차대로
`sudo systemctl start menupick-backup.service`로 그날 백업을 한 번 받는다.

```bash
# 이 변경을 서버에 반영 (스크립트와 유닛 둘 다)
sudo install -m 0755 scripts/backup-db.sh /usr/local/bin/menupick-backup
sudo cp deploy/oci/menupick-backup.service /etc/systemd/system/
sudo systemctl daemon-reload
```

### 복원

```bash
# 컨테이너는 떠 있어야 한다
zcat ~/backups/menupick-<타임스탬프>.sql.gz \
  | docker exec -i -e MYSQL_PWD="$MYSQL_ROOT_PASSWORD" menupick-mysql mysql -u root menupick
```

복원 후에는 **Flyway 이력도 함께 돌아온다**(덤프에 `flyway_schema_history`가 포함된다).
그 시점보다 새로운 마이그레이션이 앱 이미지에 들어 있으면 다음 기동에서 적용된다.

### 오프사이트 — 서버 밖에 한 벌

`~/backups/`는 **인스턴스의 부트 볼륨 위**에 있다. 인스턴스를 재생성하면 백업도 함께
사라진다 — 2026-08-19에 데이터가 사라졌을 때 정확히 그래서 남은 것이 없었다.

그래서 백업 유닛이 덤프 검증을 통과하면 `ExecStartPost=+/usr/local/bin/menupick-offsite`로 그
파일을 **OCI Object Storage**에 올린다. 인증은 **쓰기 전용 사전 인증 요청(PAR)** URL 하나다.

- CLI, API 키, 인스턴스 프린시펄이 필요 없다. 서버에 있는 `curl` 하나로 끝난다.
- 권한이 좁다. 쓰기만 되고 **읽기, 목록, 삭제는 안 된다.** 서버가 털려도 침입자가 이 URL로
  기존 백업을 내려받거나 지울 수 없다. 덮어쓰기는 되므로 버킷의 **객체 버전 관리**를 켜 둔다.
- `Content-MD5`를 같이 보낸다. 전송 중에 내용이 바뀌면 Object Storage가 400으로 거절하므로,
  200을 받으면 같은 내용이 저장됐다는 뜻이다(쓰기 전용이라 다시 읽어 비교할 수가 없다).
- 최신 백업이 1시간보다 오래됐으면 올리지 않고 실패한다. 이번 덤프가 조용히 안 만들어졌는데
  며칠 전 파일을 올리고 "성공"하면, 오프사이트가 낡았다는 것을 아무도 모르게 된다.
- 업로드가 실패하면 백업 유닛이 실패하고 **기존 `OnFailure` 알림이 그대로 간다.**

**버킷 준비 (사용자 작업, OCI 콘솔 — 최초 1회)**

1. Storage → Buckets → **Create Bucket**. 이름 예: `menupick-backups`. 기본 Standard 티어, 가시성은
   **Private**(기본값). 생성 후 **Object Versioning: Enable**.
2. 버킷 → **Lifecycle Policy Rules** → 규칙 추가: 대상 `Latest version and previous versions`,
   **30일 뒤 Delete**. 이 규칙이 없으면 백업이 영원히 쌓인다(지금 덤프는 수 KB라 무료 한도
   20GB와는 거리가 멀지만, 보관 기간은 정해 두는 것이 맞다).
3. 버킷 → **Pre-Authenticated Requests** → Create:
   - Target: **Bucket**
   - Access Type: **Permit object writes** (읽기·목록 권한을 주지 않는다)
   - Expiration: 원하는 만큼(예: 1년). **만료되면 업로드가 실패하고 알림이 온다** — 그때 새로 만든다.
4. 만들어진 URL(`https://objectstorage.<region>.oraclecloud.com/p/<토큰>/n/<네임스페이스>/b/<버킷>/o/`)은
   **그 창에서 한 번만 보인다.** 바로 서버에 넣는다.

```bash
# 설치 (최초 1회)
sudo install -m 0755 scripts/offsite-backup.sh /usr/local/bin/menupick-offsite
sudo cp deploy/oci/menupick-backup.service /etc/systemd/system/   # ExecStartPost 추가됨
sudo systemctl daemon-reload

# PAR URL 넣기 (사용자 작업 — URL 자체가 자격증명이다, 화면·채팅에 붙이지 말 것)
sudo install -m 0600 /dev/null /etc/menupick/offsite.env
sudo vi /etc/menupick/offsite.env
#   OFFSITE_PAR_URL=https://objectstorage.<region>.oraclecloud.com/p/.../o/
#   OFFSITE_PREFIX=db/

# 점검 — 백업을 한 번 돌리고 업로드 줄을 확인한다
sudo systemctl start menupick-backup.service
journalctl -u menupick-backup -n 5 -o cat    # "오프사이트 업로드 완료: db/menupick-…"
```

`offsite.env`가 없으면 경고 한 줄만 남기고 성공으로 끝난다(아직 버킷을 만들지 않은 서버).
**있는데 읽을 수 없으면 실패로 본다** — 설정을 넣어 두고도 조용히 건너뛰는 것이 제일 나쁘다.

**오프사이트에서 복원**: 쓰기 전용 URL로는 내려받을 수 없다. 콘솔에서 버킷 → 객체 → Download로
받거나, 그 순간에만 쓸 **읽기 전용 PAR**을 짧은 만료로 만들어 `curl -o`로 받는다. 받은 뒤 복원
절차는 위와 같다.

**알아 둘 것 — 서버 쪽 암호화는 Object Storage 기본값이다.** 덤프에는 이메일, 비밀번호 해시,
식사 기록이 들어 있다. 버킷은 Private이고 OCI가 저장 시 암호화하지만, 클라이언트 쪽에서 한 번 더
암호화하지는 않는다. 키를 서버에만 두면 인스턴스를 잃을 때 키도 같이 잃어 백업이 쓸모없어지고,
키를 따로 보관하는 절차를 만들 만큼의 규모가 아니라고 판단했다(D-080).
