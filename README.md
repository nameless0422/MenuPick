
# 메뉴픽 Menu Pick (back)

> **"오늘 뭐 먹지?" 결정 장애를 해결해주는 개인화 맛집 추천 서비스**

많은 맛집을 저장해두지만 정작 메뉴를 고를 때 고민하는 사람들을 위해, 본인의 취향이 담긴 데이터를 기반으로 메뉴를 추천해주는 서비스입니다. 태그별 필터링과 지도 API 연동을 통해 확실한 한 끼를 제안합니다.

-----

## 

### 1\. 메뉴 및 맛집 아카이빙

- **태그 시스템**: `#한식`, `#국물요리`, `#혼밥` 등 자유로운 태그 지정 및 관리.
- **맛집 기록**: 메뉴별 추천 식당 정보(이름, 메모, 별점) 저장.
- **지도 연동**: 카카오 로컬 API로 장소를 검색해 저장하고, 카카오맵 JS SDK로 위치를 표시한다. (네이버 지도 API는 지오코딩 프록시로만 쓴다.)

### 2\. 스마트 필터링 & 랜덤 추천

- **조건부 필터링**: 현재 기분이나 상황에 맞는 태그를 선택하여 후보군 추출.
- **실시간 랜덤 피커**: 필터링된 결과 중 프론트엔드에서 시각적인 효과(슬롯머신 등)와 함께 최종 메뉴 결정.
- **다시 돌리기**: 결과가 마음에 들지 않을 경우 즉시 재추천 가능.

### 3\. 사용자 맞춤형 편의 기능

- **소셜 로그인**: 카카오/구글 계정 연동을 통한 간편 가입.
- **내 주변 맛집**: 현재 위치 기반 반경 설정으로 근거리 식당 위주 추천.
-----

## 구성

- `src/` — 백엔드 (Spring Boot 4, Java 17)
- `frontend/` — 프론트엔드 (React + Vite + TypeScript). [frontend/README.md](frontend/README.md) 참고
- `scripts/k6/` — 부하 테스트 (시드 SQL·토큰 발급·시나리오). [LoadTestPlan.md](docs/LoadTestPlan.md) 참고

## 시작하기 (백엔드)

의존 서비스를 먼저 띄운다. MySQL·Redis 없이는 앱이 기동하지 않는다.

```bash
docker compose up -d          # mysql(3306), redis(6379), mailpit(1025/8025)
```

