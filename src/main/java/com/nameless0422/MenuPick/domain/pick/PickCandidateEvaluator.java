package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRepositoryCustom.PickCandidate;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** SQL 필터, 실제 거리 판정, 최근 추천 폴백을 픽과 대안에서 동일하게 적용한다. */
@Component
@RequiredArgsConstructor
class PickCandidateEvaluator {
    static final int RECENT_RECOMMENDATION_DAYS = 3;
    static final int FEEDBACK_WINDOW_DAYS = 30;
    private static final int RESTAURANT_BATCH_SIZE = 500;

    private final MenuRepository menuRepository;
    private final HistoryRepository historyRepository;
    private final Clock clock;

    Evaluation evaluate(Long userId, PickRequestNormalizer.NormalizedPickRequest normalized) {
        PickRequest request = normalized.request();
        List<PickCandidate> candidates = menuRepository.findPickCandidates(PickCandidates.of(userId,
                normalized.categories(), request == null ? null : request.tagIds(),
                request == null ? null : request.excludeTagIds(),
                request == null ? null : request.latitude(),
                request == null ? null : request.longitude(),
                request == null ? null : request.maxDistance()));
        LocalDateTime now = LocalDateTime.now(clock);
        List<HistoryRepository.MenuRecommendationSignals> signals =
                historyRepository.findMenuRecommendationSignalsSince(userId,
                        now.minusDays(FEEDBACK_WINDOW_DAYS), RecommendationFeedback.ACCEPTED,
                        RecommendationFeedback.REJECTED);
        return finish(filterByDistance(candidates, request), signals, now);
    }

    private List<PickCandidate> filterByDistance(List<PickCandidate> candidates, PickRequest request) {
        if (request == null || request.latitude() == null || request.longitude() == null
                || request.maxDistance() == null || candidates.isEmpty()) return candidates;
        Set<Long> inRange = new java.util.HashSet<>();
        for (int start = 0; start < candidates.size(); start += RESTAURANT_BATCH_SIZE) {
            List<Long> ids = candidates.subList(start,
                    Math.min(start + RESTAURANT_BATCH_SIZE, candidates.size()))
                    .stream().map(PickCandidate::id).toList();
            for (MenuRepository.PickCandidateRestaurant restaurant
                    : menuRepository.findPickCandidateRestaurants(ids)) {
                if (PickDistance.meters(request.latitude().doubleValue(), request.longitude().doubleValue(),
                        restaurant.getLatitude().doubleValue(), restaurant.getLongitude().doubleValue())
                        <= request.maxDistance()) inRange.add(restaurant.getMenuId());
            }
        }
        return candidates.stream().filter(menu -> inRange.contains(menu.id())).toList();
    }

