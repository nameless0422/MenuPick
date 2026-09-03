-- =============================================================
-- MenuPick k6 부하 테스트 시드 데이터 (docs/LoadTestPlan.md 3절)
-- =============================================================
--
-- 왜 SQL 직접 삽입인가: API로 시드하면 heavy 프로파일이 3,000행이라 느리고,
-- 그 자체가 쓰기 부하라 시드와 측정이 섞인다 (LoadTestPlan.md 3.2). 순수 SQL이라야
-- 시드 소요 시간이 측정에 영향을 주지 않는다.
--
-- 사용법 — typical(기본값):
--   mysql -u<user> -p <password> menupick < scripts/k6/seed.sql
--
-- 사용법 — heavy: 세션 변수 @profile을 먼저 설정해야 한다. 별도 mysql 프로세스로
-- -e "SET @profile=..."을 먼저 날리면 커넥션이 달라 세션 변수가 사라지므로 반드시
-- 같은 세션 안에서 SET 다음에 이 파일을 실행해야 한다.
--   (echo "SET @profile = 'heavy';"; cat scripts/k6/seed.sql) | mysql -u<user> -p<password> menupick
-- 또는 대화형 클라이언트 안에서:
--   SET @profile = 'heavy';
--   SOURCE scripts/k6/seed.sql;
--
-- 재실행 안전성: "이미 있으면 지우고 다시 넣기"가 아니라 "이미 있으면 즉시 에러로 중단"을
-- 택했다. load-test 실행 중 히스토리가 계속 쌓이는데, delete-then-insert는 그 누적치를
-- 조용히 날려 회차 비교를 망가뜨릴 수 있다. 재시드가 필요하면 운영자가 teardown.sql을
-- 먼저 명시적으로 돌리게 한다. 이 스크립트는 mysql 클라이언트 기본 동작(--force 없이) 그대로
-- 실행해야 한다 — 기본값은 첫 에러에서 즉시 중단하므로 그 자체로 "시끄러운 실패"가 된다.
--
-- auth_providers는 이 스크립트가 건드리지 않는다. Argon2 해시는 SQL로 만들 수 없어서
-- 로그인 시나리오(LoadTestPlan.md 5.5)용 계정은 별도로 수동 준비해야 하는 전제조건이다.
-- 픽/조회 시나리오는 JWT를 오프라인 발급하므로(4절) 로그인 자체가 필요 없다.

-- 접속 문자셋을 utf8mb4로 못 박는다.
--
-- 이 파일에는 '한식'·'분식' 같은 한글 카테고리 리터럴이 들어 있고, 그 값은 픽의 카테고리
-- 필터가 실제로 걸러내는지 확인하는 유일한 근거다. mysql 클라이언트의 기본 문자셋이
-- utf8mb4가 아니면(컨테이너 기본값이 그런 경우가 있다) 리터럴이 이중 인코딩돼 들어가고,
-- 그러면 DB에는 행이 멀쩡히 쌓이는데 카테고리 필터만 영원히 아무것도 못 맞춘다.
-- 부하 테스트 관점에서는 "픽이 전부 404"로 보여, 원인을 앱에서 찾게 된다.
-- 실제로 이 스크립트를 검증하다 그 상태를 만들었다.
--
-- 콜레이션까지 지정하는 이유: 세션 변수와 컬럼을 LIKE로 비교하는 곳이 있어(아래 가드,
-- 8절의 id 조회) 둘의 콜레이션이 어긋나면 "Illegal mix of collations"로 죽는다.
--
-- 값은 **스키마를 따라간다.** V1은 utf8mb4_unicode_ci로 시작했지만
-- V7__emoji_safe_collation.sql이 데이터베이스와 전 테이블을 utf8mb4_0900_ai_ci로 옮겼다
-- (unicode_ci는 이모지를 서로 같다고 봐서 🍕 = 🍔가 참이 된다). 이 파일은 V7 이후로도
-- unicode_ci에 고정돼 있어 실행하면 line 80에서 즉시 죽었다 — 2026-09-04 첫 실행에서
-- 드러났다. 스키마 콜레이션을 또 바꾸면 여기도 같이 바꿔야 한다.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- ---------------------------------------------------------------
-- 0. 프로파일 파라미터
-- ---------------------------------------------------------------
SET @profile = IFNULL(@profile, 'typical');

