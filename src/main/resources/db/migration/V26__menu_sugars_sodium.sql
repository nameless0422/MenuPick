-- 당류·나트륨 추정(D-085).
--
-- 원본 CSV의 "당류(g)"와 "나트륨(mg)". 탄단지·식이섬유와 같이 1인분 기준으로 환산해 두고, 메뉴에도 복사한다.
-- 나트륨은 mg 단위 정수다 — 한 그릇에 수천 mg이라 소수점이 의미가 없다.
ALTER TABLE food_references
    ADD COLUMN sugars_g DECIMAL(6,1) NULL,
    ADD COLUMN sodium_mg INT NULL,
    ALGORITHM=INPLACE, LOCK=NONE;

ALTER TABLE menus
    ADD COLUMN sugars_g DECIMAL(6,1) NULL,
    ADD COLUMN sodium_mg INT NULL,
    ALGORITHM=INPLACE, LOCK=NONE;
