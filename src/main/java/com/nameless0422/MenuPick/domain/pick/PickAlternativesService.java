package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.pick.dto.PickAlternativesResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PickAlternativesService {
    private static final List<Integer> DISTANCE_STEPS = List.of(300, 500, 1_000, 2_000, 5_000);

    private final PickRequestNormalizer requestNormalizer;
    private final PickCandidateEvaluator candidateEvaluator;

    public PickAlternativesResponse find(Long userId, PickRequest request) {
        PickRequestNormalizer.NormalizedPickRequest base = requestNormalizer.normalize(userId, request);
        PickCandidateEvaluator.Universe universe = candidateEvaluator.loadUniverse(userId, base.categories());
        if (candidateEvaluator.evaluate(universe, base).distinctCandidateCount() > 0
                || universe.menus().isEmpty()
                || (distanceRequested(base.request()) && !universe.hasActiveLinkedRestaurant())) {
            return new PickAlternativesResponse(List.of());
        }

        List<PickAlternativesResponse.Alternative> alternatives = new ArrayList<>(2);
        Integer expandedDistance = firstSuccessfulDistance(universe, base, base.categories());
        if (expandedDistance != null) {
            int count = count(universe, base.with(base.categories(), expandedDistance));
            alternatives.add(new PickAlternativesResponse.Alternative(
                    PickAlternativesResponse.Type.EXPAND_DISTANCE, count,
                    PickAlternativesResponse.Changes.distance(expandedDistance)));
        }

        int clearedCount = base.categories().isEmpty() ? 0 : count(universe, base.with(Set.of(),
                base.request() == null ? null : base.request().maxDistance()));
        if (clearedCount > 0) {
            alternatives.add(new PickAlternativesResponse.Alternative(
                    PickAlternativesResponse.Type.CLEAR_CATEGORIES, clearedCount,
                    PickAlternativesResponse.Changes.clearCategories()));
        }

        if (alternatives.isEmpty() && !base.categories().isEmpty()) {
            Integer combinedDistance = firstSuccessfulDistance(universe, base, Set.of());
            if (combinedDistance != null) {
                int count = count(universe, base.with(Set.of(), combinedDistance));
                alternatives.add(new PickAlternativesResponse.Alternative(
                        PickAlternativesResponse.Type.CLEAR_CATEGORIES_AND_EXPAND_DISTANCE, count,
                        PickAlternativesResponse.Changes.both(combinedDistance)));
            }
        }
        return new PickAlternativesResponse(List.copyOf(alternatives.subList(0,
                Math.min(2, alternatives.size()))));
    }

    private Integer firstSuccessfulDistance(PickCandidateEvaluator.Universe universe,
            PickRequestNormalizer.NormalizedPickRequest base, Set<String> categories) {
        PickRequest request = base.request();
        if (!distanceRequested(request)) return null;
        for (int step : DISTANCE_STEPS) {
            if (step > request.maxDistance() && count(universe, base.with(categories, step)) > 0) {
                return step;
            }
        }
        return null;
    }

    private int count(PickCandidateEvaluator.Universe universe,
            PickRequestNormalizer.NormalizedPickRequest request) {
        return candidateEvaluator.evaluate(universe, request).distinctCandidateCount();
    }

    private static boolean distanceRequested(PickRequest request) {
        return request != null && request.latitude() != null && request.longitude() != null
                && request.maxDistance() != null;
    }
}