SET @user_count          = IF(@profile = 'heavy', 10, 50);
SET @menus_per_user      = IF(@profile = 'heavy', 300, 30);
SET @restaurants_per_user = IF(@profile = 'heavy', 60, 15);
-- 메뉴당 식당 링크 수 범위 [min, max]. 메뉴마다 min~max 사이에서 값이 갈리도록
-- 아래 menu_restaurants 삽입에서 사용한다 (전부 같은 개수면 분포가 아니라 상수다).
SET @link_min = IF(@profile = 'heavy', 2, 1);
SET @link_max = IF(@profile = 'heavy', 3, 2);
SET @nickname_prefix = CONCAT('loadtest-', @profile, '-');

-- 재귀 CTE 깊이 참고: 이 스크립트의 재귀는 최대 @menus_per_user(=300, heavy)회 반복한다.
-- cte_max_recursion_depth 기본값 1000보다 작으므로 별도로 올리지 않는다.

-- ---------------------------------------------------------------
-- 1. 중복 시드 방지 가드 — 이미 있으면 SIGNAL로 즉시 중단
-- ---------------------------------------------------------------
DELIMITER $$
DROP PROCEDURE IF EXISTS __loadtest_guard $$
CREATE PROCEDURE __loadtest_guard()
BEGIN
    DECLARE existing_count INT;
    SELECT COUNT(*) INTO existing_count
    FROM users
    WHERE nickname LIKE CONCAT(@nickname_prefix, '%');

    IF existing_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'loadtest 시드가 이미 존재합니다. scripts/k6/teardown.sql을 먼저 실행하세요.';
    END IF;
END $$
DELIMITER ;

CALL __loadtest_guard();
DROP PROCEDURE __loadtest_guard;

-- ---------------------------------------------------------------
-- 2. users — 닉네임은 V5 UNIQUE 제약 때문에 프로파일+3자리 번호로 충돌을 피한다.
--    email은 NULL, email_verified=1 — 메일 인증 절차 없이 픽 후보로만 쓰기 위함.
-- ---------------------------------------------------------------
INSERT INTO users (email, nickname, email_verified, created_at, updated_at, deleted_at)
SELECT NULL,
       CONCAT(@nickname_prefix, LPAD(n.n, 3, '0')),
       1,
       NOW(), NOW(), NULL
FROM (
    WITH RECURSIVE seq AS (
        SELECT 1 AS n
        UNION ALL
        SELECT n + 1 FROM seq WHERE n < @user_count
    )
    SELECT n FROM seq
) n;

-- ---------------------------------------------------------------
-- 3. menus — 사용자당 정확히 @menus_per_user개.
--    is_excluded=0, deleted_at=NULL은 필수다: 하나라도 빠지면 픽이 전부 404가 되어
--    "빠르게 실패하는 속도"를 재게 된다 (LoadTestPlan.md 3.2).
-- ---------------------------------------------------------------
INSERT INTO menus (user_id, name, memo, is_excluded, weight, created_at, updated_at, deleted_at)
SELECT u.id,
       CONCAT('loadtest-menu-', u.seq, '-', m.n),
       NULL,
       0,
       1 + (m.n % 5),
       NOW(), NOW(), NULL
FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS seq
    FROM users
    WHERE nickname LIKE CONCAT(@nickname_prefix, '%')
) u
CROSS JOIN (
    WITH RECURSIVE seq AS (
        SELECT 1 AS n
        UNION ALL
        SELECT n + 1 FROM seq WHERE n < @menus_per_user
    )
    SELECT n FROM seq
) m;

