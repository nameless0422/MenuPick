-- =============================================
-- 기본 메뉴에 1인분 영양소 추정치를 채운다 (D-086)
--
-- 새 계정은 DefaultMenuProvisioner가 DefaultMenus.PRESETS의 값을 넣어 준다. 이 마이그레이션은
-- 그 전에 만들어진 계정의 기본 메뉴를 한 번 따라잡게 한다. 값은 이 시점의 PRESETS 스냅샷이다.
--
-- 대상: 이름이 기본 메뉴와 같고 열량이 아직 비어 있는 메뉴(기준 음식도 안 고른 것).
--   - 사용자가 직접 적었거나 기준 음식을 고른 메뉴는 건드리지 않는다.
--   - 열량이 비어 있으면 영양소도 비어 있다(열량 없이 영양소만 둘 수 없음) — 덮어쓸 값이 없다.
--   - version을 올려, 이 값을 모른 채 열려 있던 편집 폼의 저장이 충돌로 걸리게 한다(V9).
--
-- 다시 실행해도 안전하다 — 한 번 채워지면 열량이 NULL이 아니라 대상에서 빠진다.
-- =============================================

CREATE TEMPORARY TABLE tmp_default_menu_nutrition (
    name             VARCHAR(100) NOT NULL,
    kcal_per_serving INT          NOT NULL,
    carbs_g          DECIMAL(6,1) NOT NULL,
    protein_g        DECIMAL(6,1) NOT NULL,
    fat_g            DECIMAL(6,1) NOT NULL,
    fiber_g          DECIMAL(6,1) NOT NULL,
    sugars_g         DECIMAL(6,1) NOT NULL,
    sodium_mg        INT          NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO tmp_default_menu_nutrition
    (name, kcal_per_serving, carbs_g, protein_g, fat_g, fiber_g, sugars_g, sodium_mg) VALUES
    ('김치찌개', 230, 10.0, 16.0, 14.0, 3.0, 4.0, 1900),
    ('된장찌개', 150, 12.0, 12.0, 6.0, 3.5, 3.0, 1800),
    ('제육볶음', 370, 18.0, 25.0, 22.0, 2.0, 10.0, 1200),
    ('비빔밥', 570, 90.0, 18.0, 15.0, 5.0, 8.0, 1100),
    ('삼겹살', 770, 1.0, 34.0, 70.0, 0.0, 0.0, 120),
    ('냉면', 510, 100.0, 16.0, 5.0, 3.0, 15.0, 2600),
    ('짜장면', 720, 110.0, 20.0, 22.0, 6.0, 12.0, 2400),
    ('짬뽕', 640, 90.0, 30.0, 18.0, 6.0, 8.0, 4000),
    ('탕수육', 580, 60.0, 22.0, 28.0, 1.5, 25.0, 700),
    ('마라탕', 600, 45.0, 25.0, 35.0, 5.0, 5.0, 3500),
    ('초밥', 510, 85.0, 28.0, 6.0, 1.0, 12.0, 1300),
    ('돈까스', 680, 50.0, 30.0, 40.0, 2.0, 10.0, 1300),
    ('라멘', 660, 80.0, 28.0, 25.0, 4.0, 4.0, 3200),
    ('파스타', 630, 85.0, 22.0, 22.0, 5.0, 9.0, 1300),
    ('피자', 820, 90.0, 35.0, 35.0, 5.0, 9.0, 1900),
    ('스테이크', 530, 2.0, 45.0, 38.0, 0.0, 0.5, 400),
    ('떡볶이', 500, 100.0, 10.0, 7.0, 3.0, 25.0, 1700),
    ('김밥', 380, 60.0, 12.0, 10.0, 3.0, 4.0, 800),
    ('쌀국수', 470, 75.0, 25.0, 8.0, 2.0, 5.0, 2200),
    ('팟타이', 600, 80.0, 20.0, 22.0, 3.0, 15.0, 1500),
    ('햄버거', 500, 45.0, 25.0, 25.0, 3.0, 9.0, 1000),
    ('치킨', 790, 30.0, 55.0, 50.0, 1.0, 1.0, 1500);

UPDATE menus m
JOIN tmp_default_menu_nutrition t ON t.name = m.name
SET m.kcal_per_serving = t.kcal_per_serving,
    m.carbs_g = t.carbs_g,
    m.protein_g = t.protein_g,
    m.fat_g = t.fat_g,
    m.fiber_g = t.fiber_g,
    m.sugars_g = t.sugars_g,
    m.sodium_mg = t.sodium_mg,
    m.version = m.version + 1
WHERE m.kcal_per_serving IS NULL
  AND m.food_reference_id IS NULL;

DROP TEMPORARY TABLE tmp_default_menu_nutrition;
