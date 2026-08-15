-- =============================================================
-- MenuPick k6 부하 테스트 시드 정리 (docs/LoadTestPlan.md 3.3)
-- =============================================================
--
-- seed.sql이 만든 모든 행 + load-test 실행 중 그 위에서 만들어진 히스토리를 지운다.
-- 픽은 조회가 아니라 쓰기라(LoadTestPlan.md 2.1) load 시나리오를 한 번 돌리면
-- histories/history_filter_conditions가 시드 시점에는 없던 채로 새로 쌓인다.
-- 이 스크립트가 그것까지 찾아 지우지 않으면, 시드 사용자를 지우려는 순간
-- fk_histories_user(RESTRICT)에 걸려 실패한다.
--
-- 범위 지정은 전부 "loadtest- 닉네임을 가진 사용자"에서 출발한다. 메뉴·식당은 이
-- 스크립트가 소유자가 loadtest 사용자인 행만 건드리므로, 실사용자 데이터는 어떤 경로로도
-- WHERE 절에 들어오지 않는다.
--
-- 프로파일을 하나만 지우고 싶으면(예: heavy만) 실행 전에 세션 변수로 좁힌다:
--   SET @loadtest_prefix = 'loadtest-heavy-%';
-- 기본값은 모든 프로파일을 포괄하는 'loadtest-%'다.
--
-- auth_providers는 건드리지 않는다 — seed.sql이 만든 게 아니라(수동 준비 전제조건),
-- 여기서 지우면 로그인 시나리오용으로 손으로 넣어둔 계정을 매 회차 다시 만들어야 한다.
--
-- FK-safe 순서 (자식 → 부모):
--   history_filter_conditions → histories → menu_restaurants → menu_categories
--   → menus, restaurants → users
-- history_filter_conditions는 fk_hfc_history가 ON DELETE CASCADE라 histories 삭제만으로도
-- 자동으로 지워지지만, 삭제 전후 카운트를 명시적으로 남기기 위해 직접 지운다.

SET @loadtest_prefix = IFNULL(@loadtest_prefix, 'loadtest-%');

-- ---------------------------------------------------------------
-- 0. 대상 id를 임시 테이블에 고정한다.
--    이유: 삭제가 진행되면 "WHERE user_id IN (SELECT id FROM users WHERE nickname LIKE ...)"
--    같은 조건은 users 행이 지워지는 순간 공집합이 되어, "삭제 후" 카운트를 검증할 때
--    무엇을 셌는지 알 수 없게 된다(항상 0이 나와 버려 실제로 지워졌는지 증명이 안 됨).
--    임시 테이블은 세션 로컬이라 다른 접속의 실사용자 데이터와 절대 섞이지 않는다.
-- ---------------------------------------------------------------
DROP TEMPORARY TABLE IF EXISTS tmp_lt_users;
DROP TEMPORARY TABLE IF EXISTS tmp_lt_menus;
DROP TEMPORARY TABLE IF EXISTS tmp_lt_restaurants;
DROP TEMPORARY TABLE IF EXISTS tmp_lt_histories;

CREATE TEMPORARY TABLE tmp_lt_users AS
SELECT id FROM users WHERE nickname LIKE @loadtest_prefix;

CREATE TEMPORARY TABLE tmp_lt_menus AS
SELECT id FROM menus WHERE user_id IN (SELECT id FROM tmp_lt_users);

CREATE TEMPORARY TABLE tmp_lt_restaurants AS
SELECT id FROM restaurants WHERE user_id IN (SELECT id FROM tmp_lt_users);

-- 픽은 항상 자기 자신의 메뉴/식당에서만 히스토리를 만들므로 user_id 범위로 충분하지만,
-- 혹시 모를 참조 누락을 막기 위해 menu_id/restaurant_id 경로도 함께 잡는다.
CREATE TEMPORARY TABLE tmp_lt_histories AS
SELECT id FROM histories
WHERE user_id IN (SELECT id FROM tmp_lt_users)
   OR menu_id IN (SELECT id FROM tmp_lt_menus)
   OR restaurant_id IN (SELECT id FROM tmp_lt_restaurants);

