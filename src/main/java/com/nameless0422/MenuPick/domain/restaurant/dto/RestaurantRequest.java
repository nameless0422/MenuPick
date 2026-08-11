package com.nameless0422.MenuPick.domain.restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 문자열 필드의 {@code @Size} 상한은 전부 스키마의 컬럼 길이와 같다.
 * 검증이 없으면 초과 입력이 DB까지 내려가 제약 위반으로 409가 나는데, 그 응답에는
 * 어느 필드가 문제인지 담기지 않는다. 400 + 필드명으로 돌려주기 위한 것이다.
 */
public class RestaurantRequest {

    public record Create(
            @NotBlank(message = "식당 이름은 필수입니다.")
            @Size(max = 200)
            String name,
            @Size(max = 300)
            String address,
            @Size(max = 20)
            String phone,
            @NotNull(message = "위도는 필수입니다.")
            BigDecimal latitude,
            @NotNull(message = "경도는 필수입니다.")
            BigDecimal longitude,
            @Size(max = 500)
            String naverUrl,
            @Size(max = 100)
            String naverPlaceId
    ) {}

    public record Update(
            @NotBlank(message = "식당 이름은 필수입니다.")
            @Size(max = 200)
            String name,
            @Size(max = 300)
            String address,
            @Size(max = 20)
            String phone,
            @NotNull(message = "위도는 필수입니다.")
            BigDecimal latitude,
            @NotNull(message = "경도는 필수입니다.")
            BigDecimal longitude,
            @Size(max = 500)
            String naverUrl,
            @Size(max = 100)
            String naverPlaceId
    ) {}
}
