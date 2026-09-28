-- 픽마다 실행하는 최근 30일 메뉴별 추천/피드백 집계를 시간 범위로 제한한다.
-- (user_id, id) 인덱스는 id 커서 순서의 이력 목록에 계속 사용한다.
-- menu_id와 recommendation_feedback을 포함해 집계에 필요한 값을 인덱스에서 읽는다.
ALTER TABLE histories
    ADD INDEX idx_histories_user_recommendation_signals
        (user_id, recommended_at, menu_id, recommendation_feedback),
    ALGORITHM = INPLACE,
    LOCK = NONE;
