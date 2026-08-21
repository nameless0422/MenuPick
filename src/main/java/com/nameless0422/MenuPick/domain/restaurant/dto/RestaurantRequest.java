package com.nameless0422.MenuPick.domain.restaurant.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 문자열 필드의 {@code @Size} 상한은 전부 스키마의 컬럼 길이와 같다.
 * 검증이 없으면 초과 입력이 DB까지 내려가 제약 위반으로 409가 나는데, 그 응답에는
 * 어느 필드가 문제인지 담기지 않는다. 400 + 필드명으로 돌려주기 위한 것이다.
 *
 * <p>좌표도 같은 이유로 범위를 본다. 컬럼이 {@code DECIMAL(10,7)}이라 정수부가 3자리뿐이라
 * 큰 값은 MySQL strict 모드에서 "Out of range value"로 끊기는데, 그 예외 역시 어느 필드가
 * 문제인지 알려주지 않는다. 상한을 컬럼 크기가 아니라 위도 ±90 / 경도 ±180으로 잡은 것은
 * 그게 이 값의 실제 의미이기 때문이다 — 컬럼에는 들어가지만 지구상에 없는 좌표도 막는다.
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
            @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
            @DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.")
            BigDecimal latitude,
            @NotNull(message = "경도는 필수입니다.")
            @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
            @DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.")
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
            @DecimalMin(value = "-90.0", message = "위도는 -90 이상이어야 합니다.")
            @DecimalMax(value = "90.0", message = "위도는 90 이하여야 합니다.")
            BigDecimal latitude,
            @NotNull(message = "경도는 필수입니다.")
            @DecimalMin(value = "-180.0", message = "경도는 -180 이상이어야 합니다.")
            @DecimalMax(value = "180.0", message = "경도는 180 이하여야 합니다.")
            BigDecimal longitude,
            @Size(max = 500)
            String naverUrl
    ) {}
}
