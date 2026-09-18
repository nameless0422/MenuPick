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

    /**
     * 주변 식당 선택 결과. 두 플래그로 화면이 무엇이 새로 생겼는지 말할 수 있다 —
     * "저장하고 연결했어요"와 "이미 연결된 식당이에요"는 사용자에게 다른 사실이다.
     */
    public record PlaceChoiceResponse(
            Long restaurantId,
            String restaurantName,
            boolean restaurantCreated,
            boolean linkCreated
    ) {}

    /**
     * 내 식사 기록 요약.
     *
     * <p>{@code picks}와 {@code eaten}을 둘 다 준다. 비율 하나로 줄이면 "10번 뽑아 3번 먹었다"와
     * "3번 뽑아 3번 먹었다"가 같은 30%가 되는데, 사용자에게는 완전히 다른 이야기다.
     */
    public record EatingSummaryResponse(
            int periodDays,
            long picks,
            long eaten,
            List<LabelCount> categories,
            List<LabelCount> menus,
            /** 오래 안 뽑힌 메뉴. 이것만 기간을 보지 않는다 — 근거는 EatingSummaryService. */
            List<ForgottenMenu> forgottenMenus
    ) {}

    public record LabelCount(String label, long count) {}

    /** {@code lastPickedAt}이 null이면 한 번도 뽑힌 적이 없다는 뜻이다. */
    public record ForgottenMenu(Long menuId, String name, LocalDateTime lastPickedAt) {}

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
