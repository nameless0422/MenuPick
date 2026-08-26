package com.nameless0422.MenuPick.domain.restaurant.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 문자열 필드의 {@code @Size} 상한은 전부 스키마의 컬럼 길이와 같다.
 * 검증이 없으면 초과 입력이 DB까지 내려가 제약 위반으로 409가 나는데, 그 응답에는
 * 어느 필드가 문제인지 담기지 않는다. 400 + 필드명으로 돌려주기 위한 것이다.
 *
 * <p>{@code naverUrl}에만 형식 제약이 하나 더 붙는다. 이 값은 화면에서 그대로 {@code <a href>}가
 * 되는데, 길이만 보고 통과시키면 {@code javascript:...}를 저장해 자기 브라우저에서 스크립트를
 * 실행시킬 수 있다(self-XSS). 지금은 자기 데이터만 자기가 보므로 피해가 자신에게 갇히지만,
 * 공유·추천처럼 남의 식당이 내 화면에 그려지는 기능이 하나 붙는 순간 저장형 XSS가 된다.
 * 프론트도 같은 판정을 한 번 더 한다({@code frontend/src/externalUrl.ts}) — 여기 제약은
 * 앞으로 들어올 값만 막고, 이미 저장된 행은 그쪽이 막는다.
 *
 * <p>좌표도 같은 이유로 범위를 본다. 컬럼이 {@code DECIMAL(10,7)}이라 정수부가 3자리뿐이라
 * 큰 값은 MySQL strict 모드에서 "Out of range value"로 끊기는데, 그 예외 역시 어느 필드가
 * 문제인지 알려주지 않는다. 상한을 컬럼 크기가 아니라 위도 ±90 / 경도 ±180으로 잡은 것은
 * 그게 이 값의 실제 의미이기 때문이다 — 컬럼에는 들어가지만 지구상에 없는 좌표도 막는다.
 */
public class RestaurantRequest {

    /**
     * 링크로 걸어도 되는 주소의 형식. 빈 문자열을 허용하는 이유는 화면이 "지도 링크 없음"을
     * 빈 입력으로 보내기 때문이다({@code @Pattern}은 null만 건너뛰고 빈 문자열은 검사한다).
     * 공백을 허용하지 않는 것도 의도다 — 스킴 이름 사이에 탭·개행을 끼워
     * 스킴 검사를 피하는 고전적인 수법이 여기서 함께 막힌다.
     */
    static final String NAVER_URL_PATTERN = "^(https?://\\S+)?$";

    static final String NAVER_URL_MESSAGE = "지도 링크는 http 또는 https 주소여야 합니다.";

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
            @Pattern(regexp = NAVER_URL_PATTERN, message = NAVER_URL_MESSAGE)
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
            @Pattern(regexp = NAVER_URL_PATTERN, message = NAVER_URL_MESSAGE)
            String naverUrl
    ) {}
}
