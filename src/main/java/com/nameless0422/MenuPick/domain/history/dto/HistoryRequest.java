package com.nameless0422.MenuPick.domain.history.dto;

import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.OptBoolean;

public class HistoryRequest {

    public record VisitTimeRequest(
            @NotNull @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss", lenient = OptBoolean.FALSE)
            java.time.LocalDateTime visitedAt,
            @NotNull @Min(0) Long version
    ) {}

    /**
     * 추천 없이 먹은 끼니. {@code mealSlot}을 비우면 먹은 시각으로 추정한다.
     * 시각 형식은 방문 시각 수정과 같다 — 초까지, 시간대 없는 KST.
     */
    public record ManualMealRequest(
            @NotNull(message = "메뉴(menuId)는 필수입니다.") Long menuId,
            @NotNull(message = "먹은 시각(eatenAt)은 필수입니다.")
            @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss", lenient = OptBoolean.FALSE)
            java.time.LocalDateTime eatenAt,
            com.nameless0422.MenuPick.domain.history.MealSlot mealSlot,
            Long restaurantId,
            @Size(max = 500, message = "메모는 500자 이하여야 합니다.") String memo
    ) {}

    public record VisitRequest(
            Long restaurantId
    ) {
    }

    public record FeedbackRequest(@NotNull RecommendationFeedback feedback) {}

    public record MemoRequest(
            @Size(max = 500, message = "메모는 500자 이하여야 합니다.") String memo,
            @NotNull(message = "버전(version)은 필수입니다.") @Min(0) Long version
    ) {}
}
