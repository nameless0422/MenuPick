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

    @Modifying
    @Query("delete from MenuRestaurant mr where " +
            "mr.menu.id in (select m.id from Menu m where m.user.id = :userId) or " +
            "mr.restaurant.id in (select r.id from Restaurant r where r.user.id = :userId)")
    void deleteAllByUserId(@Param("userId") Long userId);
}
