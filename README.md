
# 메뉴픽 Menu Pick (back)

> **"오늘 뭐 먹지?" 결정 장애를 해결해주는 개인화 맛집 추천 서비스**

많은 맛집을 저장해두지만 정작 메뉴를 고를 때 고민하는 사람들을 위해, 본인의 취향이 담긴 데이터를 기반으로 메뉴를 추천해주는 서비스입니다. 태그별 필터링과 지도 API 연동을 통해 확실한 한 끼를 제안합니다.

-----

## 

### 1\. 메뉴 및 맛집 아카이빙

- **태그 시스템**: `#한식`, `#국물요리`, `#혼밥` 등 자유로운 태그 지정 및 관리.
- **맛집 기록**: 메뉴별 추천 식당 정보(이름, 메모, 별점) 저장.
- **지도 연동**: 네이버 지도 API를 활용하여 식당의 위치 정보 저장 및 확인.

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

## docs

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