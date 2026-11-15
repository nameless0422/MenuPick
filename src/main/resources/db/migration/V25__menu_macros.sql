-- 탄수화물·단백질·지방·식이섬유 추정(D-084).
--
-- food_references에는 탄단지 칸이 이미 있다(V24). 식이섬유만 더한다 — 원본 CSV의 "식이섬유(g)".
ALTER TABLE food_references
    ADD COLUMN fiber_g DECIMAL(6,1) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;

-- 메뉴에 1인분 값을 복사해 둔다. 열량(kcal_per_serving)과 같은 이유다 — 기록·요약이 참조 테이블을
-- 조인하지 않는다. 기준 음식을 고르면 넷 다 복사되고, 직접 적은 메뉴는 비어 있을 수 있다(모름).
ALTER TABLE menus
    ADD COLUMN carbs_g DECIMAL(6,1) NULL,
    ADD COLUMN protein_g DECIMAL(6,1) NULL,
    ADD COLUMN fat_g DECIMAL(6,1) NULL,
    ADD COLUMN fiber_g DECIMAL(6,1) NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
