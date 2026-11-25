# 프로젝트 점검표 (외부 평가 기준 대조)

**점검일: 2026-11-25 / 기준 커밋: `main` `12e4a43`**

외부에서 쓰는 평가 기준에 이 프로젝트를 대 보고 빠진 것을 적는다. 상태가 바뀌면 이 표를 고쳐 쓴다.

## 참고한 기준

- [9 Criteria to Assess Your Project's Maturity](https://noti.st/petyodimitrov/mw7Bw7/slides) (DevDays Europe 2024):
  보안, 코드 품질, 배포성, 테스트, 운영성, 연동, 클라우드, 프로세스, 서드파티 의존성.
- [OWASP ASVS 4.0 V14 Configuration](https://github.com/OWASP/ASVS/blob/master/4.0/en/0x22-V14-Config.md):
  빌드·배포(14.1), 의존성(14.2), 의도치 않은 노출(14.3), 보안 헤더(14.4), 요청 헤더(14.5).
- [현직 백엔드 개발자가 신입에게 기대하는 역량](https://fastcampus.co.kr/story_article_keh) 외 포트폴리오 조언:
  기술을 왜 골랐는지, 문제를 어떻게 개선했는지, 기획부터 운영까지 다뤘는지.

## 기준별 상태

✅ 충족 · ⚠️ 일부 · ⏸️ 보류(의도적)

| 기준 | 상태 | 근거 | 빠진 것 / 후속 |
| --- | --- | --- | --- |
| 테스트 | ✅ | JaCoCo 하한 LINE 91% / BRANCH 79%가 CI 필수 검사(D-030). Testcontainers 통합 테스트, Vitest, Playwright E2E | — |
| 성능 테스트 | ✅ | k6 4회차, 100 rps 픽 P95 30ms, 천장 ≈390 rps([LoadTestResults.md](LoadTestResults.md)) | `heavy`(메뉴 수 민감도)·`soak`(누수) 미실행 |
| CI/CD·배포 | ✅ | 필수 검사 6개, GHCR 이미지 전체 SHA 태그, `APP_VERSION` 한 줄 롤백, [`deploy/oci/deploy.sh`](../deploy/oci/deploy.sh)(D-091) | — |
| 보안: 의존성·정적 분석 | ✅ | CodeQL, Dependabot, 이미지 스캔(Trivy), npm audit가 필수 검사 | CodeQL #5·#6(k6 스크립트의 `Math.random`, 오탐) 해제는 저장소 관리자가 한다 |
| 보안 헤더 (ASVS 14.4) | ⏸️ | nosniff, X-Frame-Options, Referrer-Policy, Permissions-Policy, `server_tokens off`, 최소 CSP | **HSTS 없음**, 엄격한 CSP는 Report-Only. 실제 도메인·신뢰 인증서가 붙어야 켤 수 있다(D-034) — 아래 보류 4 |
| 운영성 | ⏸️ | 헬스체크, unhealthy 자동 복구, 디스크·ERROR 로그 감시, 일일 백업, journald 영속화 | 지표가 그 순간 값뿐, 알림 웹훅·오프사이트 백업 PAR 미설정, 복원 리허설 기록 없음 — 아래 보류 5 |
| API 문서 | ✅ | [Specification.md](Specification.md), springdoc(운영에서는 꺼 둠) | — |
| 코드 품질 도구 | ✅ | 프론트 oxlint, 백엔드 Checkstyle(버그성 규칙, 위반 0에서 시작, D-091) | 모양(포매터)은 일부러 강제하지 않는다 |
| 서드파티 의존성 | ✅ | Dependabot, CycloneDX SBOM이 jar 안과 CI 아티팩트에(D-091) | — |
| 요청 헤더·CORS (ASVS 14.5) | ✅ | CORS 허용 목록(`CorsProperties`), 관리 포트는 컨테이너 루프백에만 | — |
| 문서 | ✅ | DecisionLog(D-001~), 설계 명세, 개인정보 검토, 부하 시험 계획·결과, README 요약 | — |
| 제품 지표 | ⚠️ | `GET /actuator/kpi`로 방문율·재픽률·7일 리텐션 집계 | 실사용자가 없어 표본이 0 |
| 공개 준비 | ⚠️ | [PrivacyReview.md](PrivacyReview.md) 1차 검토 | 위치기반서비스 신고, 개인정보처리방침·약관 게시(Requirements.md Phase 6) |

## 이번에 고친 것 (2026-11-25)

- 백엔드 Checkstyle 추가, 첫 실행 위반 11건 정리(쓰지 않는 임포트 10, 빈 catch 1).
- CycloneDX SBOM을 bootJar와 CI 아티팩트로.
- 서버 `/tmp`에만 있던 배포 스크립트를 `deploy/oci/deploy.sh`로 옮기고 40자 SHA 검사를 더함.
- README 첫 화면을 다시 씀(구성도, 성과 수치, 스크린샷, 결정 기록 링크). 오래된 상태 문구 갱신.

## 보류

### 4. 실제 도메인과 TLS 강화

- **지금**: 접속 주소가 IP이고 인증서가 자체 서명이다. 이 상태로 HSTS를 보내면 브라우저가 예외 통과를
  막아 되돌릴 수 없는 잠금이 된다(`deploy/oci/nginx-tls.conf` 주석, DecisionLog D-034).
- **할 일**: 도메인 구매(사람이 한다) → DNS A 레코드 → Let's Encrypt(certbot 또는 nginx 컨테이너 옆 ACME) →
  짧은 `max-age`의 HSTS부터 → Report-Only CSP 위반이 없음을 확인하고 강제 CSP로 전환 →
  `SameSite`·CORS 허용 목록·카카오 키 도메인 화이트리스트를 새 도메인으로.
- **트리거**: 공개(Phase 6)를 정할 때.

### 5. 알림과 서버 밖 백업 마무리

- **지금**: 알림 유닛(`menupick-alert@.service`)과 오프사이트 업로드는 깔려 있지만 자격증명이 비어 있어
  아무 데도 가지 않는다. 지표는 actuator의 그 순간 값뿐이다.
- **할 일**: 웹훅 URL을 `/etc/menupick/alert.env`에, 쓰기 전용 PAR을 `/etc/menupick/offsite.env`에
  (둘 다 사람이 발급해 넣는다, URL 자체가 자격증명) → 점검 알림 한 줄 확인 → 백업을 빈 DB에 복원해 보는
  리허설을 한 번 하고 걸린 시간을 이 표에 적는다. 지표 시계열(Prometheus 등)은 공개 뒤 트래픽이 생기면.
- **트리거**: 자격증명이 준비되는 대로.
