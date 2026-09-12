# Pick Trends 인수인계

- 기본 `trends.enabled=false`; disabled는 DB 스캔·잠금을 하지 않는다.
- 집계는 `[now-windowDays, now)`이며 탈퇴 유예 중 사용자·미래 기록·자유 문자열을 제외한다.
- native query의 `ACCEPTED`는 문자열로 바인딩해 MySQL/H2 enum 서수 바인딩에 의존하지 않는다.
- singleton `PESSIMISTIC_WRITE`와 단일 트랜잭션으로 동시 재집계를 직렬화하고 실패 시 이전
  스냅샷을 보존한다.
- GET은 인증 필수, actuator POST는 관리 포트 전용이다.
- 정확히 36시간은 `READY`; 초과·설정 불일치는 `STALE`. 탈퇴 반영은 최대 36시간 지연된다.
- V14 SHA-256와 픽 query budget 7/12/14를 유지한다.
- 커밋·push·PR·배포는 범위 밖이다.
