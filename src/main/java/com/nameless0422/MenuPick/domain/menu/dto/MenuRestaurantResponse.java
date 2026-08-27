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
            LocalDateTime updatedAt,
            /** 낙관적 락 버전. 수정 요청에 그대로 실어 보내야 한다 — 근거는 VersionGuard. */
            long version
    ) {}

    public record MenuRestaurantListResponse(
            List<MenuRestaurantDetail> menuRestaurants
    ) {}
}
