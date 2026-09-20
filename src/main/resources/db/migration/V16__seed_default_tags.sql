-- =============================================
-- 기존 계정에 기본 태그 백필
--
-- 2026-09-21 운영 기준 태그가 0개였다. 그런데 태그를 쓰는 기능은 이미 넷이다 — 픽의 포함·제외
-- 필터, 기본 제외 태그, 빠른 픽의 태그 조건, 집단 통계의 태그 축. 빈 칸에 무엇을 적어야 할지
-- 알려주는 것이 없어 아무도 만들지 않았고, 그래서 네 기능이 통째로 잠들어 있었다.
--
-- 앞으로 가입하는 계정은 애플리케이션이 넣어 준다(DefaultMenuProvisioner). 이 마이그레이션은
-- 그 코드가 생기기 전에 만들어진 계정을 한 번 따라잡게 한다. 목록은 DefaultTags의 **이 시점
-- 스냅샷**이며, 자바 쪽을 나중에 고쳐도 이 파일은 따라가지 않는다(V10과 같은 이유).
--
-- 대상: 살아 있고(deleted_at IS NULL) 메일 인증까지 끝났으며(email_verified = 1)
--       **태그가 한 건도 없는** 계정.
--   - 태그가 하나라도 있으면 건드리지 않는다. 자기 어휘를 이미 만든 사용자에게 앱이 여섯 개를
--     끼워 넣으면, 그 사람의 필터 목록이 예고 없이 늘어난다.
--   - 미인증·탈퇴 계정을 빼는 이유는 V10과 같다.
--
-- 연결은 **이름이 정확히 일치하는 메뉴에만** 붙인다. 사용자가 이름을 고쳤거나 직접 만든 메뉴에는
-- 붙지 않는다 — 앱이 남의 메뉴를 해석해 분류하는 동작은 하지 않는다.
--
-- 원자성: 순수 DML(INSERT)뿐이라 암묵적 커밋이 없다. 중간에 실패하면 태그만 있고 연결이 비어
-- 있는 상태가 남을 수 있는데, 그건 "태그를 만들어 두고 아직 아무 메뉴에도 안 붙인" 정상 상태와
-- 같아서 앱이 그대로 다룬다.
-- =============================================

-- 1) 태그 여섯 개
INSERT INTO tags (user_id, name, created_at)
SELECT u.id, t.name, NOW(6)
  FROM users u
  JOIN (
        SELECT '국물'     AS name
  UNION SELECT '면'
  UNION SELECT '매콤'
  UNION SELECT '든든하게'
  UNION SELECT '가볍게'
  UNION SELECT '혼밥'
  ) t
 WHERE u.deleted_at IS NULL
   AND u.email_verified = 1
   AND NOT EXISTS (SELECT 1 FROM tags x WHERE x.user_id = u.id);

-- 2) 기본 메뉴에 연결
--
-- 방금 만든 태그와 같은 사용자의 메뉴를 이름으로 맞춘다. 이미 연결이 있으면 건너뛴다
-- (uq_menu_tags 위반을 피하려는 것이 아니라, 두 번 돌아도 같은 결과가 되게 하려는 것이다).
INSERT INTO menu_tags (menu_id, tag_id)
SELECT m.id, tg.id
  FROM menus m
  JOIN tags tg ON tg.user_id = m.user_id
  JOIN (
        SELECT '김치찌개' AS menu_name, '국물'     AS tag_name
  UNION SELECT '김치찌개', '매콤'
  UNION SELECT '김치찌개', '든든하게'
  UNION SELECT '된장찌개', '국물'
  UNION SELECT '된장찌개', '든든하게'
  UNION SELECT '제육볶음', '매콤'
  UNION SELECT '제육볶음', '든든하게'
  UNION SELECT '비빔밥',   '가볍게'
  UNION SELECT '비빔밥',   '혼밥'
  UNION SELECT '냉면',     '면'
  UNION SELECT '냉면',     '가볍게'
  UNION SELECT '냉면',     '혼밥'
  UNION SELECT '짜장면',   '면'
  UNION SELECT '짜장면',   '혼밥'
  UNION SELECT '짬뽕',     '국물'
  UNION SELECT '짬뽕',     '면'
  UNION SELECT '짬뽕',     '매콤'
  UNION SELECT '마라탕',   '국물'
  UNION SELECT '마라탕',   '매콤'
  UNION SELECT '마라탕',   '혼밥'
  UNION SELECT '초밥',     '가볍게'
  UNION SELECT '돈까스',   '든든하게'
  UNION SELECT '돈까스',   '혼밥'
  UNION SELECT '라멘',     '국물'
  UNION SELECT '라멘',     '면'
  UNION SELECT '라멘',     '혼밥'
  UNION SELECT '파스타',   '면'
  UNION SELECT '피자',     '든든하게'
  UNION SELECT '스테이크', '든든하게'
  UNION SELECT '떡볶이',   '매콤'
  UNION SELECT '김밥',     '가볍게'
  UNION SELECT '김밥',     '혼밥'
  UNION SELECT '쌀국수',   '국물'
  UNION SELECT '쌀국수',   '면'
  UNION SELECT '쌀국수',   '가볍게'
  UNION SELECT '쌀국수',   '혼밥'
  UNION SELECT '팟타이',   '면'
  UNION SELECT '햄버거',   '혼밥'
  ) link ON link.menu_name = m.name AND link.tag_name = tg.name
 WHERE m.deleted_at IS NULL
   AND NOT EXISTS (
        SELECT 1 FROM menu_tags mt WHERE mt.menu_id = m.id AND mt.tag_id = tg.id
   );
