-- =============================================
-- 미사용 인덱스 정리 (코드 리뷰 2026-08-21, issue #86)
-- 리포지토리 7개의 파생 쿼리와 @Query를 전수 대조해, 어떤 쿼리도 쓰지 않는 인덱스를 걷어낸다.
-- 인덱스는 읽기를 돕는 대신 쓰기마다 유지 비용을 물리고 버퍼 풀(128MB)을 갉아먹는다.
--
-- MySQL 8은 DROP INDEX IF EXISTS를 지원하지 않는다. 둘 다 V1에서 생성되므로 조건 없이 DROP한다.
-- =============================================

-- 1. histories.idx_histories_visited (user_id, is_visited, recommended_at DESC)
--    is_visited를 조건으로 쓰는 쿼리가 HistoryRepository 전체에 없다. 방문 여부는 조회해 온
--    행에서 읽기만 하고 필터로는 쓰지 않는다.
--    histories는 픽할 때마다 INSERT가 들어오는 가장 뜨거운 쓰기 경로라, 여기의 죽은 인덱스가
--    가장 비싸다.
ALTER TABLE histories
    DROP INDEX idx_histories_visited;

-- 2. menu_categories.idx_menu_categories_cat (category)
--    category 단독 조회 경로가 없다. 픽의 카테고리 필터는 메뉴를 메모리로 올린 뒤
--    PickService.filterByCategories가 자바에서 거른다(그 자체는 별도 과제 #87).
--    menu_categories는 PK(menu_id, ...) 접두사로만 읽히고, FK도 PK가 만족시키므로
--    이 인덱스를 드롭해도 FK 제약에 필요한 인덱스가 사라지지 않는다.
--    메뉴 생성·수정마다 다시 쓰이는 테이블이다.
ALTER TABLE menu_categories
    DROP INDEX idx_menu_categories_cat;

-- 남겨 둔 것: restaurants.idx_restaurants_location (user_id, latitude, longitude)
--   지금은 이 인덱스를 쓰는 쿼리가 없다(거리 계산은 PickService가 자바에서 한다).
--   그래도 드롭하지 않는 이유는 두 가지다.
--    (1) restaurants는 사용자가 가끔 식당을 저장할 때만 쓰이는 낮은 쓰기 볼륨이라
--        유지 비용이 위 둘과 비교가 안 될 만큼 작다.
--    (2) 픽의 거리 필터를 DB로 내리는 과제(#87)가 바로 이 인덱스를 쓴다 —
--        좌표 바운딩 박스로 1차 축소한 뒤 Haversine으로 정밀 판정하는 형태다.
--        지금 드롭하면 그때 같은 인덱스를 다시 만들게 된다.
--   그 과제가 취소되면 이 인덱스도 함께 걷어낸다.
