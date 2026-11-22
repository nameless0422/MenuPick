-- =============================================
-- 기준 음식의 1인분 단위, 기본 메뉴의 기준 음식 연결 (D-090)
--
-- 1) serving_unit: 원본의 식품중량은 g만이 아니라 ml도 있다(약 30%). 지금까지는 숫자만 두고 화면이
--    전부 g로 보여 "200ml" 국물이 "200g"으로 보였다. 가져오기 스크립트가 다음 실행에서 채운다.
--
-- 2) 기본 메뉴 연결: 새 계정은 DefaultMenuProvisioner가 기본 메뉴 17개를 기준 음식에 연결해 만든다.
--    이 마이그레이션은 그 전 계정의 기본 메뉴를 한 번 따라잡게 한다.
--    - 대상: 이름이 기본 메뉴와 같고, 기준 음식을 고르지 않았고, 영양소가 V27이 넣은 값 그대로인 메뉴.
--      값이 하나라도 다르면 사용자가 고친 것이므로 건드리지 않는다(D-036, D-086과 같은 기준).
--    - 기준 음식 행이 없는 DB(개발·테스트, 데이터를 아직 안 넣은 서버)에서는 JOIN이 비어 아무것도 안 한다.
--    - version을 올려, 열려 있던 편집 폼의 저장이 충돌로 걸리게 한다(V9).
--
-- 다시 실행해도 안전하다 — 한 번 연결되면 food_reference_id가 NULL이 아니라 대상에서 빠진다.
-- =============================================

ALTER TABLE food_references
    ADD COLUMN serving_unit VARCHAR(2) NOT NULL DEFAULT 'g' AFTER serving_grams,
    ALGORITHM=INPLACE, LOCK=NONE;

CREATE TEMPORARY TABLE tmp_default_menu_links (
    name             VARCHAR(100) NOT NULL,
    code             VARCHAR(30)  NOT NULL,
    kcal_per_serving INT          NOT NULL,
    carbs_g          DECIMAL(6,1) NOT NULL,
    protein_g        DECIMAL(6,1) NOT NULL,
    fat_g            DECIMAL(6,1) NOT NULL,
    fiber_g          DECIMAL(6,1) NOT NULL,
    sugars_g         DECIMAL(6,1) NOT NULL,
    sodium_mg        INT          NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 값은 V27의 추정치(= 사용자가 손대지 않았다는 표시), 코드는 DefaultMenus.PRESETS의 foodCode.
INSERT INTO tmp_default_menu_links
    (name, code, kcal_per_serving, carbs_g, protein_g, fat_g, fiber_g, sugars_g, sodium_mg) VALUES
    ('김치찌개', 'D306-266000000-0001', 230, 10.0, 16.0, 14.0, 3.0, 4.0, 1900),
    ('된장찌개', 'D106-275000000-0001', 150, 12.0, 12.0, 6.0, 3.5, 3.0, 1800),
    ('제육볶음', 'D110-465000000-0001', 370, 18.0, 25.0, 22.0, 2.0, 10.0, 1200),
    ('비빔밥', 'D101-018000000-0001', 570, 90.0, 18.0, 15.0, 5.0, 8.0, 1100),
    ('삼겹살', 'D108-382000000-0001', 770, 1.0, 34.0, 70.0, 0.0, 0.0, 120),
    ('냉면', 'D403-187000000-0001', 510, 100.0, 16.0, 5.0, 3.0, 15.0, 2600),
    ('짜장면', 'D403-197000000-0001', 720, 110.0, 20.0, 22.0, 6.0, 12.0, 2400),
    ('짬뽕', 'D403-172000000-0001', 640, 90.0, 30.0, 18.0, 6.0, 8.0, 4000),
    ('탕수육', 'D412-566000000-0001', 580, 60.0, 22.0, 28.0, 1.5, 25.0, 700),
    ('초밥', 'D301-042200000-0001', 510, 85.0, 28.0, 6.0, 1.0, 12.0, 1300),
    ('돈까스', 'D312-550080000-0001', 680, 50.0, 30.0, 40.0, 2.0, 10.0, 1300),
    ('파스타', 'D403-161000000-0001', 630, 85.0, 22.0, 22.0, 5.0, 9.0, 1300),
    ('스테이크', 'D108-387140000-0001', 530, 2.0, 45.0, 38.0, 0.0, 0.5, 400),
    ('떡볶이', 'D110-467000000-0001', 500, 100.0, 10.0, 7.0, 3.0, 25.0, 1700),
    ('김밥', 'D101-007000000-0001', 380, 60.0, 12.0, 10.0, 3.0, 4.0, 800),
    ('쌀국수', 'D303-162000000-0001', 470, 75.0, 25.0, 8.0, 2.0, 5.0, 2200),
    ('햄버거', 'D302-123000000-0005', 500, 45.0, 25.0, 25.0, 3.0, 9.0, 1000);

UPDATE menus m
JOIN tmp_default_menu_links t ON t.name = m.name
JOIN food_references f ON f.code = t.code
SET m.food_reference_id = f.id,
    m.kcal_per_serving = f.kcal_per_serving,
    m.carbs_g = f.carbs_g,
    m.protein_g = f.protein_g,
    m.fat_g = f.fat_g,
    m.fiber_g = f.fiber_g,
    m.sugars_g = f.sugars_g,
    m.sodium_mg = f.sodium_mg,
    m.version = m.version + 1
WHERE m.food_reference_id IS NULL
  AND m.kcal_per_serving = t.kcal_per_serving
  AND m.carbs_g <=> t.carbs_g
  AND m.protein_g <=> t.protein_g
  AND m.fat_g <=> t.fat_g
  AND m.fiber_g <=> t.fiber_g
  AND m.sugars_g <=> t.sugars_g
  AND m.sodium_mg <=> t.sodium_mg;

DROP TEMPORARY TABLE tmp_default_menu_links;
