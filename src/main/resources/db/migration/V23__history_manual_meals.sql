-- 추천 없이 먹은 끼니도 기록한다(D-081).
-- source: 이 행이 픽에서 생겼는지(PICK), 사용자가 직접 적었는지(MANUAL). 기존 행은 전부 PICK이다.
-- meal_slot: 직접 기록에서 사용자가 고른 끼니. 픽 기록은 비워 두고 시각으로 추정한다.
ALTER TABLE histories
    ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'PICK',
    ADD COLUMN meal_slot VARCHAR(10) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
