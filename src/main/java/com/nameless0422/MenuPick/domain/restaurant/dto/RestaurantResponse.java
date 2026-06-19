package com.nameless0422.MenuPick.domain.restaurant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class RestaurantResponse {

    public record RestaurantDetail(
            Long id,
            String name,
            String address,
            String phone,
            BigDecimal latitude,
            BigDecimal longitude,
            String naverUrl,
            String naverPlaceId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record RestaurantSummary(
            Long id,
            String name,
            String address
    ) {}
}