-- ---------------------------------------------------------------
-- 1. 삭제 전 카운트
--    MySQL은 같은 TEMPORARY 테이블을 한 쿼리(문장) 안에서 두 번 이상 참조하지 못한다
--    ("Can't reopen table"). UNION ALL로 묶으면 하나의 문장이 되어 이 제약에 걸리므로,
--    테이블마다 개별 SELECT 문으로 나눈다 — 결과는 여러 개의 결과셋으로 출력된다.
-- ---------------------------------------------------------------
SELECT 'before' AS phase, 'users' AS table_name, COUNT(*) AS row_count FROM tmp_lt_users;
SELECT 'before' AS phase, 'menus' AS table_name, COUNT(*) AS row_count FROM tmp_lt_menus;
SELECT 'before' AS phase, 'restaurants' AS table_name, COUNT(*) AS row_count FROM tmp_lt_restaurants;
SELECT 'before' AS phase, 'menu_categories' AS table_name, COUNT(*) AS row_count
FROM menu_categories WHERE menu_id IN (SELECT id FROM tmp_lt_menus);
SELECT 'before' AS phase, 'menu_restaurants' AS table_name, COUNT(*) AS row_count
FROM menu_restaurants
WHERE menu_id IN (SELECT id FROM tmp_lt_menus) OR restaurant_id IN (SELECT id FROM tmp_lt_restaurants);
SELECT 'before' AS phase, 'histories' AS table_name, COUNT(*) AS row_count FROM tmp_lt_histories;
SELECT 'before' AS phase, 'history_filter_conditions' AS table_name, COUNT(*) AS row_count
FROM history_filter_conditions WHERE history_id IN (SELECT id FROM tmp_lt_histories);

-- ---------------------------------------------------------------
-- 2. 삭제 — FK 자식부터
-- ---------------------------------------------------------------
DELETE FROM history_filter_conditions
WHERE history_id IN (SELECT id FROM tmp_lt_histories);

DELETE FROM histories
WHERE id IN (SELECT id FROM tmp_lt_histories);

DELETE FROM menu_restaurants
WHERE menu_id IN (SELECT id FROM tmp_lt_menus)
   OR restaurant_id IN (SELECT id FROM tmp_lt_restaurants);

DELETE FROM menu_categories
WHERE menu_id IN (SELECT id FROM tmp_lt_menus);

DELETE FROM menus
WHERE id IN (SELECT id FROM tmp_lt_menus);

DELETE FROM restaurants
WHERE id IN (SELECT id FROM tmp_lt_restaurants);

DELETE FROM users
WHERE id IN (SELECT id FROM tmp_lt_users);

-- ---------------------------------------------------------------
-- 3. 삭제 후 카운트 — 전부 0이어야 정상이다. (위와 같은 이유로 UNION ALL 대신 개별 문장)
-- ---------------------------------------------------------------
SELECT 'after' AS phase, 'users' AS table_name, COUNT(*) AS row_count
FROM users WHERE id IN (SELECT id FROM tmp_lt_users);
SELECT 'after' AS phase, 'menus' AS table_name, COUNT(*) AS row_count
FROM menus WHERE id IN (SELECT id FROM tmp_lt_menus);
SELECT 'after' AS phase, 'restaurants' AS table_name, COUNT(*) AS row_count
FROM restaurants WHERE id IN (SELECT id FROM tmp_lt_restaurants);
SELECT 'after' AS phase, 'menu_categories' AS table_name, COUNT(*) AS row_count
FROM menu_categories WHERE menu_id IN (SELECT id FROM tmp_lt_menus);
SELECT 'after' AS phase, 'menu_restaurants' AS table_name, COUNT(*) AS row_count
FROM menu_restaurants
WHERE menu_id IN (SELECT id FROM tmp_lt_menus) OR restaurant_id IN (SELECT id FROM tmp_lt_restaurants);
SELECT 'after' AS phase, 'histories' AS table_name, COUNT(*) AS row_count
FROM histories WHERE id IN (SELECT id FROM tmp_lt_histories);
SELECT 'after' AS phase, 'history_filter_conditions' AS table_name, COUNT(*) AS row_count
FROM history_filter_conditions WHERE history_id IN (SELECT id FROM tmp_lt_histories);

DROP TEMPORARY TABLE tmp_lt_users;
DROP TEMPORARY TABLE tmp_lt_menus;
DROP TEMPORARY TABLE tmp_lt_restaurants;
DROP TEMPORARY TABLE tmp_lt_histories;
