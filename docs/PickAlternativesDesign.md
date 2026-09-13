# 픽 후보 없음 — 안전한 조건 조정 대안 설계

**상태: 운영 배포·활성화 완료 / 기준일: 2026-09-14**

이 문서는 수동 픽에서 조건 때문에 후보가 0개일 때, 사용자가 명시적으로 선택할 수 있는
최소 범위의 조정안을 제시하는 계약이다. 코드의 기능 플래그 기본값은 `false`를 유지하며,
운영에서는 `main` SHA `165b154b6c653584a42b2ab260f320c2efb785e9`로 두 플래그를 활성화했다.

## 1. 목표와 범위

- 대상은 인증 사용자의 일반 `POST /api/v1/pick`이 `NO_PICK_CANDIDATES`에 도달한 경우뿐이다.
- `NO_PICKABLE_MENUS`(픽 가능한 메뉴 자체가 없음), `NO_LINKED_RESTAURANTS`(연결 식당 없음)는
  데이터 보완이 필요한 상태이므로 대안을 제시하지 않는다.
- 게스트 데모와 프리셋 실행은 1차 범위 밖이다. 프리셋 조건을 조용히 바꾸거나 저장하지 않는다.
- 진단은 픽도 예약도 아니며 히스토리를 생성하지 않는다. 사용자가 대안을 적용한 뒤 기존
  `POST /api/v1/pick`을 다시 호출한 결과만 권위 있는 픽이다.
- DB 스키마와 Flyway 마이그레이션은 추가하지 않는다.

## 2. 절대 완화하지 않는 안전 경계

- 요청의 `tagIds`, `excludeTagIds`와 계정의 기본 제외 태그는 자동으로 제거하거나
  무시하지 않는다. 알레르기 여부를 서버가 추정하거나 안전을 보장하지 않는다.
- 카테고리는 OR 조건이므로 일부만 제거하면 어떤 의도를 우선했는지 서버가 임의 판단하게 된다.
  따라서 카테고리 조정은 전체 해제(`CLEAR_CATEGORIES`)만 허용한다.
- 거리 조정은 위치(`latitude`, `longitude`)와 `maxDistance`가 모두 유효할 때만 가능하다.
- 후보 수가 0인 대안은 응답에서 숨기며 최대 2개만 반환한다.

## 3. 대안 생성 규칙

입력을 기존 `PickRequest`와 같은 규칙으로 정규화·검증한 뒤 원 조건의 실제 후보가 0인지 먼저
확인한다. 이미 후보가 있으면 `200`과 빈 배열을 반환한다.

1. `EXPAND_DISTANCE`: `[300, 500, 1000, 2000, 5000]`미터 중 현재 거리보다 큰 단계를
   오름차순으로 평가해, 후보가 처음 생기는 최소 단계 하나만 채택한다.
2. `CLEAR_CATEGORIES`: 카테고리 전체를 해제했을 때 후보가 있으면 채택한다.
3. 위 두 단독 대안의 후보 수가 모두 0일 때만 `CLEAR_CATEGORIES_AND_EXPAND_DISTANCE`를
   평가한다. 거리 단계 중 결합 후보가 처음 생기는 최소 단계 하나만 채택한다.
4. 단독 대안이 하나라도 성공하면 결합 대안은 반환하지 않는다. 순서는 항상 거리 확대,
   카테고리 해제이며 전체 응답은 최대 2개다.

`candidateCount`는 단순 SQL 행 수가 아니라 실제 가중치 풀의 **서로 다른 메뉴 수**다. 최근
3일에 추천되지 않은 후보(`fresh`)가 하나라도 있으면 fresh 풀을 세고, 모든 후보가 최근 추천
메뉴면 원본 풀로 폴백하여 센다. 가중치는 추첨 확률에만 영향을 주며 같은 메뉴를 중복 집계하지
않는다.

## 4. API 계약

### `POST /api/v1/pick/alternatives`

- 인증 필수. 요청 본문은 기존 `PickRequest`의 null/빈 배열, 좌표·거리 결합, 문자열/집합
  검증과 개수 상한 의미를 그대로 사용한다.
- 성공은 후보 유무와 무관하게 `200`이다. 이미 원 조건으로 후보가 있거나 안전한 대안이 없으면
  `alternatives: []`다.
- 기존 Bean Validation 오류와 인증 오류를 그대로 유지한다. 카테고리나 tag ID에 대안 API만의
  신규 존재·소유권 검증을 추가하지 않는다.
- 픽과 같은 보안 필터를 통과시키고 전용 rate-limit 버킷/한도를 구성한다. 과도한 반복 평가와
  ID 열거를 막되 구체 값은 구현 시 부하 측정 후 확정한다.

응답 예시:

```json
{
  "success": true,
  "data": {
    "alternatives": [
      {
        "type": "EXPAND_DISTANCE",
        "candidateCount": 3,
        "changes": { "maxDistance": 1000 }
      },
      {
        "type": "CLEAR_CATEGORIES",
        "candidateCount": 5,
        "changes": { "categories": [] }
      }
    ]
  },
  "message": null
}
```

