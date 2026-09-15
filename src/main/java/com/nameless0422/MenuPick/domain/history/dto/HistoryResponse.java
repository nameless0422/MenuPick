package com.nameless0422.MenuPick.domain.history.dto;

import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

public class HistoryResponse {

    public record HistorySummary(
            Long id,
            String menuName,
            String restaurantName,
            boolean isVisited,
            LocalDateTime recommendedAt,
            LocalDateTime visitedAt,
            /**
             * 픽 결과에서 누른 수락/거절. 누르지 않았으면 {@code null}이다.
             *
             * <p>픽 화면이 "지난번 뽑은 메뉴, 드셨어요?"를 물을지 정하는 데 쓴다 — 이미 거절한
             * 메뉴를 두고 먹었냐고 물으면 방금 한 대답을 무시한 셈이 된다.
             */
            RecommendationFeedback recommendationFeedback,
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

    public record VisitCalendarEntry(
            Long id,
            String menuName,
            String restaurantName,
            LocalDateTime visitedAt
    ) {}

    public record VisitCalendarResponse(
            YearMonth month,
            List<VisitCalendarEntry> entries,
            boolean truncated
    ) {}
}
