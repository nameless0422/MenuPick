package com.nameless0422.MenuPick.domain.restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public class RestaurantRequest {

    public record Create(
            @NotBlank(message = "식당 이름은 필수입니다.")
            @Size(max = 200)
            String name,
            String address,
            String phone,
            @NotNull(message = "위도는 필수입니다.")
            BigDecimal latitude,
            @NotNull(message = "경도는 필수입니다.")
            BigDecimal longitude,
            String naverUrl,
            String naverPlaceId
    ) {}

    public record Update(
            @NotBlank(message = "식당 이름은 필수입니다.")
            @Size(max = 200)
            String name,
            String address,
            String phone,
            @NotNull(message = "위도는 필수입니다.")
            BigDecimal latitude,
            @NotNull(message = "경도는 필수입니다.")
            BigDecimal longitude,
            String naverUrl,
            String naverPlaceId
    ) {}
}
