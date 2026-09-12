package com.nameless0422.MenuPick.domain.trend;
import com.nameless0422.MenuPick.domain.trend.dto.PickTrendResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.List;

@Service @RequiredArgsConstructor
public class PickTrendService {
    private static final Duration MAX_AGE = Duration.ofHours(36);
    private final PickTrendRepository trendRepository;
    private final PickTrendSnapshotRepository snapshotRepository;
    private final PickTrendProperties properties;
    private final Clock clock;
    @Transactional(readOnly = true)
    public PickTrendResponse currentTrends() {
        if (!properties.enabled()) return empty(PickTrendResponse.Status.DISABLED, null);
        PickTrendSnapshot snapshot = snapshotRepository.findById(PickTrendSnapshot.ID).orElse(null);
        if (snapshot == null || snapshot.getComputedAt() == null)
            return empty(PickTrendResponse.Status.NOT_READY, null);
        LocalDateTime now = LocalDateTime.now(clock);
        boolean stale = snapshot.getWindowDays() != properties.windowDays()
                || snapshot.getMinUsers() != properties.minUsers()
                || snapshot.getMaxLabels() != properties.maxLabels()
                || snapshot.getComputedAt().isAfter(now)
                || snapshot.getComputedAt().isBefore(now.minus(MAX_AGE));
        if (stale) return empty(PickTrendResponse.Status.STALE, snapshot.getComputedAt());
        List<PickTrend> categories = safeRows(TrendDimension.CATEGORY, snapshot.getMinUsers());
        List<PickTrend> menus = safeRows(TrendDimension.MENU, snapshot.getMinUsers());
        return new PickTrendResponse(categories.stream().map(PickTrendResponse.Entry::from).toList(),
                menus.stream().map(PickTrendResponse.Entry::from).toList(), PickTrendResponse.Status.READY,
                snapshot.getWindowDays(), snapshot.getMinUsers(), snapshot.getMaxLabels(), snapshot.getComputedAt());
    }
    private List<PickTrend> safeRows(TrendDimension dimension, int storedThreshold) {
        int threshold = Math.max(storedThreshold, properties.minUsers());
        return trendRepository.findByDimensionOrderByRankOrderAsc(dimension).stream()
                .filter(t -> t.getUserCount() >= threshold && TrendVocabulary.allows(dimension, t.getLabel()))
                .limit(properties.maxLabels()).toList();
    }
    private PickTrendResponse empty(PickTrendResponse.Status status, LocalDateTime computedAt) {
        return new PickTrendResponse(List.of(), List.of(), status, properties.windowDays(),
                properties.minUsers(), properties.maxLabels(), computedAt);
    }
}