    Universe loadUniverse(Long userId, Set<String> originalCategories) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<HistoryRepository.MenuRecommendationSignals> signals =
                historyRepository.findMenuRecommendationSignalsSince(userId,
                        now.minusDays(FEEDBACK_WINDOW_DAYS), RecommendationFeedback.ACCEPTED,
                        RecommendationFeedback.REJECTED);
        boolean filterCategories = !originalCategories.isEmpty();
        // Native IN에는 비어 있지 않은 컬렉션을 바인딩하되, 빈 요청의 의미는 별도 boolean으로
        // 결정한다. 빈 문자열 카테고리는 요청/저장 검증상 존재할 수 없어 충돌하지 않는다.
        Set<String> queryCategories = filterCategories ? originalCategories : Set.of("");
        Map<Long, CandidateFactBuilder> builders = new HashMap<>();
        for (MenuRepository.PickAlternativeFact row
                : menuRepository.findPickAlternativeFacts(userId, filterCategories, queryCategories)) {
            CandidateFactBuilder builder = builders.computeIfAbsent(row.getMenuId(), CandidateFactBuilder::new);
            if ("CATEGORY".equals(row.getKind()) && Long.valueOf(1L).equals(row.getCategoryMatched())) {
                builder.categoryMatched = true;
            } else if ("TAG".equals(row.getKind()) && row.getLongValue() != null) {
                builder.tagIds.add(row.getLongValue());
            }
        }
        for (MenuRepository.PickAlternativeRestaurant row
                : menuRepository.findPickAlternativeRestaurants(userId)) {
            CandidateFactBuilder builder = builders.get(row.getMenuId());
            if (builder != null) builder.restaurants.add(new Coordinates(row.getLatitude(), row.getLongitude()));
        }
        List<CandidateFact> facts = builders.values().stream().map(CandidateFactBuilder::build).toList();
        return new Universe(facts, signals, now);
    }

    FactEvaluation evaluate(Universe universe, PickRequestNormalizer.NormalizedPickRequest normalized) {
        PickRequest request = normalized.request();
        Set<Long> includes = request == null || request.tagIds() == null ? Set.of() : request.tagIds();
        Set<Long> excludes = request == null || request.excludeTagIds() == null
                ? Set.of() : withoutNulls(request.excludeTagIds());
        List<CandidateFact> filtered = universe.menus().stream()
                .filter(menu -> normalized.categories().isEmpty()
                        || menu.categoryMatched())
                .filter(menu -> includes.isEmpty() || (includes.stream().noneMatch(java.util.Objects::isNull)
                        && menu.tagIds().containsAll(includes)))
                .filter(menu -> menu.tagIds().stream().noneMatch(excludes::contains))
                .filter(menu -> withinDistance(menu, request))
                .toList();
        return finishFacts(filtered, universe.signals(), universe.now());
    }

    private Evaluation finish(List<PickCandidate> candidates,
            List<HistoryRepository.MenuRecommendationSignals> signals, LocalDateTime now) {
        Set<Long> recentIds = signals.stream()
                .filter(signal -> !signal.getLatestRecommendedAt()
                        .isBefore(now.minusDays(RECENT_RECOMMENDATION_DAYS)))
                .map(HistoryRepository.MenuRecommendationSignals::getMenuId)
                .collect(Collectors.toSet());
        List<PickCandidate> fresh = candidates.stream().filter(menu -> !recentIds.contains(menu.id())).toList();
        return new Evaluation(fresh.isEmpty() ? candidates : fresh, signals, !fresh.isEmpty());
    }

    private FactEvaluation finishFacts(List<CandidateFact> candidates,
            List<HistoryRepository.MenuRecommendationSignals> signals, LocalDateTime now) {
        Set<Long> recentIds = recentIds(signals, now);
        List<CandidateFact> fresh = candidates.stream()
                .filter(candidate -> !recentIds.contains(candidate.menuId())).toList();
        return new FactEvaluation(fresh.isEmpty() ? candidates : fresh);
    }

    private static Set<Long> recentIds(List<HistoryRepository.MenuRecommendationSignals> signals,
            LocalDateTime now) {
        return signals.stream().filter(signal -> !signal.getLatestRecommendedAt()
                        .isBefore(now.minusDays(RECENT_RECOMMENDATION_DAYS)))
                .map(HistoryRepository.MenuRecommendationSignals::getMenuId).collect(Collectors.toSet());
    }

    private static boolean withinDistance(CandidateFact menu, PickRequest request) {
        if (request == null || request.latitude() == null || request.longitude() == null
                || request.maxDistance() == null) return true;
        return menu.restaurants().stream().anyMatch(point -> PickDistance.meters(
                request.latitude().doubleValue(), request.longitude().doubleValue(),
                point.latitude().doubleValue(), point.longitude().doubleValue()) <= request.maxDistance());
    }

    private static Set<Long> withoutNulls(Set<Long> ids) {
        Set<Long> result = new LinkedHashSet<>(ids);
        result.remove(null);
        return result;
    }

    record Universe(List<CandidateFact> menus, List<HistoryRepository.MenuRecommendationSignals> signals,
                    LocalDateTime now) {
        boolean hasActiveLinkedRestaurant() {
            return menus.stream().anyMatch(menu -> !menu.restaurants().isEmpty());
        }
    }

    private record Coordinates(BigDecimal latitude, BigDecimal longitude) {}
    private record CandidateFact(Long menuId, boolean categoryMatched, Set<Long> tagIds,
                                 List<Coordinates> restaurants) {}
    private static final class CandidateFactBuilder {
        private final Long menuId;
        private boolean categoryMatched;
        private final Set<Long> tagIds = new LinkedHashSet<>();
        private final List<Coordinates> restaurants = new ArrayList<>();
        private CandidateFactBuilder(Long menuId) { this.menuId = menuId; }
        private CandidateFact build() {
            return new CandidateFact(menuId, categoryMatched, Set.copyOf(tagIds), List.copyOf(restaurants));
        }
    }

    record FactEvaluation(List<CandidateFact> candidates) {
        int distinctCandidateCount() {
            return (int) candidates.stream().map(CandidateFact::menuId).distinct().count();
        }
    }

    record Evaluation(List<PickCandidate> candidates,
                      List<HistoryRepository.MenuRecommendationSignals> signals,
                      boolean avoidedRecentRecommendation) {
        int distinctCandidateCount() {
            return (int) candidates.stream().map(PickCandidate::id).distinct().count();
        }
    }
}
