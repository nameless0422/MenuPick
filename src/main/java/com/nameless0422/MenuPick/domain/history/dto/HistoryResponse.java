package com.nameless0422.MenuPick.domain.history.dto;

import com.nameless0422.MenuPick.domain.history.HistorySource;
import com.nameless0422.MenuPick.domain.history.MealSlot;

import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

public class HistoryResponse {

    public record VisitTimeResponse(Long historyId, LocalDateTime visitedAt,
                                    LocalDateTime earliestVisitAt, LocalDateTime latestVisitAt, Long version) {}

    public record HistorySummary(
            Long id,
            /** 연결 식당을 조회할 활성 메뉴 ID. 삭제된 메뉴이거나 참조가 없으면 null이다. */
            Long menuId,
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
            List<FilterCondition> filterConditions,
            String memo,
            /** 픽에서 생긴 기록인지 직접 적은 끼니인지. 직접 기록은 방문 취소가 없다. */
            HistorySource source,
            /** 고른 끼니, 없으면 시각으로 추정한 끼니. 늘 채워져 있다. */
            MealSlot mealSlot,
            /** 먹은 양(인분). 픽 기록은 1. */
            java.math.BigDecimal servings,
            /** 열량 추정치(kcal). 메뉴 열량을 모르면 null이다 — 0과 다르다(D-082). */
            Integer kcal,
            /** 탄단지 추정치. 셋 중 하나라도 모르면 null이다(D-085). */
            MealMacros macros
    ) {
        /** 탄단지 전 호출부 호환. */
        public HistorySummary(Long id, Long menuId, String menuName, String restaurantName, boolean isVisited,
                              LocalDateTime recommendedAt, LocalDateTime visitedAt,
                              RecommendationFeedback recommendationFeedback,
                              List<FilterCondition> filterConditions, String memo,
                              HistorySource source, MealSlot mealSlot, java.math.BigDecimal servings, Integer kcal) {
            this(id, menuId, menuName, restaurantName, isVisited, recommendedAt, visitedAt,
                    recommendationFeedback, filterConditions, memo, source, mealSlot, servings, kcal, null);
        }

        /** 열량 전 호출부 호환. */
        public HistorySummary(Long id, Long menuId, String menuName, String restaurantName, boolean isVisited,
                              LocalDateTime recommendedAt, LocalDateTime visitedAt,
                              RecommendationFeedback recommendationFeedback,
                              List<FilterCondition> filterConditions, String memo,
                              HistorySource source, MealSlot mealSlot) {
            this(id, menuId, menuName, restaurantName, isVisited, recommendedAt, visitedAt,
                    recommendationFeedback, filterConditions, memo, source, mealSlot, java.math.BigDecimal.ONE, null);
        }

        /** 끼니 구분 전 호출부 호환. 픽 기록으로 보고 끼니는 비운다. */
        public HistorySummary(Long id, Long menuId, String menuName, String restaurantName, boolean isVisited,
                              LocalDateTime recommendedAt, LocalDateTime visitedAt,
                              RecommendationFeedback recommendationFeedback,
                              List<FilterCondition> filterConditions, String memo) {
            this(id, menuId, menuName, restaurantName, isVisited, recommendedAt, visitedAt,
                    recommendationFeedback, filterConditions, memo, HistorySource.PICK, null);
        }
    }

    /** 한 끼의 탄수화물·단백질·지방(g, 먹은 양을 곱해 반올림한 정수). */
    public record MealMacros(int carbsG, int proteinG, int fatG) {}

    public record MemoResponse(Long historyId, String memo, Long version) {}

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
            List<ForgottenMenu> forgottenMenus,
            /**
             * 추천 없이 직접 남긴 끼니 수. {@code eaten}에 이미 들어 있다 — 그래서 {@code eaten}이
             * {@code picks}보다 클 수 있다. 따로 주는 이유는 "뽑은 10번 중 12번 먹었다"를 설명하기 위해서다.
             */
            long logged
    ) {
        /** 직접 기록 전 호출부 호환. */
        public EatingSummaryResponse(int periodDays, long picks, long eaten, List<LabelCount> categories,
                                     List<LabelCount> menus, List<ForgottenMenu> forgottenMenus) {
            this(periodDays, picks, eaten, categories, menus, forgottenMenus, 0);
        }
    }

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
