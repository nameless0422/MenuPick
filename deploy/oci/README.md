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

**여기 없는 것**(서버에만 있고 앞으로도 커밋하지 않는다):
- `.env` — 자격증명. 형식은 리포 루트의 `.env.prod.example` 참고.
- `certs/` — 자체 서명 인증서와 개인키. `.gitignore`가 `*.key`/`*.pem`을 막는다.

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

nginx 설정을 바꿨으면 **반드시 `nginx -t`로 먼저 검증**한다. 잘못된 설정으로 reload하면
nginx는 옛 설정을 유지하지만, 재시작하면 그대로 죽는다.

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
