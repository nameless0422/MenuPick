package com.nameless0422.MenuPick.domain.menu.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code memo}의 상한 1000자 근거는 {@link MenuRequest}와 같다 — 컬럼이 {@code TEXT}라
 * 한계가 문자 수가 아니라 65,535<b>바이트</b>이고, utf8mb4에서 한글 3바이트·이모지 4바이트라
 * 검증이 없으면 초과 입력이 DB까지 내려가 원인 불명의 409가 된다.
 * 문자당 4바이트인 최악의 경우에도 4,000바이트라 컬럼 한계에 닿지 않는다.
 */
public class MenuRestaurantRequest {

    public record Create(
            @NotNull(message = "식당 ID는 필수입니다.")
            Long restaurantId,
            // rating은 선택 값(null 허용)이지만, 값이 있으면 1~5 범위여야 한다 (DB TINYINT + UI 별점 계약).
            @Min(value = 1, message = "평점은 1 이상이어야 합니다.")
            @Max(value = 5, message = "평점은 5 이하여야 합니다.")
            Integer rating,
            @Size(max = 1000, message = "메모는 1000자 이하여야 합니다.")
            String memo
    ) {}

    public record Update(
            @Min(value = 1, message = "평점은 1 이상이어야 합니다.")
            @Max(value = 5, message = "평점은 5 이하여야 합니다.")
            Integer rating,
            @Size(max = 1000, message = "메모는 1000자 이하여야 합니다.")
            String memo
    ) {}
}
