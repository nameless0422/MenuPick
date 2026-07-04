package com.nameless0422.MenuPick.domain.menu.dto;

import java.time.LocalDateTime;
import java.util.List;

public class MenuRestaurantResponse {

    public record MenuRestaurantDetail(
            Long menuId,
            Long restaurantId,
            String restaurantName,
            String restaurantAddress,
            Integer rating,
            String memo,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record MenuRestaurantListResponse(
            List<MenuRestaurantDetail> menuRestaurants
    ) {}
}
