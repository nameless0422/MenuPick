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
            /**
             * 카카오 장소 id. 이 값이 있으면 같은 장소를 이미 저장했는지 판정에 쓰인다.
             * 없으면(장소 검색을 거치지 않은 저장) 중복 판정 없이 그대로 새로 만든다.
             */
            @Size(max = 100)
            String kakaoPlaceId
    ) {}

    /** {@code kakaoPlaceId}는 받지 않는다 — 이름을 고친다고 다른 장소가 되지는 않는다. */
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
            String naverUrl
    ) {}
}
