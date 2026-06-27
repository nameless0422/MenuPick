package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HistoryService {

    private static final int DEFAULT_DAYS = 7;

    private final HistoryRepository historyRepository;

    public HistoryResponse.HistoryListResponse getHistories(Long userId, Long cursor, Integer days, int size) {
        int effectiveDays = (days != null && days > 0) ? days : DEFAULT_DAYS;
        LocalDateTime after = LocalDateTime.now().minusDays(effectiveDays);
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
    public void markVisited(Long userId, Long historyId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        history.markVisited();
    }

    @Transactional
    public void deleteHistory(Long userId, Long historyId) {
        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        historyRepository.delete(history);
    }

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
