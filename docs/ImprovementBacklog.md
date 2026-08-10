# 메뉴픽 개선 과제 백로그

**2026-07-05** — Planning.md 및 구현 코드 검토에서 도출한 미흡 사항 정리

우선순위 기준: **P1** 실제 버그·데이터 유실 (즉시 수정 권장) / **P2** 정책 결정 필요 (결정 없이는 구현 방향을 못 정함) / **P3** 전략·문서·운영 보강 (배포 전까지 해소)

---

## 요약

| # | 과제 | 분류 | 우선순위 | 상태 |
| --- | --- | --- | --- | --- |
| 1 | 이메일 UNIQUE 충돌 시 500 (타 소셜 동일 이메일 가입 불가) | 버그 | P1 | ✅ |
| 2 | Pick 시 histories.restaurant_id 미기록 | 데이터 유실 | P1 | ✅ |
| 3 | Refresh Token 전달 방식 미결 (쿠키 vs 바디) | 정책 결정 | P2 | ✅ |
| 4 | 계정 통합 정책 부재 (카카오+구글 동일인) | 정책 결정 | P2 | ✅ |
| 5 | 위치정보법·개인정보 고지 검토 | 법적 검토 | P2 | ✅ |
| 6 | 테스트 스키마 드리프트 (H2 vs 운영 MySQL) | 테스트 전략 | P3 | ✅ |
| 7 | API 계약 문서 부재 → OpenAPI 자동화 | 협업 | P3 | ✅ |
| 8 | CI/CD·배포 전략 없음 | 운영 | P3 | ✅ (최소선) |
| 9 | Redis 단일 장애점 (Refresh Token 저장소) | 운영 | P3 | ✅ |
| 10 | DB 백업·복구(RPO/RTO) 정책 없음 | 운영 | P3 | ✅ |
| 11 | 스케줄러 다중 인스턴스 중복 실행 | 운영 | P3 | ✅ (문서화, 코드 대응은 스케일아웃 시점) |
| 12 | 성능 목표 검증(부하 테스트)·모니터링 최소선 | 관측성 | P3 | ✅ (최소선) |
| 13 | 요구사항·KPI·일정 문서화 | 기획 | P3 | ✅ |

---

## P1 — 실제 버그·데이터 유실

### 1. 이메일 UNIQUE 충돌 시 500 ✅ (2026-07-06 해소)

