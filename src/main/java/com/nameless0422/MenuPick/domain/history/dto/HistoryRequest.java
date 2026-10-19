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
