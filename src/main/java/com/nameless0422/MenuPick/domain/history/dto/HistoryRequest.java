package com.nameless0422.MenuPick.domain.history.dto;

import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;
import jakarta.validation.constraints.NotNull;

public class HistoryRequest {

    public record VisitRequest(
            Long restaurantId
    ) {
    }

    public record FeedbackRequest(@NotNull RecommendationFeedback feedback) {}
}
