package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoryService {

    private static final int DEFAULT_DAYS = 7;

    private final HistoryRepository historyRepository;
    private final RestaurantRepository restaurantRepository;
    /** 기준 시간대(KST) 고정 — days 필터 경계가 서버 JVM 시간대에 좌우되지 않게 한다. */
    private final Clock clock;

    public HistoryResponse.HistoryListResponse getHistories(Long userId, Long cursor, Integer days, int size) {
        // days는 컨트롤러에서 @Min(1)로 검증되므로 여기서는 미지정(null) 여부만 판단한다.
        int effectiveDays = (days != null) ? days : DEFAULT_DAYS;
        LocalDateTime after = LocalDateTime.now(clock).minusDays(effectiveDays);
        PageRequest pageable = PageRequest.of(0, size + 1);

        List<History> histories;
        if (cursor != null) {
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
    public void deleteHistory(Long userId, Long historyId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        historyRepository.delete(history);
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
                history.getMenu() != null ? history.getMenu().getName() : null,
                history.getRestaurant() != null ? history.getRestaurant().getName() : null,
                history.isVisited(),
                history.getRecommendedAt(),
                history.getVisitedAt(),
                conditions
        );
    }
}