`JWT_SECRET`은 기본값이 없다 — 없으면 기동 단계에서 실패한다. 의도적으로 기본값을 두지 않았다([D-008](docs/DecisionLog.md#d-008-jwt-시크릿-기본값-제거)). HS256이라 **최소 256비트(32바이트 이상)** 여야 한다.

```bash
export JWT_SECRET="아무렇게나-길게-32바이트-이상-되는-로컬-전용-값"
./gradlew bootRun             # http://localhost:8080, 프로파일 기본값은 local
```

스키마는 Flyway가 기동 시 적용한다(`src/main/resources/db/migration`). API 문서는 local 프로파일에서만 열린다 — <http://localhost:8080/swagger-ui.html>.

**메일**: local 프로파일은 SMTP를 지정하지 않아 인증 링크를 로그(WARN)로 떨어뜨린다. 실제 발송까지 보려면 compose의 mailpit을 쓴다 — `SPRING_MAIL_HOST=localhost SPRING_MAIL_PORT=1025`로 띄우고 받은 메일은 <http://localhost:8025>에서 확인한다.

### 검증

```bash
./gradlew check               # 테스트 + 커버리지 하한 (CI가 도는 것과 같다)
./gradlew test                # 테스트만
```

`check`에는 JaCoCo 커버리지 게이트가 포함된다([D-030](docs/DecisionLog.md#d-030-테스트-커버리지-하한--목표치가-아니라-래칫)). 통합 테스트는 Testcontainers로 실제 MySQL·Redis·SMTP 컨테이너를 띄우므로 **Docker가 떠 있어야 한다**.

## 배포 (운영)

운영은 OCI VM 한 대다 — Oracle Linux 9 / aarch64, 접속 계정은 `ubuntu`가 아니라 **`opc`**(`opc@146.56.116.44`), 배포 경로는 `~/menupick`. 스택 구성은 [docker-compose.prod.yml](docker-compose.prod.yml)이고, 환경 변수 템플릿은 [.env.prod.example](.env.prod.example)이다.

**VM에서 소스를 빌드하지 않는다.** 백엔드(`ghcr.io/nameless0422/menupick`)와 프론트(`ghcr.io/nameless0422/menupick-web`) 이미지는 모두 CI가 빌드해 GHCR에 올리고, VM은 태그를 pull해서 쓴다. 두 이미지는 `.env.prod`의 **`APP_VERSION` 한 값을 함께** 가리킨다 — 따로 두면 "백엔드만 새 버전이고 프론트는 옛 번들"인 상태가 생긴다([#82](https://github.com/nameless0422/MenuPick/issues/82)).

### 1. 소스 동기화 — `git pull`이 아니라 `git archive` + `scp`

**서버 `~/menupick`에는 `.git`이 없다.** 파일 복사본이라 `git pull`이 불가능하다.

```bash
git archive --format=tar origin/main -o main.tar
scp main.tar opc@146.56.116.44:~/
ssh opc@146.56.116.44 'tar -xf ~/main.tar -C ~/menupick'
```

`git archive`에는 **Git이 추적하는 파일만** 들어간다. 그래서 서버에만 있는 파일(`.env.prod`, compose 오버레이, TLS 설정·인증서)은 전개해도 덮어써지지 않는다 — rsync 대신 이 방법을 쓰는 이유가 그것이다. 뒤집어 말하면, 리포에 없는 파일을 고쳐야 하는 변경은 서버에서 따로 손봐야 한다(아래 "서버에만 있는 것" 참고).

### 2. 배포

1. **DB 백업 먼저.** `mysqldump --single-transaction`으로 뜨고 `gzip -t`로 파일이 온전한지까지 확인한다. 마이그레이션이 포함된 배포는 되돌리기가 이미지 교체만으로 끝나지 않는다.
2. 소스 동기화(위 1번).
3. 서버 `.env.prod`의 `APP_VERSION`을 배포할 **커밋 SHA**로 바꾼다. `latest`는 "지금 main"이라 롤백 지점이 남지 않는다.
4. 이미지를 받고 띄운다. **`--build`는 쓰지 않는다.**
   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.prod pull
   docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
   ```
   운영 서버에는 오버레이가 한 장 더 있으므로 그 서버에서는 `-f`로 함께 넘긴다.
5. 확인. 앱의 관리 포트는 컨테이너 루프백에만 있으므로 안에서 본다.
   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env.prod ps
   docker exec menupick-app curl -s localhost:9090/actuator/health
   ```

`web`(nginx)의 80번은 **기본적으로 호스트 루프백에만** 붙는다(`WEB_HTTP_BIND`). nginx가 `/api/`를 그대로 프록시하므로, 0.0.0.0에 열면 TLS를 우회한 평문 경로로 로그인 자격증명이 오간다. 여는 조건은 `.env.prod.example`의 해당 항목에 적어 두었다.

### 3. 롤백

`.env.prod`의 `APP_VERSION`을 이전 SHA로 되돌리고 다시 `pull` → `up -d`. **이 한 줄이 백엔드와 프론트를 함께 되돌린다.**

되돌리기 전에 두 가지를 확인한다(자세한 이유는 `docker-compose.prod.yml`의 `app` 주석에 있다).

- **되돌릴 SHA가 지금 DB 스키마와 맞는가.** `ddl-auto=validate`라 스키마가 앞서 있으면 옛 이미지는 기동 검증에서 실패하고 restart 루프에 빠진다. 특히 V5는 컬럼을 RENAME해 버려서 **이미지 교체만으로는 되돌아가지 않는다**(컬럼 수동 복구 + `flyway_schema_history` 정리가 필요하다).
- **`app`이 재생성되면 컨테이너 IP가 바뀐다.** `web`이 옛 IP를 캐시하지 않도록 nginx 설정이 resolver + 변수 업스트림을 쓰고 있어야 한다.

### 서버에만 있는 것

`.env.prod`, compose 오버레이, TLS 종단 설정과 인증서는 리포에 커밋하지 않는다(값 자체가 비밀이거나 이 서버에서만 의미가 있다). 두 가지를 기억할 것.

- **리포의 `frontend/nginx.conf`를 고쳐도 이 서버에는 반영되지 않는다.** 오버레이가 서버 전용 nginx 설정을 컨테이너에 마운트해 이미지 안의 설정을 통째로 덮어쓴다. nginx 관련 수정은 양쪽에 넣고 `docker exec menupick-web nginx -t`로 확인한다. 실제로 [#73](https://github.com/nameless0422/MenuPick/issues/73)의 DNS 캐싱 수정을 머지하고도 서버는 그대로 502였다.
- **오버레이에 남은 임시값은 전제가 사라지면 걷어낸다.** TLS 이전에 넣은 `AUTH_COOKIE_SECURE=false`, 실제 SMTP 공급자를 정하기 전에 넣은 Mailpit 컨테이너가 그것이다. 지금은 둘 다 전제가 끝났다(TLS 종단·Gmail SMTP).

## docs

- [CurrentStatus.md](docs/CurrentStatus.md) — 현재 구현·배포·운영 상태와 다음 제품 과제
- [Specification.md](docs/Specification.md) — 전체 스펙 문서 (기능 스펙 단일 진입점, 로드맵)
- [Planning.md](docs/Planning.md) — 기술 설계 계획서 (스택, API, 데이터 모델, 인증, 리스크)
- [DecisionLog.md](docs/DecisionLog.md) — 설계·구현 결정 기록 (왜 이렇게 만들었는지, 검토한 대안, 트레이드오프). **진행하면서 계속 갱신**
- [Requirements.md](docs/Requirements.md) — 유저 스토리, 추천 품질 KPI, Phase 일정 초안
- [ImprovementBacklog.md](docs/ImprovementBacklog.md) — 개선 과제 백로그와 처리 현황
- [PrivacyReview.md](docs/PrivacyReview.md) — 위치정보법·개인정보 고지 검토
- [LoadTestPlan.md](docs/LoadTestPlan.md) — 부하 테스트 설계 (시나리오, 시드, 판정 기준)
- [LoadTestResults.md](docs/LoadTestResults.md) — 부하 테스트 회차별 결과 기록
- [CommitConvention.md](docs/CommitConvention.md) — 커밋 메시지 규칙

-----

이 README 파일은 개발 진행 상황에 따라 업데이트될 예정입니다.
