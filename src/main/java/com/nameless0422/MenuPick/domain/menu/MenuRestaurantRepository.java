package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuRestaurantRepository extends JpaRepository<MenuRestaurant, Long> {

    List<MenuRestaurant> findAllByMenuId(Long menuId);

    Optional<MenuRestaurant> findByMenuIdAndRestaurantId(Long menuId, Long restaurantId);

    boolean existsByMenuIdAndRestaurantId(Long menuId, Long restaurantId);

    /**
     * 식당 soft-delete 시 해당 식당을 참조하는 메뉴-식당 링크를 일괄 정리한다.
     * 링크는 soft-delete 대상이 아니므로(단순 연결 테이블) 물리 삭제한다.
     */
    @Modifying
    @Query("delete from MenuRestaurant mr where mr.restaurant.id = :restaurantId")
    void deleteByRestaurantId(@Param("restaurantId") Long restaurantId);

    @Modifying
    @Query("delete from MenuRestaurant mr where " +
            "mr.menu.id in (select m.id from Menu m where m.user.id = :userId) or " +
            "mr.restaurant.id in (select r.id from Restaurant r where r.user.id = :userId)")
    void deleteAllByUserId(@Param("userId") Long userId);
}
