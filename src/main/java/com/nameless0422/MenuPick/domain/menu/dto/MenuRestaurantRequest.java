package com.nameless0422.MenuPick.domain.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class MenuRestaurantRequest {

    public record Create(
            @NotNull(message = "식당 ID는 필수입니다.")
            Long restaurantId,
            // rating은 선택 값(null 허용)이지만, 값이 있으면 1~5 범위여야 한다 (DB TINYINT + UI 별점 계약).
            @Min(value = 1, message = "평점은 1 이상이어야 합니다.")
            @Max(value = 5, message = "평점은 5 이하여야 합니다.")
            Integer rating,
            String memo
    ) {}

    public record Update(
            @Min(value = 1, message = "평점은 1 이상이어야 합니다.")
            @Max(value = 5, message = "평점은 5 이하여야 합니다.")
            Integer rating,
            String memo
    ) {}
}
