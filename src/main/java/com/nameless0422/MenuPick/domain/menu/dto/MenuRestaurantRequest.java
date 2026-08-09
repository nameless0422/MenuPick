package com.nameless0422.MenuPick.domain.menu.dto;

import jakarta.validation.constraints.NotNull;

public class MenuRestaurantRequest {

    public record Create(
            @NotNull(message = "식당 ID는 필수입니다.")
            Long restaurantId,
            Integer rating,
            String memo
    ) {}

    public record Update(
            Integer rating,
            String memo
    ) {}
}
