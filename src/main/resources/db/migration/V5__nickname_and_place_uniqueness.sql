-- 두 가지 유일성 제약을 세운다.
--   1. 닉네임 중복 비허용 (users.nickname)
--   2. 같은 사용자가 같은 장소를 두 번 저장하지 않음 (restaurants.user_id + 카카오 place id)
-- 둘 다 제품 결정이며, 근거는 docs/DecisionLog.md D-028 참고.

-- =====================================================================
-- 1. users.nickname UNIQUE
-- =====================================================================

-- 기존 중복을 먼저 정리한다. 소셜 로그인에서 프로필 제공 동의를 받지 못한 사용자는
-- 전원 같은 기본 닉네임("메뉴픽 사용자")을 받으므로 중복은 실제로 생길 수 있다.
--
-- 가장 먼저 만들어진 계정이 이름을 유지하고 나머지에 자기 id를 덧붙인다. id는 유일하므로
-- 결과도 유일하다 — 누군가 이미 "X-7" 같은 이름을 쓰고 있는 경우만 예외인데, 그때는 아래
-- ALTER가 실패해 마이그레이션 전체가 롤백된다. 조용히 잘못된 상태로 넘어가지 않는다.
--
-- LEFT()로 앞을 잘라내는 이유는 nickname이 VARCHAR(50)이라 접미사를 붙이면 넘칠 수 있어서다.
-- 그룹 기준은 컬럼 콜레이션(utf8mb4_unicode_ci)을 그대로 따르므로 대소문자를 구분하지 않는다.
-- 아래 UNIQUE 인덱스도 같은 콜레이션으로 판정하니 둘의 기준이 어긋나지 않는다.
UPDATE users u
    JOIN (SELECT id,
                 ROW_NUMBER() OVER (PARTITION BY nickname ORDER BY id) AS seq
          FROM users) ranked ON ranked.id = u.id
SET u.nickname = CONCAT(
        LEFT(u.nickname, 50 - CHAR_LENGTH(CAST(u.id AS CHAR)) - 1),
        '-', u.id)
WHERE ranked.seq > 1;

-- 탈퇴(soft delete)한 계정도 인덱스에 남아 닉네임을 계속 점유한다. 의도한 것이다 —
-- 유예기간(30일) 안에 돌아오면 쓰던 이름 그대로 복구되어야 하고, 유예가 지나면
-- 하드 삭제 배치가 행을 지우면서 이름이 자동으로 풀린다.
ALTER TABLE users
    ADD UNIQUE INDEX uq_users_nickname (nickname);

-- =====================================================================
-- 2. restaurants: 사용자당 같은 장소 하나
-- =====================================================================

-- 이 컬럼은 이름과 달리 실제로는 카카오 로컬 검색 결과의 place id를 담는다 —
-- 장소 검색은 카카오만 쓰고, 네이버는 지오코딩 용도로만 쓴다. 유일성 제약이 붙는 컬럼의
-- 이름이 틀린 채로 남으면 나중에 네이버 id를 같은 칸에 섞어 넣는 사고가 난다.
ALTER TABLE restaurants
    RENAME COLUMN naver_place_id TO kakao_place_id;

-- 지금까지 프론트가 이 값을 보내지 않아 전부 NULL이지만, naver_url에는 카카오가 준
-- place_url(http://place.map.kakao.com/{id})이 그대로 들어 있어 id를 되살릴 수 있다.
-- 백필하지 않으면 이미 저장해 둔 식당을 다시 저장할 때 중복 판정이 되지 않아 행이 하나 더 생긴다.
UPDATE restaurants
SET kakao_place_id = SUBSTRING_INDEX(naver_url, '/', -1)
WHERE kakao_place_id IS NULL
  AND naver_url LIKE '%place.map.kakao.com/%'
  AND SUBSTRING_INDEX(naver_url, '/', -1) REGEXP '^[0-9]+$';

-- 백필로 "같은 사용자가 같은 장소를 두 번 저장해 둔" 기존 상태가 드러날 수 있다
-- (지금까지 막을 수단이 없었으므로). 가장 먼저 저장한 행만 id를 유지하고 나머지는 NULL로 되돌린다.
--
-- 행을 실제로 합치지 않는 이유: 합치려면 menu_restaurants와 histories의 참조를 옮겨야 하고,
-- menu_restaurants에는 UNIQUE (menu_id, restaurant_id)가 있어 같은 메뉴가 양쪽에 연결돼 있으면
-- 옮기다 충돌한다. 그건 스키마 변경이 아니라 데이터 정리 작업이라 여기서 하지 않는다.
-- NULL이 된 행은 사용자가 만든 별개의 식당으로 그대로 남고, 중복 판정에만 참여하지 않는다.
UPDATE restaurants r
    JOIN (SELECT id,
                 ROW_NUMBER() OVER (PARTITION BY user_id, kakao_place_id ORDER BY id) AS seq
          FROM restaurants
          WHERE kakao_place_id IS NOT NULL) ranked ON ranked.id = r.id
SET r.kakao_place_id = NULL
WHERE ranked.seq > 1;

-- MySQL의 UNIQUE 인덱스는 NULL을 서로 다른 값으로 취급한다. 즉 place id가 없는 식당
-- (백필로 살리지 못한 기존 행)은 몇 개든 공존한다 — 이름이 같아도 다른 식당일 수 있다는
-- 전제이므로 이름은 판정에 쓰지 않는다.
--
-- soft delete된 행도 인덱스에 남아 자리를 차지한다. 그래서 저장 경로는 "없으면 INSERT"가
-- 아니라 "지워진 게 있으면 되살린다"로 동작해야 한다 (RestaurantService.createRestaurant).
--
-- 단일 컬럼 인덱스는 새 유니크 인덱스가 대체한다. 조회는 항상 소유자 범위로 한정되므로
-- place id만으로 찾는 경로가 없다.
ALTER TABLE restaurants
    DROP INDEX idx_restaurants_naver_place,
    ADD UNIQUE INDEX uq_restaurants_user_place (user_id, kakao_place_id);
