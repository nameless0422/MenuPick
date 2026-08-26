-- =============================================
-- histories의 겹치는 user_id 선두 인덱스 정리 (코드 리뷰 2026-08-21, issue #87)
--
-- V6 이후 histories에 남은 user_id 선두 인덱스는 둘이다.
--   idx_histories_user_time (user_id, recommended_at DESC)  -- V1
--   idx_histories_user_id   (user_id, id DESC)              -- V3
-- 둘은 보완재가 아니라 **상호 배타적 선택지**다. 옵티마이저는 하나만 고르고, 어느 쪽을
-- 고르느냐에 따라 filesort가 붙거나 붙지 않는다. 즉 V3이 없앤 filesort가 통계 변화만으로
-- 되돌아올 수 있다 — 코드는 그대로인데 어느 날 갑자기 느려지는 종류의 회귀다.
--
-- HistoryRepository의 쿼리 전수(5개)와 대조한 근거:
--   1. findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc
--        WHERE user_id=? AND recommended_at>? AND id<?  ORDER BY id DESC  LIMIT n
--   2. findByUserIdAndRecommendedAtAfterOrderByIdDesc
--        WHERE user_id=? AND recommended_at>?           ORDER BY id DESC  LIMIT n
--   3. findByIdAndUserId                → PK 조회
--   4. deleteAllByUserId                → WHERE user_id=? (정렬 없음)
--   5. deleteFilterConditionsByUserId   → 서브쿼리 SELECT id WHERE user_id=? (정렬 없음)
--
-- recommended_at으로 **정렬하는** 쿼리는 하나도 없다. 커서 페이지네이션의 정렬 기준은
-- 언제나 id DESC이고(HistoryService.getHistories가 마지막 행의 id를 nextCursor로 준다),
-- recommended_at은 days 필터의 잔여 조건일 뿐이다.
--   - idx_histories_user_id를 고르면: (user_id, id DESC) 순서가 곧 요구된 정렬이라
--     인덱스를 앞에서부터 n건 읽고 멈춘다. filesort도 임시 테이블도 없다.
--   - idx_histories_user_time을 고르면: recommended_at 범위로 좁힌 뒤 그 결과 전체를
--     id로 다시 정렬해야 한다(filesort). LIMIT이 있어도 정렬은 먼저 끝나야 한다.
-- 3~5번은 user_id 선두이기만 하면 되므로 남는 인덱스로 그대로 만족된다.
-- fk_histories_user가 요구하는 인덱스도 idx_histories_user_id(user_id 선두)가 대신한다.
--
-- 감수하는 트레이드오프: "히스토리가 아주 많은데 최근 N일에는 하나도 없는" 사용자의 조회는
-- 이제 그 사용자 구간을 끝까지 훑고 빈 결과를 낸다(예전에는 범위 조건으로 즉시 0건).
-- 이 앱의 histories는 사용자당 픽 횟수만큼만 쌓이는 개인 데이터라 그 구간이 작고,
-- 반대로 filesort는 "정상적으로 잘 쓰는 사용자"의 첫 화면에서 매번 걸린다 — 흔한 쪽을
-- 확실하게 만드는 편을 택했다. 사용자당 히스토리가 수만 건 규모가 되면 재검토 대상이다.
--
-- EXPLAIN 실측이 아니라 쿼리 전수 대조를 근거로 삼았다(운영 DB가 내려가 있어 실측 불가).
-- 그래서 검증은 PerformanceIndexMigrationTest가 실제 MySQL 컨테이너 위에서 대신한다.
--
-- MySQL 8은 DROP INDEX IF EXISTS를 지원하지 않는다. 이 인덱스는 V1에서 생성되므로 조건 없이 DROP한다.
--
-- ALGORITHM=INPLACE, LOCK=NONE: 이 선언은 "이렇게 해 달라"가 아니라 "이렇게 못 하면 실패하라"는
-- 안전장치다(docs/DecisionLog.md D-031). 인덱스 DROP은 원래 in-place라 값이 바뀌는 건 없지만,
-- 조용히 COPY로 강등돼 테이블을 통째로 복사하며 DML을 막는 사고를 마이그레이션 단위로 차단한다.
-- =============================================

ALTER TABLE histories
    DROP INDEX idx_histories_user_time,
    ALGORITHM = INPLACE,
    LOCK = NONE;