각 항목은 안정된 `type`, `candidateCount`, `changes`만 제공한다. 좌표, 태그 ID, 기존 카테고리
문자열, 기본 제외 태그나 그 밖의 민감 입력은 응답에 반사하지 않는다. 결합형 `changes`에는
`maxDistance`와 빈 `categories`만 들어간다. 클라이언트는 사람이 읽는 문구를 `type`에
따라 렌더링하며 서버 메시지 파싱에 의존하지 않는다.

카테고리는 소유 리소스 ID가 아니라 `Set<String>` 자유 문자열이다. 기존 `PickRequest`와 같이
최대 20개, 원소별 non-blank·20자 이하 검증과 trim 정규화를 적용하며, 저장된 카테고리와
일치하지 않는 문자열은 404가 아니라 후보 계산 결과에만 영향을 준다. `tagIds`와
`excludeTagIds`도 현재 일반 픽은 소유권/존재 사전 검증을 하지 않는다. 없는·타 사용자 포함
태그 ID는 보통 매칭 후보를 없애고, 제외 태그 ID는 매칭되지 않아 무시된다. 성공한 픽의
히스토리 이름 조회에서도 본인 태그만 이름으로 해석하고 나머지는 ID 문자열로 남긴다. 대안
API만 새 404를 만들지 않고 이 동작을 그대로 재사용한다.

백엔드 `PICK_ALTERNATIVES_ENABLED=false`이면 `@ConditionalOnProperty`로 컨트롤러를 등록하지
않아 인증된 직접 요청에도 endpoint 자체가 404가 되게 한다. 프론트도 빌드/런타임 플래그가
꺼져 있으면 대안 API를 호출하지 않고 UI를 렌더링하지 않는다. 실제 노출에는 백엔드 플래그와
프론트 빌드 플래그 `VITE_PICK_ALTERNATIVES_ENABLED=true`가 **둘 다** 필요하다. Vite 값은 빌드
시 정적으로 들어가므로 GitHub Actions variable을 바꾼 뒤 프론트 이미지를 다시 빌드·배포해야
한다. 한쪽만 켜진 상태는 지원되는 공개 상태가 아니다.

전용 고정 윈도우 버킷은 **IP당 10회/분**으로 확정한다. 기존 `RateLimitFilter`의
`PathPatternRequestMatcher`와 Lua `INCR`+`EXPIRE` 원자 처리 방식을 재사용하고 별도 Redis key
prefix와 설정값을 둔다. 현재 RateLimitFilter가 JWT 필터보다 먼저 실행되므로 사용자 ID가 아닌
검증된 client IP(`trustProxy`/`trustedProxyHops` 정책 포함)가 현실적인 주체다. 초과 시
`429 TOO_MANY_REQUESTS`와 `Retry-After: 60`, 공통 오류 body를 반환한다. Redis 예외나 null count는
기존 정책대로 fail-open하여 요청을 통과시킨다.

## 5. 백엔드 구조와 의미 일치

픽과 대안 진단이 각자 필터를 구현하면 화면의 후보 수와 실제 재픽이 어긋난다. 공통
`PickRequestNormalizer`(소유권·기본 제외 병합·검증)와 `PickCandidateEvaluator`(필터,
최근 3일 폴백, distinct 메뉴)를 추출해 두 경로가 사용해야 한다.

구현은 사용자 메뉴·카테고리·태그 native facts, 연결 식당, 최근 추천 signals를 고정 3 queries로
적재한 뒤 evaluator가 모든 변형 조건을 메모리에서 평가한다. 기본 제외 태그가 요청에서
`null`이면 최신 기본값 조회가 1건 추가되어 4 queries다. MySQL의 기존 category 비교 의미를
보존하려 native facts 쿼리는 원본 컬럼 collation인 `ai_ci` 비교를 사용한다. 대상 계약·쿼리
예산 테스트로 `PickCandidates`의 soft delete, 사용자 범위, include/exclude tag, 연결 식당,
거리 경계와 의미 일치를 검증했다. 최종 대안 예산은 3/4이며 기존 일반 픽 7/12, 프리셋 픽
14 예산은 변경하지 않는다. DB 마이그레이션은 없다.

동시 수정에 대한 예약이나 스냅샷 토큰은 두지 않는다. 대안 조회 후 메뉴·식당·기본 제외가
바뀌면 후보 수가 달라질 수 있으며, 최종 `POST /pick`이 현재 상태를 다시 검증한다. 실패하면
최신 조건으로 대안을 다시 조회한다.

## 6. 프론트엔드 상태 흐름

1. 수동 픽이 `NO_PICK_CANDIDATES`이면 같은 요청으로 대안 조회를 시작한다.
2. 입력을 정규화해 만든 fingerprint를 요청과 함께 메모리에 보관한다. 사용자가 조건을
   변경하면 진행 중 요청을 `AbortController`로 취소하고 이전 fingerprint 응답을 폐기한다.
