-- 픽 직후의 선택 의도. 실제 방문 여부(is_visited)와 분리한다.
ALTER TABLE histories
    ADD COLUMN recommendation_feedback VARCHAR(20) NULL AFTER is_visited;
