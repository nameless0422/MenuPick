package com.nameless0422.MenuPick.domain.history.dto;

import java.time.LocalDateTime;
import java.util.List;

public class HistoryResponse {

    public record HistorySummary(
            Long id,
            String menuName,
            String restaurantName,
            boolean isVisited,
            LocalDateTime recommendedAt,
            LocalDateTime visitedAt,
            List<FilterCondition> filterConditions
    ) {}

    public record FilterCondition(
            String filterType,
            String filterValue
    ) {}

    public record HistoryListResponse(
            List<HistorySummary> histories,
            Long nextCursor,
            boolean hasNext
    ) {}
}
