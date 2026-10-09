package com.nameless0422.MenuPick.domain.history.dto;

import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public class HistoryRequest {

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
