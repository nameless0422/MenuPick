-- "요즘은 이거 말고" 를 담는 칸. 영구 제외(is_excluded)와 다르다 — 이 시각이 지나면
-- 사용자가 아무것도 하지 않아도 다시 후보가 된다. 그래서 상태가 아니라 만료 시각을 저장한다:
-- 되돌리는 일을 사람이나 스케줄러가 기억할 필요가 없고, 후보 조회의 WHERE 한 줄이 곧 만료다.
--
-- NULL = 쉬지 않는다. 과거 시각 = 이미 깨어났다(굳이 지우지 않는다 — 마지막으로 언제까지
-- 쉬었는지가 남는 편이 낫고, 지우려면 쓰기가 한 번 더 필요하다).
ALTER TABLE menus
    ADD COLUMN paused_until DATETIME(6) NULL AFTER is_excluded,
    ALGORITHM = INPLACE,
    LOCK = NONE;
