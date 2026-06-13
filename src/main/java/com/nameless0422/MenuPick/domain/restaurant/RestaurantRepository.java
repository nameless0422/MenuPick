package com.nameless0422.MenuPick.domain.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    List<Restaurant> findAllByUserIdAndDeletedAtIsNull(Long userId);
}