-- ---------------------------------------------------------------
-- 4. menu_categories — CATEGORY_PRESETS(frontend/src/constants.ts) 8종을
--    메뉴 id 순서로 균등 순환 배정한다. 전부 같은 카테고리면 필터가 아무것도 안 거르고,
--    전부 다르면 전부 걸러져 둘 다 필터 시나리오를 무의미하게 만든다 (3.1).
--    약 1/4 메뉴에는 두 번째 카테고리를 추가해 다중 카테고리 메뉴도 섞는다.
-- ---------------------------------------------------------------
INSERT INTO menu_categories (menu_id, category)
SELECT base.menu_id, presets.category
FROM (
    SELECT m.id AS menu_id,
           ROW_NUMBER() OVER (ORDER BY m.id) AS rn
    FROM menus m
    JOIN users u ON u.id = m.user_id
    WHERE u.nickname LIKE CONCAT(@nickname_prefix, '%')
) base
CROSS JOIN (SELECT 0 AS slot UNION ALL SELECT 1 AS slot) slots
JOIN (
    SELECT 0 AS idx, '한식' AS category UNION ALL
    SELECT 1, '중식' UNION ALL
    SELECT 2, '일식' UNION ALL
    SELECT 3, '양식' UNION ALL
    SELECT 4, '분식' UNION ALL
    SELECT 5, '아시안' UNION ALL
    SELECT 6, '패스트푸드' UNION ALL
    SELECT 7, '카페·디저트'
) presets
    -- slot=0: 기본 카테고리를 메뉴 id 순서로 8종 순환 배정.
    -- slot=1: rn%4=0인 메뉴(약 1/4)에만 3칸 옮긴 두 번째 카테고리를 추가.
    --         오프셋이 3이라 8로 나눈 나머지가 절대 slot 0과 같아지지 않으므로
    --         PK(menu_id, category) 충돌이 나지 않는다.
    ON presets.idx = MOD((base.rn - 1) + (slots.slot * 3), 8)
WHERE slots.slot = 0 OR MOD(base.rn, 4) = 0;

-- ---------------------------------------------------------------
-- 5. restaurants — 사용자당 정확히 @restaurants_per_user개.
--    위경도는 서울시청(37.5665, 126.9780) 기준 남북·동서 각각 약 ±2km 사각형 안에서
--    흩뿌린다. maxDistance 500/1000/2000m 필터가 실제로 갈리려면 0m~약 2.8km(모서리)
--    사이에 분포가 걸쳐 있어야 한다. kakao_place_id는 NULL로 둔다 — V5 유니크 인덱스는
--    NULL을 서로 다른 값으로 취급하므로 사용자당 여러 식당을 자유롭게 넣을 수 있다.
--    위도 1도 ≈ 111km → 2km ≈ 0.018도. 경도는 위도 37.5°에서 1도 ≈ 88km → 2km ≈ 0.0227도.
-- ---------------------------------------------------------------
INSERT INTO restaurants (user_id, name, address, phone, latitude, longitude, naver_url, kakao_place_id, created_at, updated_at, deleted_at)
SELECT u.id,
       CONCAT('loadtest-restaurant-', u.seq, '-', r.n),
       NULL, NULL,
       ROUND(37.5665 + (RAND() - 0.5) * 0.036, 7),
       ROUND(126.9780 + (RAND() - 0.5) * 0.0454, 7),
       NULL, NULL,
       NOW(), NOW(), NULL
FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS seq
    FROM users
    WHERE nickname LIKE CONCAT(@nickname_prefix, '%')
) u
CROSS JOIN (
    WITH RECURSIVE seq AS (
        SELECT 1 AS n
        UNION ALL
        SELECT n + 1 FROM seq WHERE n < @restaurants_per_user
    )
    SELECT n FROM seq
) r;