> **해소**: 4번 정책을 "이메일 기준 자동 통합"으로 결정하고 구현. `createNewUser`가 가입 전 이메일로 기존 유저를 조회해, 있으면 해당 유저에 auth_provider 행만 추가한다. [Planning.md 4.1](Planning.md#41-소셜-로그인-플로우) 반영.

**문제**: `users` 테이블에 `uq_users_email`(UNIQUE) 제약이 있다. 같은 이메일을 쓰는 사용자가 카카오로 가입한 뒤 구글로 로그인하면, `createNewUser`의 유저 INSERT가 UNIQUE 위반으로 실패한다. 예외 catch가 `(provider, socialId)` 기준으로 재조회하는데 이 조합의 행은 존재하지 않으므로 `INTERNAL_SERVER_ERROR`(500)로 끝난다.

**위치**: `AuthService.createNewUser`, `V1__init_schema.sql`의 `uq_users_email`

**해결 방향** (4번 계정 통합 정책 결정과 연동):
- 통합 정책 채택 시: 가입 전 이메일로 기존 유저 조회 → 있으면 해당 유저에 auth_provider 행만 추가 (자동 연동)
- 분리 정책 채택 시: `uq_users_email` 제약 제거 (이메일은 소셜 제공 참고 정보일 뿐 식별자가 아님)

### 2. Pick 시 histories.restaurant_id 미기록 ✅ (2026-07-06 해소)

> **해소**: 픽 시점에 대표 식당을 기본 기록하고, 방문 처리 시 덮어쓸 수 있게 구현. 픽 시 위치가 있으면 최근접 식당, 없으면 연결 식당이 하나뿐일 때만 기록. `PATCH /history/{id}/visit` 바디(선택)에 `restaurantId`를 받아 실제 방문 식당으로 갱신.

**문제**: 스키마와 Planning.md 모두 `histories.restaurant_id`를 "추천된 식당 참조"로 정의하지만, `PickService.saveHistory`는 이 값을 채우지 않아 항상 NULL이다. "그때 어디 갔었지" 회고 기능의 절반(식당)이 데이터가 없어 동작할 수 없고, 데이터는 지금부터 쌓여야 나중에 쓸 수 있다.

**위치**: `PickService.saveHistory` (History.builder에 restaurant 미전달)

**해결 방향**: 추천 결과의 식당 목록 중 대표 식당(최근접 또는 사용자가 선택한 식당)을 기록. 픽 시점에는 식당이 확정되지 않는다면, 방문 처리(`PATCH /history/{id}/visit`) 시 restaurantId를 받는 방식도 검토.

---

## P2 — 정책 결정 필요

### 3. Refresh Token 전달 방식 미결 ✅ (2026-07-06 결정·구현)

> **결정**: 클라이언트가 웹 기반으로 확정되어 **HttpOnly Cookie** 방식 채택·구현. 쿠키 속성은 `HttpOnly + Secure + SameSite=Strict + Path=/api/v1/auth`, CSRF는 SameSite=Strict로 방어(재설계 불필요). 프론트·API 동일 사이트 배포 제약 포함 근거를 [Planning.md 4.2](Planning.md#42-jwt-토큰-정책)에 반영.

**문제**: Planning.md 4.1은 "Refresh Token은 HttpOnly Cookie 저장 권장"인데 실제 구현은 JSON 바디로 반환하며, CSRF는 disable 상태다. 문서와 구현이 모순이고, 어느 쪽으로 갈지 결정 자체가 없다.

**선택지**:
| 방식 | 장점 | 단점 |
| --- | --- | --- |
| HttpOnly Cookie | XSS로 탈취 불가 | CSRF 방어 재설계 필요 (현재 disable), 모바일 앱 클라이언트와 궁합 나쁨 |
| JSON 바디 (현행) | 클라이언트 유형 무관, 구현 단순 | XSS 시 탈취 가능 — 저장 위치를 클라이언트가 책임 |

**해결 방향**: 클라이언트 유형(웹/앱)을 확정한 뒤 결정하고, 결정 근거를 Planning.md 4.1~4.2에 반영. 현행 유지 시 "바디 반환 + 클라이언트 보안 저장 책임" 명시.

### 4. 계정 통합 정책 부재 ✅ (2026-07-06 결정)

> **결정**: ① 이메일 기준 자동 통합 채택. 1번 버그 수정과 함께 구현 완료, [Planning.md 4.1](Planning.md#41-소셜-로그인-플로우) 반영.

**문제**: `auth_providers`는 유저 1명 : 소셜 N개 연동을 전제로 설계됐지만, 로그인 로직은 provider별로 새 유저를 생성한다. 같은 사람이 카카오와 구글로 각각 로그인하면 계정이 2개 생기며(1번 버그가 없었어도), 메뉴·히스토리가 분산된다.

**선택지**: ① 이메일 기준 자동 통합 ② 마이페이지에서 수동 연동 ③ 통합하지 않음(계정=소셜 단위 명시)

**해결 방향**: 정책 결정 후 1번 버그 수정과 함께 구현. Planning.md 4.1에 반영.

### 5. 위치정보법·개인정보 고지 검토 ✅ (2026-08-05 1차 검토 완료)

> **검토 결과**: Pick 시 수신하는 좌표는 개인위치정보로, MenuPick은 위치기반서비스사업 신고 대상으로 판단된다(원본 좌표를 저장하지 않아도 "이용" 자체가 요건). 다만 소상공인/1인창조기업 특례로 **사업 개시 후 1개월까지는 신고 유예** 가능 — 단 사업자등록이 전제이므로 공개 전 사업자 지위 확정이 선행 과제다. 벌칙은 미신고 시 3년 이하 징역/3천만원 이하 벌금으로 가볍지 않아, 공개 직전 방송미디어통신위원회(02-588-0185)에 최종 확인을 권장한다. 개인정보처리방침·이용약관·OAuth 동의 문구 초안을 작성했다. 상세: [PrivacyReview.md](PrivacyReview.md).

**문제**: 위치 기반 추천(좌표 수신·거리 계산)을 하면서 법적 검토 흔적이 문서에 없다.
- 한국 위치정보법상 위치기반서비스사업 신고 대상 여부 확인 (개인·소규모 면제 조항 포함)
- 탈퇴 후 30일 보존·파기 정책은 구현됐으나 사용자 고지(약관/개인정보처리방침) 없음
- OAuth로 수집하는 항목(이메일, 닉네임)의 수집·이용 동의 명시

**해결 방향**: 서비스 공개 전 체크리스트로 검토. 결과를 Planning.md에 별도 절로 기록.

---

## P3 — 전략·문서·운영 보강

### 6. 테스트 스키마 드리프트 ✅ (2026-08-05 해소)

> **해소**: Repository 슬라이스 테스트(`UserRepositoryTest`, `AuthProviderRepositoryTest`, `HistoryRepositoryTest`, `MenuRepositoryTest`, `RestaurantRepositoryTest`, `TagRepositoryTest`, `UserHardDeleteServiceTest`) 7개를 Testcontainers 실 MySQL 8.0 + Flyway 마이그레이션(`ddl-auto=validate`) 기반으로 전환했다. `AbstractIntegrationTest`(`src/test/java/.../support/`)가 `@Testcontainers`로 MySQL 컨테이너를 띄우고 `@DynamicPropertySource`로 datasource를 주입, `application-integration.yml`이 `integration` 프로파일로 Flyway를 켠다. Service/Controller 단위·슬라이스 테스트는 기존 H2(`test` 프로파일)를 그대로 유지 — 빠른 피드백이 중요한 계층이라 실 DB로 옮길 필요가 없다.
>
> **로컬 실행 조건**: 이 7개 클래스는 Docker가 떠 있어야 통과한다(로컬 dev 환경은 이미 docker-compose로 MySQL/Redis를 띄우는 전제라 문제 없음). 이번 세션의 개발 샌드박스에는 Docker 데몬이 없어 `Previous attempts to find a Docker environment failed`로 7개 클래스만 실패하고 나머지 185개는 전부 통과함을 확인했다 — Docker 미가용 환경에서의 예상된 실패이지 로직 문제가 아니다. Docker가 있는 환경(로컬 실제 머신, 8번 CI)에서 최종 재검증 필요.
> 커버리지 기준(예: 서비스 레이어 80%)은 별도 과제로 남겨둔다.

**문제**: 테스트는 H2(MySQL 모드) + `ddl-auto=create-drop` + Flyway off로 돌아, 테스트 스키마(Hibernate 생성)와 운영 스키마(Flyway)가 다르다. 이미 FK의 `ON DELETE CASCADE` 유무가 다르며, 방언 차이로 운영에서만 터지는 쿼리가 생길 수 있다.

**해결 방향**: 통합 테스트를 Testcontainers(실 MySQL + Flyway 마이그레이션 적용)로 전환. 단위/슬라이스 테스트는 H2 유지 가능. 커버리지 기준(예: 서비스 레이어 80%)도 함께 정의.

### 7. API 계약 문서 부재 ✅ (2026-08-05 해소)

> **해소**: `springdoc-openapi-starter-webmvc-ui` 도입. `OpenApiConfig`(`common/config/`)가 JWT Bearer 보안 스킴을 등록해 Swagger UI에서 바로 인증 테스트가 가능하다. 노출은 기본 false(`application.yml`)이고 `application-local.yml`에서만 true로 켜지므로 운영 배포 시 별도 프로파일에서 값을 지정하지 않는 한 `/swagger-ui`, `/v3/api-docs`는 닫혀 있다. `SecurityConfig`에 해당 경로 permitAll 추가. 전체 테스트 회귀 없음 확인(192개 중 Docker 미가용 7개만 실패, 8번 CI 항목과 동일 원인).

**문제**: 엔드포인트 목록만 있고 요청/응답 스키마, 에러 코드 카탈로그(`ErrorCode` enum 미문서화)가 없다. 프론트 협업 시작 시 병목이 된다.

**해결 방향**: springdoc-openapi 도입으로 코드에서 자동 생성 (수기 문서는 이번 Planning.md처럼 금방 낡는다). `/swagger-ui` 노출은 local/dev 프로파일 한정.

### 8. CI/CD·배포 전략 없음 ✅ (2026-08-05 최소선 구축)

> **구현**: `.github/workflows/ci.yml` — PR(→main/dev)마다 `./gradlew test` 실행, main 머지 시 `docker-build` 잡이 `Dockerfile`로 이미지를 빌드해 GHCR(`ghcr.io/<repo>:sha`, `:latest`)에 push한다. `GITHUB_TOKEN`만으로 인증되어 별도 레지스트리 계정/시크릿 설정이 필요 없다. 루트에 멀티스테이지 `Dockerfile`(build: JDK 17 + bootJar, runtime: JRE 17)을 신설했다.
> Testcontainers(6번) 기반 통합 테스트는 GitHub Actions `ubuntu-latest` 러너에 기본 탑재된 Docker 데몬으로 별도 서비스 컨테이너 설정 없이 그대로 동작한다.
> `application-dev.yml`/`application-prod.yml` 스켈레톤도 함께 신설([Planning.md 6.1](Planning.md#61-환경-구성)) — 실제 호스트 값은 배포 대상 확정 후 주입.
> 배포 대상은 Oracle Cloud Free Tier로 잠정 확정(사용자 확인, 미확정)되어 `docker-compose.prod.yml` + `.env.prod.example`을 준비했다([DecisionLog.md D-023](DecisionLog.md#d-023-배포-대상--oracle-cloud-free-tier-잠정)). PaaS가 아니라 VM 직접 운영이라 MySQL/Redis/앱을 한 대에서 함께 띄우는 구조다.
> **보류 항목**: 실제 VM 프로비저닝·최초 배포 실행, 롤백 절차. 이건 사람이 실제 서버에 접근해 진행해야 하는 운영 작업이라 이번 세션 범위 밖이다.

**문제**: Docker Compose 언급이 전부. CI 파이프라인, 배포 대상, 롤백 절차가 없다.

**해결 방향**: 최소선 — GitHub Actions로 PR마다 `gradlew test` 실행, main 머지 시 이미지 빌드. 배포 대상 확정 후 배포 파이프라인·롤백 절차 문서화.

### 9. Redis 단일 장애점 ✅ (2026-08-05 리스크로 명시)

> **해소**: [Planning.md 8장 리스크 표](Planning.md#8-리스크-및-대응-방안)에 "Redis 장애(단일 장애점)" 행을 추가하고, "장애 시 전 사용자 재로그인 감수 + 신속 재기동"을 명시적 결정으로 채택했다. AOF persistence 활성화는 실제 운영 Redis 구성 시점의 인프라 작업으로 남겨둔다(코드 변경 대상이 아님).

**문제**: Rate limit은 fail-open으로 Redis 장애를 흡수하지만, Refresh Token 저장소로서의 Redis가 죽으면 로그인·재발급 전체가 불능이다. Planning.md 8장 리스크 표에 Redis 장애 항목 자체가 없다.

**해결 방향**: 규모상 HA(Sentinel)가 과하다면 "장애 시 전 사용자 재로그인 감수 + 신속 재기동" 을 명시적 결정으로 리스크 표에 추가. AOF persistence 활성화로 재기동 시 토큰 유실 최소화.

### 10. DB 백업·복구 정책 없음 ✅ (2026-08-05 정책 수립)

> **해소**: [Planning.md 7.4 백업 및 복구 정책](Planning.md#74-백업-및-복구-정책) 신설. 백업 주기·보존기간·RPO/RTO 목표치와, 하드삭제 배치가 남기는 `userId` 로그를 활용한 오삭제 복구 절차를 정의했다. 실제 백업 도구 적용은 운영 DB 확정(8번 CI/CD와 연동) 이후 후속 작업.

**문제**: 백업 주기, RPO/RTO가 없다. 탈퇴 유저 하드삭제 배치가 도입되어 실수 삭제를 복구할 수단이 백업뿐이므로 중요도가 올라갔다.

**해결 방향**: 운영 전환 시 일일 백업 + 보존 기간 정의. 하드삭제 배치는 삭제 대상 로그를 남기고 있으므로(userId), 백업과 조합해 오삭제 복구 절차 문서화.

### 11. 스케줄러 다중 인스턴스 중복 실행 ✅ (2026-08-05 문서화)

> **해소**: [Planning.md 4.4](Planning.md#44-회원-탈퇴-및-재가입-정책)와 8장 리스크 표에 "단일 인스턴스 배포 전제"를 명시하고, 스케일 아웃 시점에 ShedLock(Redis 기반) 도입을 후속 계획으로 기록했다. 현재는 단일 인스턴스 운영이라 코드 변경은 보류.

**문제**: `WithdrawnUserCleanupScheduler`는 단일 인스턴스를 가정한다. 스케일 아웃하면 인스턴스마다 배치가 돌아 중복 실행된다 (현재 로직은 멱등에 가까우나 보장 없음).

**해결 방향**: 당장은 "단일 인스턴스 전제"를 문서에 명시. 스케일 아웃 시점에 ShedLock(Redis 기반) 도입.

### 12. 성능 목표 검증·모니터링 최소선 ✅ (2026-08-05 최소선 구축)

> **구현**: `spring-boot-starter-actuator` 추가, `/actuator/health`(SecurityConfig permitAll, `show-details: never`로 비인증 호출자에게 상세 정보 비노출)와 `/actuator/metrics`(인증 필요)만 노출(`management.endpoints.web.exposure.include`). `scripts/k6/load-test.js`에 Planning.md 7.1 성능 목표(조회 P95<300ms, 픽 P95<500ms)를 threshold로 반영한 부하 테스트 스크립트를 작성했다 — 메뉴 목록 조회와 랜덤 픽 시나리오를 20 VU로 1분간 구동한다.
>
> **보류 항목**: 로그인 API는 OAuth 인가 코드가 실제 카카오/구글 리다이렉트를 거쳐야 해 스크립트로 자동화할 수 없다 — 스크립트는 사전에 수동으로 발급한 `ACCESS_TOKEN`을 받아 인증 API(픽, 메뉴 목록)만 부하 테스트한다. 부하 테스트 실행 자체(k6가 실제 대상 서버에 트래픽을 쏘는 행위)는 배포 전 사람이 판단해 실행할 운영 작업이라 이번 세션에서 실행하지 않았다. 장애 알림 채널(디스코드 웹훅 등)은 실제 웹훅 URL 발급이 필요한 외부 인프라 설정이라 대상 확정 후 진행.

**문제**: P95 300ms 등 목표 수치는 있으나 검증 수단(부하 테스트)이 없고, 모니터링은 "APM 고려" 수준이다.

**해결 방향**: 배포 전 k6 등으로 핵심 API(픽, 메뉴 목록, 로그인) 부하 테스트 1회. Spring Actuator health/metrics 노출 + 장애 알림 채널(예: 디스코드 웹훅) 최소 구성.

### 13. 요구사항·KPI·일정 문서화 ✅ (2026-08-05 해소)

> **해소**: [Requirements.md](Requirements.md) 신설. 핵심 유저 스토리 6개(수용 기준 포함), 추천 품질 KPI 3개(픽 후 방문율/재픽률/7일 리텐션, 기존 `histories.is_visited` 스키마로 바로 계산 가능), Phase 4~6 목표 시점 초안을 담았다. 날짜는 제안일 뿐 확정 일정이 아니라고 문서에 명시했다 — 일정 확정은 사용자 몫이다.

**문제**: Planning.md가 기술 설계에서 시작해 유저 스토리·수용 기준이 없고, 참조하는 "기획서"가 리포지토리에 없다. `is_visited` 데이터를 모으지만 추천 품질 지표(픽 후 방문율, 재픽률 등) 정의가 없어 데이터가 의사결정에 쓰이지 못한다. Phase에 기간·마일스톤이 없다.

**해결 방향**: 간단한 요구사항 문서(핵심 유저 스토리 + 수용 기준)와 측정할 지표 2~3개를 docs에 추가. Phase별 목표 시점 부여.

---

## 2026-08-10 코드 리뷰 배치 (GitHub 이슈 #3~#19) 처리 결과

2026-08-08 자동 코드 리뷰로 등록된 이슈 17건을 처리했다. 위 1~13번과 달리 GitHub 이슈로 추적했으므로 상세 내용은 각 이슈를 참조한다.

| 이슈 | 내용 | 상태 |
| --- | --- | --- |
| #3 | X-Forwarded-For 스푸핑으로 레이트리밋 우회 | ✅ |
| #4 | OAuth 로그인 CSRF (state 파라미터) | ✅ (PKCE는 아래 보류) |
| #5 | OAuth 프로바이더 견고화 | ✅ |
| #6 | 요청 DTO 입력 검증 공백 | ✅ |
| #7 | 메뉴 수정 시 isExcluded 유실 | ✅ |
| #8 | 외부 API 클라이언트 오류 처리 | ✅ |
| #9 | Redis 장애의 프록시 전파 (CacheErrorHandler) | ✅ |
| #10 | 외부 API 프록시 레이트리밋 부재 | ✅ (키 기준은 아래 보류) |
| #11 | 타임존 미고정 (Clock 주입) | ✅ — [Planning.md 7.4](Planning.md#74-시각-처리-정책) |
| #12 | application-test.yml 운영 JAR 패키징 | ✅ |
| #13 | Docker/배포 하드닝 | ✅ |
| #15 | 풀 불균형·트랜잭션 내 외부 호출·블로킹 프록시 | ✅ |
| #16 | 성능 개선 모음 | ✅ (푸시다운은 아래 보류) |
| #17 | 테스트 공백 | ✅ |
| #18 | 보안·안정성 개선 모음 | ✅ (일부 아래 보류) |
| #19 | 도메인 코드 정리 모음 | ✅ (일부 아래 보류) |

이 과정에서 **필터 없는 픽 응답이 500으로 실패하는 버그**를 새로 발견해 함께 고쳤다. `PickService.toDetail`이 LAZY 컬렉션을 그대로 DTO에 담아, 카테고리 필터가 없는 픽(가장 흔한 경로)에서 `open-in-view=false` 환경의 직렬화 시점에 `LazyInitializationException`이 발생했다. 컨트롤러 슬라이스 테스트는 서비스를 목으로 대체해 이 회귀를 구조적으로 잡을 수 없어, 실제 직렬화까지 타는 통합 테스트를 추가해 고정했다.

---

## 보류 항목 (의도적 미해결)

아래는 이번 배치에서 **의식적으로 미룬** 항목이다. 배포 구성이 확정돼야 판단할 수 있거나, 현재 규모에서 실익보다 비용이 큰 것들이다.

| 항목 | 출처 | 보류 사유 · 재검토 시점 |
| --- | --- | --- |
| OAuth PKCE 도입 | #4 | state로 로그인 CSRF는 차단된다. PKCE는 백엔드 토큰 교환까지 바꿔야 해 별도 과제로 분리 — 모바일 클라이언트 추가 시 필수가 되므로 그때 도입 |
| 프록시 레이트리밋을 사용자 ID 기준으로 | #10 | 현재 IP 기준으로 동작한다(RateLimitFilter가 SecurityConfig 등록 순서상 JWT 필터보다 먼저 실행). 코드는 principal이 있으면 사용자 키를 쓰도록 이미 준비돼 있어 **필터 순서만 바꾸면 전환**된다. 쿼터 보호 목적은 IP 기준으로도 달성돼 필터 체인 재배치는 미룸 |
| Access Token 블랙리스트 (jti) | #18 | 로그아웃·탈퇴 후에도 AT가 최대 30분 유효하다. Redis 조회를 모든 요청에 추가하는 비용이 있어, 탈퇴 이벤트가 실제 문제로 관측될 때 도입 |
| `SameSite=None` 전환 | #18 | 프론트·API를 같은 사이트로 배포하면 불필요하다. **배포 도메인 확정 시 반드시 재검토** — 다른 사이트로 갈라지면 refresh 쿠키가 전송되지 않아 항상 401이 된다 |
| ShedLock (스케줄러 분산 락) | #18 | 단일 인스턴스 배포 전제. 하드 삭제는 멱등에 가까워 중복 실행 피해가 없다. 스케일 아웃 시점에 도입 |
| Pick 필터 쿼리 푸시다운 | #16 | 현재는 사용자 메뉴 전량을 메모리에 올려 필터한다. 개인 메뉴 수십~수백 건 규모에서는 병목이 아니며, 푸시다운은 쿼리 복잡도를 크게 올린다. 사용자당 메뉴가 수천 건이 되거나 픽 P95가 목표(500ms)를 넘으면 착수 |
| 목록 API 페이지네이션 일관화 | #16 | 메뉴만 커서 페이지네이션이고 식당·제외 목록·메뉴-식당 연결은 전량 반환한다. 개인 데이터라 규모가 작아 미룸 |
| FK `ON DELETE CASCADE` 전환 | #19 | 현재 하드 삭제는 `UserHardDeleteService.purge()`의 수동 삭제 순서에 의존한다. 자식 테이블 추가 시 purge 갱신을 빠뜨리면 배치가 매일 실패한다 — 스키마 변경 비용 때문에 미뤘으나 **테이블 추가 시 함께 검토** |
| 픽 최근 중복 회피 | #19 | 같은 메뉴가 연속으로 뽑힐 수 있다. 제품 결정 사항이라 [Specification.md 9.2](Specification.md)의 추천 고도화 과제로 이관 |
| 네이버 지도 API 구 도메인 | #8 | `naveropenapi.apigw.ntruss.com`이 신규 도메인(`maps.apigw.ntruss.com`)으로 이전 공지된 상태다. 실제 키 발급·콘솔 확인이 필요해 배포 담당 판단으로 남김 |

---

본 문서는 과제 해소 시 상태(⬜→✅)를 갱신하고, 정책 결정 사항은 Planning.md에 반영한 뒤 본 문서에서 링크한다.
