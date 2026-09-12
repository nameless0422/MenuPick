package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/** 닫힌 어휘의 최근 선택·방문 사용자 수를 원자적 스냅샷으로 교체한다. */
@Service @RequiredArgsConstructor
public class PickTrendAggregationService {
    private final HistoryRepository historyRepository;
    private final PickTrendRepository trendRepository;
    private final PickTrendSnapshotRepository snapshotRepository;
    private final PickTrendProperties properties;
    private final Clock clock;

    @Transactional
    public Result recompute() {
        if (!properties.enabled()) return Result.disabled(properties);
        PickTrendSnapshot snapshot = snapshotRepository.lockSingleton()
                .orElseThrow(() -> new IllegalStateException("trend snapshot singleton is missing"));
        // 직렬화 락을 얻은 뒤 경계를 잡아, 기다린 회차가 과거 시각으로 새 결과를 덮지 않게 한다.
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(properties.windowDays());
        List<PickTrend> rows = new ArrayList<>();
        rows.addAll(rank(TrendDimension.CATEGORY,
                historyRepository.countUsersByCategorySince(since, now, TrendVocabulary.CATEGORIES,
                        "ACCEPTED"), snapshot));
        rows.addAll(rank(TrendDimension.MENU,
                historyRepository.countUsersByMenuNameSince(since, now, TrendVocabulary.MENU_NAMES,
                        "ACCEPTED"), snapshot));
        trendRepository.deleteAllTrends();
        trendRepository.saveAll(rows);
        snapshot.complete(now, properties);
        snapshotRepository.save(snapshot);
        return new Result(true, count(rows, TrendDimension.CATEGORY), count(rows, TrendDimension.MENU),
                properties.windowDays(), properties.minUsers(), now);
    }

    private List<PickTrend> rank(TrendDimension dimension, List<HistoryRepository.TrendCount> counts,
                                 PickTrendSnapshot snapshot) {
        List<HistoryRepository.TrendCount> eligible = counts.stream()
                // 쿼리가 이미 IN으로 제한하지만, 저장 직전에도 자유 문자열을 거부한다.
                .filter(c -> TrendVocabulary.allows(dimension, c.getLabel()))
                .filter(c -> c.getUserCount() != null && c.getUserCount() >= properties.minUsers())
                .sorted(Comparator.comparingLong((HistoryRepository.TrendCount c) -> c.getUserCount()).reversed()
                        .thenComparing(HistoryRepository.TrendCount::getLabel))
                .limit(properties.maxLabels()).toList();
        List<PickTrend> rows = new ArrayList<>(eligible.size());
        for (int i = 0; i < eligible.size(); i++) {
            var c = eligible.get(i);
            rows.add(PickTrend.builder().dimension(dimension).label(c.getLabel())
                    .userCount(Math.toIntExact(c.getUserCount())).rankOrder(i + 1).snapshot(snapshot).build());
        }
        return rows;
    }

    private int count(List<PickTrend> rows, TrendDimension dimension) {
        return (int) rows.stream().filter(r -> r.getDimension() == dimension).count();
    }

    public record Result(boolean enabled, int categoryCount, int menuCount, int windowDays,
                         int minUsers, LocalDateTime computedAt) {
        static Result disabled(PickTrendProperties p) {
            return new Result(false, 0, 0, p.windowDays(), p.minUsers(), null);
        }
    }
}
