package com.nameless0422.MenuPick.domain.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    List<Restaurant> findAllByUserIdAndDeletedAtIsNull(Long userId);

    @Modifying
    @Query("delete from Restaurant r where r.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
