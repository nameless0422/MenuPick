-- =============================================
-- 이모지를 구분하는 콜레이션으로 이전 (코드 리뷰 2026-08-21, issue #114)
--
-- V1이 잡은 utf8mb4_unicode_ci는 UCA 4.0.0 기반이다. UCA 4.0.0 시점에 미할당이던
-- 보충 평면(U+10000 이상) 문자에는 전부 같은 가중치가 매겨지는데, 이모지가 통째로
-- 여기 속한다. 그래서 서로 다른 이모지가 전부 "같은 문자"로 취급된다.
--
-- 운영 DB(MySQL 8.0)에서 실측한 결과:
--     SELECT _utf8mb4 0xF09F8D95 = _utf8mb4 0xF09F8D94 COLLATE utf8mb4_unicode_ci;  -- 🍕 = 🍔 → 1
--     SELECT _utf8mb4 0xF09F8D95 = _utf8mb4 0xF09F8D94 COLLATE utf8mb4_0900_ai_ci;  -- 🍕 = 🍔 → 0
--
-- UNIQUE 인덱스에서 실제로 거부되는 것까지 재현했다(ERROR 1062 Duplicate entry).
-- 사용자에게 보이는 증상은 이렇다: 누군가 닉네임을 🍕로 잡으면 그다음 사람은
-- 🍔든 🍜든 어떤 이모지 닉네임도 "이미 사용 중"으로 막힌다. 화면에 원인이
-- 드러나지 않아 신고가 들어와도 재현이 어렵다.
--
-- utf8mb4_0900_ai_ci는 UCA 9.0.0 기반이고 MySQL 8의 기본값이다. 이모지를 정확히
-- 구분하면서 한글·영문의 대소문자/악센트 무시 동작은 그대로 유지한다.
--
-- 동등성만의 문제가 아니다. LIKE·ORDER BY·GROUP BY가 모두 같은 가중치를 쓰므로
-- 이모지가 섞인 메뉴명·식당명의 검색과 정렬도 조용히 틀어져 있었다.
--
-- 안전성:
--   - FK 컬럼은 전부 BIGINT다. 문자열 FK가 하나도 없으므로 "참조 양쪽의 콜레이션이
--     같아야 한다"는 제약에 걸리지 않고, 변환 순서도 자유롭다.
--   - utf8mb4 → utf8mb4라 문자당 최대 바이트가 그대로다. VARCHAR가 TEXT로 승격되거나
--     인덱스 키가 3072바이트 상한을 넘는 일이 없다(V4의 (20+255)*4=1100 계산 그대로).
--   - 느슨한 콜레이션에서 엄격한 쪽으로 가므로, 기존에 통과했던 UNIQUE 값이
--     새로 충돌하는 방향은 원칙적으로 발생하지 않는다. 다만 UCA 4.0.0과 9.0.0은
--     서로 다른 표라 특정 문자에서 반대 방향(unicode_ci는 달랐는데 0900은 같음)이
--     생길 수 있어, 배포 전 아래 쿼리로 운영 데이터를 확인했다:
--         SELECT nickname FROM users GROUP BY nickname COLLATE utf8mb4_0900_ai_ci HAVING COUNT(*) > 1;
--         SELECT user_id, name FROM tags GROUP BY user_id, name COLLATE utf8mb4_0900_ai_ci HAVING COUNT(*) > 1;
--     충돌 0건을 확인한 뒤 적용한다. 충돌이 있으면 이 마이그레이션은 ERROR 1062로
--     실패하고, DDL은 암묵적 커밋이라 앞선 테이블은 이미 변환된 상태로 남는다.
--
-- V1은 이미 적용된 환경이 있으므로 수정하지 않는다. 새 마이그레이션으로만 바꾼다.
-- =============================================

-- 1. 스키마 기본값. 이걸 바꾸지 않으면 앞으로 콜레이션을 명시하지 않고 만드는
--    테이블이 다시 utf8mb4_unicode_ci로 돌아간다.
--    이름을 생략하면 현재 기본 데이터베이스에 적용된다 — 테스트(Testcontainers)와
--    운영의 스키마 이름이 달라도 같은 스크립트가 동작한다.
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- 2. 테이블별 변환. CONVERT TO CHARACTER SET은 테이블 기본값과 모든 문자열 컬럼을
--    한 번에 바꾸고 테이블을 재구축한다. 문자열 컬럼이 없는 연결 테이블
--    (menu_tags)도 테이블 기본값을 맞춰 두어야 이후 컬럼 추가가 옛 콜레이션을
--    물려받지 않는다.
--
--    UNIQUE 인덱스가 걸린 컬럼을 가진 테이블을 먼저 둔다. 충돌로 실패한다면
--    변환된 테이블 수가 적을 때 멈추는 편이 복구가 쉽다.
ALTER TABLE users                     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE tags                      CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE auth_providers            CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE restaurants               CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE menu_categories           CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE menus                     CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE menu_tags                 CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE menu_restaurants          CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE histories                 CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
ALTER TABLE history_filter_conditions CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