3. 로딩, 대안 있음, 빈 결과, 오류 상태를 픽 오류와 구분한다. 대안 조회 실패가 원래 오류를
   가리거나 자동 재시도를 무한 반복해서는 안 된다.
4. 버튼에는 변경 내용과 후보 수를 텍스트로 제공하고 키보드 조작, 명확한 focus, `aria-live`
   결과 알림을 지원한다. 색상만으로 변경 위험을 표현하지 않는다.
5. 사용자가 버튼을 누를 때 화면의 조건 state만 명시적으로 갱신한 뒤 기존 수동 픽 API를
   호출한다. 프리셋 선택·실행 상태와 섞거나 프리셋을 수정하지 않는다.

## 7. 개인정보와 관측성

- 애플리케이션 로그·분석 이벤트에 좌표, 원시 태그 ID/카테고리 문자열, 메뉴명, 식당명 또는 전체
  요청 본문을 남기지 않는다.
- 허용 metric/event dimension은 결과(`offered`, `empty`, `error`), 대안 type, 제시 개수,
  적용 여부, 최종 픽 성공 여부와 지연 구간이다. 사용자 식별자와 고카디널리티 fingerprint는
  metric label로 쓰지 않는다.
- `candidateCount`는 해당 사용자의 데이터에 대한 수치일 뿐 안전성·영업 여부·재고 또는
  실제 방문 가능성을 보장하지 않는다는 UI 문구를 둔다.

## 8. 테스트 매트릭스

기능 대상 테스트와 리포 전체 check를 모두 통과했다. 백엔드는 764 tests / 0 failures,
JaCoCo instruction 93% / branch 81%였고, 프론트는 lint·build, Vitest 29 files / 363 tests,
Playwright 3/3을 통과했다. stale failed-request race 회귀를 수정한 뒤 최종 Astra 리뷰에서도
blocker 0건을 확인했다. PR #254~#257 CI와 2026-09-14 운영 smoke까지 통과했다.

| 영역 | 필수 검증 |
| --- | --- |
| 범위 | 수동 `NO_PICK_CANDIDATES`에서만 제시; `NO_PICKABLE_MENUS`, `NO_LINKED_RESTAURANTS`, 데모·프리셋 제외 |
| 안전 | include/exclude/default-exclude가 모든 대안에서 유지되고 응답에 반사되지 않음 |
| 거리 | 위치/거리 없으면 미제시; 경계값과 300→500→1000→2000→5000 첫 성공; 5000 초과 없음 |
| 카테고리 | OR 목록 전체 해제만 수행; 일부 제거 대안 없음; 원래 빈 목록이면 미제시 |
| 조합·순서 | 거리 우선, 카테고리 다음, 단독 모두 0일 때만 결합; 0후보 숨김; 최대 2개 |
| 후보 의미 | fresh 존재 시 fresh distinct 메뉴, 전부 recent면 원본 폴백, 가중치·다중 식당/태그로 중복 집계 없음 |
| API | 원 조건 후보 존재/안전 대안 없음은 200 빈 배열; stable shape; 자유 문자열 category 검증; 기존 tag ID 의미; 인증·429 |
| 의미 일치 | 같은 정규화 입력에서 evaluator 수와 실제 픽 가능 여부가 일치하는 contract/property 테스트 |
| 동시성 | 조회 후 메뉴/연결/기본 제외 변경 시 최종 픽이 현재 상태로 성공 또는 정상 실패 |
| 성능 | JDBC count 실측, N+1 부재, 후보 규모별 메모리·P95, 기존 7/12/14 회귀 없음 |
| 프론트 | fingerprint stale 응답 폐기, abort, loading/empty/error, 재픽, 키보드·focus·screen reader |
| 레이트 리밋 | 전용 IP 버킷 10회/분; 11번째는 429 + `Retry-After: 60`; Redis 오류/null은 fail-open; 경로 인코딩 우회 방지 |
| 플래그 | 기본 false에서 조건부 빈 미등록으로 인증 요청도 endpoint 404, 프론트 미호출·미노출; true에서만 활성화 |

## 9. 출시 계획과 성공 기준

1. 구현 PR을 병합하되 두 플래그를 기본 `false`로 유지하고 배포 회귀를 확인한다.
2. GitHub Actions variable과 백엔드 환경 변수를 함께 켜 프론트를 재빌드한 뒤 개발 환경과
   내부 계정에서 후보 수-실제 픽 일치율, 오류율, P95, DB 왕복을 측정한다.
3. 제한된 사용자에게 점진 활성화하고 이상 시 두 플래그를 끈다. 스키마 롤백은 필요 없다.

성공 지표는 `NO_PICK_CANDIDATES` 세션 중 대안 제시율, 대안 적용률, 적용 후 픽 성공률,
후보 없음 이탈률 변화다. 안전 가드 위반은 0건이어야 하며, 기존 픽 오류율·P95와 DB 포화가
유의하게 악화되면 롤아웃을 중단한다. 목표 수치는 베이스라인 수집 후 확정한다.
운영 롤아웃은 시작했으며 성공 지표 베이스라인은 아직 없다.
