package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.domain.VersionGuard;
import com.nameless0422.MenuPick.domain.history.dto.HistoryRequest;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoryService {

    private static final int VISIT_CALENDAR_LIMIT = 500;
    private static final YearMonth EARLIEST_CALENDAR_MONTH = YearMonth.of(2000, 1);

    private final HistoryRepository historyRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuRepository menuRepository;
    private final UserRepository userRepository;
    /** 기준 시간대(KST) 고정 — days 필터 경계가 서버 JVM 시간대에 좌우되지 않게 한다. */
    private final Clock clock;

    /** 하루(KST, 먹은 시각 기준)에 직접 남길 수 있는 끼니 수. 근거는 D-081. */
    static final int MANUAL_MEALS_PER_DAY = 10;
    /** 직접 기록으로 남길 수 있는 가장 오래된 날. 그보다 오래된 끼니는 기억이 아니라 추정이다. */
    static final int MANUAL_MEAL_MAX_AGE_DAYS = 365;

    /**
     * 추천 없이 먹은 끼니를 남긴다. 만들 때부터 먹은 기록이다 — {@link History#manual}.
     *
     * <p>하루 상한은 D-077과 같은 이유다: 기록 총량에 상한이 없어 계정 하나가 저장 공간과 조회
     * 성능을 깎을 수 있다. 픽 기록과 달리 여기는 사람이 손으로 적는 경로라 하루 10끼면 넉넉하다.
     * 판정은 사용자 행을 잠근 뒤 한다(D-040) — 잠금이 트랜잭션의 첫 조회여야 동시 요청이 옛 개수를
     * 보지 않는다.
     */
    @Transactional
    public HistoryResponse.HistorySummary createManualMeal(Long userId, HistoryRequest.ManualMealRequest request) {
        User user = userRepository.findByIdForUpdate(userId)
                .filter(found -> !found.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        LocalDateTime now = LocalDateTime.now(clock).withNano(0);
        LocalDateTime eatenAt = request.eatenAt().withNano(0);
        if (eatenAt.isAfter(now) || eatenAt.isBefore(manualEarliest(now))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT,
                    "먹은 시각은 최근 " + MANUAL_MEAL_MAX_AGE_DAYS + "일 안에서 현재 시각까지 지정해주세요.");
        }

        Menu menu = menuRepository.findByIdAndUserIdAndDeletedAtIsNull(request.menuId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
        Restaurant restaurant = null;
        if (request.restaurantId() != null) {
            restaurant = restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(request.restaurantId(), userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));
        }

        LocalDateTime dayStart = eatenAt.toLocalDate().atStartOfDay();
        if (historyRepository.countManualMealsBetween(userId, dayStart, dayStart.plusDays(1))
                >= MANUAL_MEALS_PER_DAY) {
            throw new BusinessException(ErrorCode.MANUAL_MEAL_DAILY_LIMIT_EXCEEDED);
        }

        java.math.BigDecimal servings = request.servings();
        // 0.5 단위만 받는다. 식당 양이 제각각이라 더 촘촘한 값은 정밀해 보이기만 한다(D-082).
        if (servings != null && servings.multiply(java.math.BigDecimal.valueOf(2)).stripTrailingZeros().scale() > 0) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "양은 0.5인분 단위로 지정해주세요.");
        }

        String memo = request.memo() == null ? null : request.memo().strip();
        History history = historyRepository.save(History.manual(user, menu, restaurant, eatenAt,
                request.mealSlot(), memo == null || memo.isEmpty() ? null : memo,
                servings == null ? null : servings.setScale(1, java.math.RoundingMode.UNNECESSARY)));
        return toSummary(history);
    }

    private static LocalDateTime manualEarliest(LocalDateTime now) {
        return now.toLocalDate().minusDays(MANUAL_MEAL_MAX_AGE_DAYS).atStartOfDay();
    }

    public HistoryResponse.HistoryListResponse getHistories(Long userId, Long cursor, Integer days, Boolean visited, int size) {
        return getHistories(userId, cursor, days, visited, size, null);
    }

    public HistoryResponse.HistoryListResponse getHistories(Long userId, Long cursor, Integer days, Boolean visited,
                                                           int size, String keyword) {
        HistoryFilters filters = HistoryFilters.of(clock, days, keyword);
        LocalDateTime after = filters.after();
        PageRequest pageable = PageRequest.of(0, size + 1);

        List<History> histories;
        if (filters.pattern() != null) {
            histories = historyRepository.searchByNames(userId, after, cursor, visited, filters.pattern(), pageable);
        } else if (visited != null && cursor != null) {
            histories = historyRepository.findByUserIdAndIsVisitedAndRecommendedAtAfterAndIdLessThanOrderByIdDesc(
                    userId, visited, after, cursor, pageable);
        } else if (visited != null) {
            histories = historyRepository.findByUserIdAndIsVisitedAndRecommendedAtAfterOrderByIdDesc(
                    userId, visited, after, pageable);
        } else if (cursor != null) {
            histories = historyRepository
                    .findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc(userId, after, cursor, pageable);
        } else {
            histories = historyRepository
                    .findByUserIdAndRecommendedAtAfterOrderByIdDesc(userId, after, pageable);
        }

        boolean hasNext = histories.size() > size;
        List<History> page = hasNext ? histories.subList(0, size) : histories;
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;

        List<HistoryResponse.HistorySummary> summaries = page.stream()
                .map(this::toSummary)
                .toList();

        return new HistoryResponse.HistoryListResponse(summaries, nextCursor, hasNext);
    }

    public HistoryResponse.VisitCalendarResponse getVisitCalendar(Long userId, String requestedMonth) {
        YearMonth currentMonth = YearMonth.from(LocalDateTime.now(clock));
        YearMonth month = parseCalendarMonth(requestedMonth, currentMonth);
        if (month.isBefore(EARLIEST_CALENDAR_MONTH) || month.isAfter(currentMonth)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.plusMonths(1).atDay(1).atStartOfDay();
        List<HistoryRepository.VisitCalendarRow> rows = historyRepository.findVisitCalendarRows(
                userId, start, end, PageRequest.of(0, VISIT_CALENDAR_LIMIT + 1));
        boolean truncated = rows.size() > VISIT_CALENDAR_LIMIT;
        List<HistoryResponse.VisitCalendarEntry> entries = new ArrayList<>(rows.stream()
                .limit(VISIT_CALENDAR_LIMIT)
                .map(row -> new HistoryResponse.VisitCalendarEntry(
                        row.getId(), row.getMenuName(), row.getRestaurantName(), row.getVisitedAt()))
                .toList());
        // DB에서는 최신 500건을 고른 뒤, 달력 계약에 맞게 오래된 순으로 응답한다.
        Collections.reverse(entries);
        return new HistoryResponse.VisitCalendarResponse(month, entries, truncated);
    }

    public HistoryResponse.MemoResponse getMemo(Long userId, Long historyId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        return new HistoryResponse.MemoResponse(history.getId(), history.getMemo(), history.getVersion());
    }

    public HistoryResponse.VisitTimeResponse getVisitTime(Long userId, Long historyId) {
        History history = findVisitedHistory(userId, historyId);
        return visitTimeResponse(history);
    }

    @Transactional
    public HistoryResponse.VisitTimeResponse updateVisitTime(Long userId, Long historyId,
                                                             HistoryRequest.VisitTimeRequest request) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        VersionGuard.requireCurrentVersion(history.getVersion(), request.version());
        LocalDateTime at = request.visitedAt().withNano(0);
        // 추천일의 한국 시간 00:00부터 현재 시각까지 초 단위로 수정한다.
        // 직접 기록은 추천일이 따로 없다 — 만들 때와 같은 범위(최근 1년) 안에서 옮길 수 있다.
        if (at.isBefore(earliestVisitAt(history))
                || at.isAfter(LocalDateTime.now(clock).withNano(0))) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, history.isManual()
                    ? "먹은 시각은 최근 " + MANUAL_MEAL_MAX_AGE_DAYS + "일 안에서 현재 시각까지 지정해주세요."
                    : "방문 일시는 추천일 이후부터 현재 시각까지 지정해주세요.");
        }
        history.changeVisitedAt(at);
        historyRepository.flush();
        return visitTimeResponse(history);
    }

    private History findVisitedHistory(Long userId, Long historyId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        if (!history.isVisited() || history.getVisitedAt() == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_VISITED);
        }
        return history;
    }

    private HistoryResponse.VisitTimeResponse visitTimeResponse(History history) {
        return new HistoryResponse.VisitTimeResponse(history.getId(), history.getVisitedAt(),
                earliestVisitAt(history), LocalDateTime.now(clock).withNano(0), history.getVersion());
    }

    private LocalDateTime earliestVisitAt(History history) {
        return history.isManual()
                ? manualEarliest(LocalDateTime.now(clock))
                : history.getRecommendedAt().toLocalDate().atStartOfDay();
    }

    @Transactional
    public HistoryResponse.MemoResponse updateMemo(Long userId, Long historyId, HistoryRequest.MemoRequest request) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        VersionGuard.requireCurrentVersion(history.getVersion(), request.version());
        String memo = request.memo() == null ? null : request.memo().strip();
        history.updateMemo(memo == null || memo.isEmpty() ? null : memo);
        // 응답 버전에 이번 쓰기를 반영하고, 커밋 전 경합도 낙관적 락으로 확인한다.
        historyRepository.flush();
        return new HistoryResponse.MemoResponse(history.getId(), history.getMemo(), history.getVersion());
    }

    private YearMonth parseCalendarMonth(String requestedMonth, YearMonth defaultMonth) {
        if (requestedMonth == null) {
            return defaultMonth;
        }
        // YearMonth.parse alone accepts forms such as +2026-01; the public contract is exactly YYYY-MM.
        if (!requestedMonth.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        try {
            return YearMonth.parse(requestedMonth);
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
    }

    @Transactional
    public void markVisited(Long userId, Long historyId, Long restaurantId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));

        Restaurant restaurant = null;
        if (restaurantId != null) {
            restaurant = restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(restaurantId, userId)
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));
        }

        history.markVisited(restaurant, LocalDateTime.now(clock));
    }

    @Transactional
    public void unmarkVisited(Long userId, Long historyId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        history.unmarkVisited();
    }

    @Transactional
    public void deleteHistory(Long userId, Long historyId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        historyRepository.delete(history);
    }

    @Transactional
    public void recordFeedback(Long userId, Long historyId, RecommendationFeedback feedback) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        history.recordFeedback(feedback);
    }

    /**
     * 히스토리 한 줄을 응답으로 옮긴다.
     *
     * <p><b>소프트 삭제된 메뉴·식당의 이름을 그대로 싣는 것은 의도다.</b> 코드 리뷰에서
     * "지운 식당을 새로 붙일 수는 없는데(아래 {@code markVisited}) 계속 보이기는 한다"는
     * 비대칭이 지적됐는데, 그 둘은 서로 다른 일이다 — {@code markVisited}는 지금 새로운
     * 사실을 만드는 쓰기이고, 여기는 이미 일어난 사실을 보여주는 읽기다. 8월 3일에
     * "김치찌개"를 추천받은 것은 그 메뉴를 나중에 지웠다고 해서 없던 일이 되지 않는다.
     *
     * <p>가려서 얻는 것도 없다. 히스토리는 조회 자체가 소유자 범위라
     * ({@code findByUserIdAnd...}) 남에게 새는 이름이 아니고, 이름을 지우면 "삭제된 메뉴"만
     * 늘어선 목록이 남아 무엇을 먹었는지 되짚는다는 이 화면의 유일한 쓸모가 사라진다.
     * 계정을 탈퇴하면 30일 뒤 히스토리까지 통째로 하드 삭제되므로 "영원히 남는다"는 것도
     * 아니다. 개별 기록을 지우고 싶으면 {@code deleteHistory}가 있다.
     *
     * <p>이 판단이 뒤집히면(예: 히스토리를 공유하는 기능이 생기면) 바꿔야 할 곳은 여기다.
     */
    private HistoryResponse.HistorySummary toSummary(History history) {
        List<HistoryResponse.FilterCondition> conditions = history.getFilterConditions().stream()
                .map(fc -> new HistoryResponse.FilterCondition(fc.getFilterType(), fc.getFilterValue()))
                .toList();

        return new HistoryResponse.HistorySummary(
                history.getId(),
                history.getMenu() != null && !history.getMenu().isDeleted() ? history.getMenu().getId() : null,
                history.getMenu() != null ? history.getMenu().getName() : null,
                history.getRestaurant() != null ? history.getRestaurant().getName() : null,
                history.isVisited(),
                history.getRecommendedAt(),
                history.getVisitedAt(),
                history.getRecommendationFeedback(),
                conditions,
                history.getMemo(),
                history.getSource(),
                history.effectiveMealSlot(),
                history.getServings(),
                history.estimatedKcal()
        );
    }
}