-- ---------------------------------------------------------------
-- 6. menu_restaurants — 메뉴당 [@link_min, @link_max] 사이 개수를 연결한다.
--    UNIQUE(menu_id, restaurant_id)를 어길 수 없으므로, 메뉴별 순번(mseq)에 오프셋을
--    더한 값을 사용자의 식당 수로 모듈로 연산해 식당을 고른다. 오프셋 개수가 항상
--    restaurants_per_user 이하이므로(연속된 서로 다른 나머지) 같은 메뉴 안에서
--    식당이 절대 겹치지 않는다 — RAND()로 뽑고 충돌을 걸러내는 방식보다 단순하고 결정적이다.
-- ---------------------------------------------------------------
INSERT INTO menu_restaurants (menu_id, restaurant_id, rating, memo, created_at, updated_at)
SELECT mo.menu_id, ro.restaurant_id, NULL, NULL, NOW(), NOW()
FROM (
    SELECT menu_id, user_id, mseq,
           @link_min + MOD(mseq, (@link_max - @link_min + 1)) AS link_count
    FROM (
        SELECT m.id AS menu_id, m.user_id,
               ROW_NUMBER() OVER (PARTITION BY m.user_id ORDER BY m.id) AS mseq
        FROM menus m
        JOIN users u ON u.id = m.user_id
        WHERE u.nickname LIKE CONCAT(@nickname_prefix, '%')
    ) base_menu
) mo
JOIN (
    WITH RECURSIVE off AS (
        SELECT 0 AS o
        UNION ALL
        SELECT o + 1 FROM off WHERE o < @link_max - 1
    )
    SELECT o FROM off
) offs ON offs.o < mo.link_count
JOIN (
    SELECT r.id AS restaurant_id, r.user_id,
           ROW_NUMBER() OVER (PARTITION BY r.user_id ORDER BY r.id) AS rseq
    FROM restaurants r
    JOIN users u2 ON u2.id = r.user_id
    WHERE u2.nickname LIKE CONCAT(@nickname_prefix, '%')
) ro ON ro.user_id = mo.user_id
    AND ro.rseq = 1 + MOD(mo.mseq + offs.o, @restaurants_per_user);

-- ---------------------------------------------------------------
-- 7. 요약 — 운영자가 눈으로 행 수를 확인할 수 있도록 프로파일 표와 비교한다.
-- ---------------------------------------------------------------
SELECT @profile AS profile, @user_count AS expected_users,
       @menus_per_user AS expected_menus_per_user,
       @restaurants_per_user AS expected_restaurants_per_user;

SELECT 'users' AS table_name, COUNT(*) AS row_count
FROM users WHERE nickname LIKE CONCAT(@nickname_prefix, '%')
UNION ALL
SELECT 'menus', COUNT(*)
FROM menus m JOIN users u ON u.id = m.user_id
WHERE u.nickname LIKE CONCAT(@nickname_prefix, '%')
UNION ALL
SELECT 'menu_categories', COUNT(*)
FROM menu_categories mc
JOIN menus m ON m.id = mc.menu_id
JOIN users u ON u.id = m.user_id
WHERE u.nickname LIKE CONCAT(@nickname_prefix, '%')
UNION ALL
SELECT 'restaurants', COUNT(*)
FROM restaurants r JOIN users u ON u.id = r.user_id
WHERE u.nickname LIKE CONCAT(@nickname_prefix, '%')
UNION ALL
SELECT 'menu_restaurants', COUNT(*)
FROM menu_restaurants mr
JOIN menus m ON m.id = mr.menu_id
JOIN users u ON u.id = m.user_id
WHERE u.nickname LIKE CONCAT(@nickname_prefix, '%');

-- ---------------------------------------------------------------
-- 8. 부하 스크립트에 넘길 사용자 id
--
-- load-test.js는 VU가 사용자 풀에서 골라 쓰도록 USER_IDS를 요구한다(한 계정만
-- 두들기면 그 사용자의 행이 버퍼풀에 완전히 얹혀 실제보다 좋은 숫자가 나온다).
-- id는 AUTO_INCREMENT라 미리 알 수 없으므로 여기서 출력해 그대로 복사해 쓴다.
--
--   USER_IDS=<range> k6 run scripts/k6/load-test.js
--
-- 범위형(101-150)과 나열형(101,102,...) 둘 다 받는다. 한 프로파일의 사용자는 한 번의
-- INSERT로 들어가 id가 연속이므로 보통 범위형으로 충분하지만, 다른 프로파일을 사이에
-- 두고 시드했다면 나열형을 쓴다.
-- ---------------------------------------------------------------
SELECT CONCAT(MIN(id), '-', MAX(id))       AS user_ids_range,
       GROUP_CONCAT(id ORDER BY id)        AS user_ids_list,
       COUNT(*)                            AS user_count
FROM users
WHERE nickname LIKE CONCAT(@nickname_prefix, '%');
