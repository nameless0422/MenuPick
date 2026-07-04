package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MenuRestaurantRepository extends JpaRepository<MenuRestaurant, Long> {

    List<MenuRestaurant> findAllByMenuId(Long menuId);

    Optional<MenuRestaurant> findByMenuIdAndRestaurantId(Long menuId, Long restaurantId);

    boolean existsByMenuIdAndRestaurantId(Long menuId, Long restaurantId);
}
