-- 칼로리 추정(D-082).
--
-- food_references: 식약처 식품영양성분 DB "음식" 데이터의 사본. 저장소에는 데이터를 넣지 않고
-- scripts/import-food-references.py가 서버에서 CSV를 읽어 채운다(코드 기준 upsert, 행은 지우지 않는다).
-- 값은 전부 1인분 기준으로 환산해 둔다 — 원본은 100g 기준이라 쓸 때마다 곱하면 실수할 자리가 늘어난다.
CREATE TABLE food_references (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    code             VARCHAR(30)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    category         VARCHAR(50)  NULL,
    serving_grams    DECIMAL(7,1) NULL,
    kcal_per_serving INT          NOT NULL,
    carbs_g          DECIMAL(6,1) NULL,
    protein_g        DECIMAL(6,1) NULL,
    fat_g            DECIMAL(6,1) NULL,
    source_date      DATE         NULL,
    imported_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_food_references_code (code),
    KEY idx_food_references_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 메뉴의 1인분 열량. 기준 음식을 고르면 그 값을 복사하고(food_reference_id에 출처를 남긴다),
-- 직접 적으면 food_reference_id가 비어 있다. 외래 키를 걸지 않는 이유: 참조 행은 지우지 않으므로
-- 매달릴 일이 없고, menus에 FK를 추가하면 INPLACE가 안 돼 테이블을 복사한다.
ALTER TABLE menus
    ADD COLUMN food_reference_id BIGINT NULL,
    ADD COLUMN kcal_per_serving INT NULL,
    ALGORITHM=INPLACE, LOCK=NONE;

-- 기록 한 줄에 먹은 양(인분). 열량은 메뉴 열량 × 양으로 계산한다.
ALTER TABLE histories
    ADD COLUMN servings DECIMAL(3,1) NOT NULL DEFAULT 1.0,
    ALGORITHM=INPLACE, LOCK=NONE;
