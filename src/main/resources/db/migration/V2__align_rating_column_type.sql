-- menu_restaurants.rating: TINYINT -> INT
-- 엔티티(MenuRestaurant.rating Integer)와 컬럼 타입을 정렬한다.
-- Boot 4(Hibernate 7)의 ddl-auto=validate가 TINYINT를 INTEGER로 인정하지 않아 기동이 실패한다.
ALTER TABLE menu_restaurants MODIFY COLUMN rating INT NULL;
