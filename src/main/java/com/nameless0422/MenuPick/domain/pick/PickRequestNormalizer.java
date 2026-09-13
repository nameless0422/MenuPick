package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 픽과 픽 대안이 공유하는 요청 정규화 경계. */
@Component
@RequiredArgsConstructor
class PickRequestNormalizer {

    private final DefaultPickPreferenceService defaultPickPreferenceService;

    NormalizedPickRequest normalize(Long userId, PickRequest request) {
        PickRequest effective = request;
        if (request == null || request.excludeTagIds() == null) {
            Set<Long> defaults = defaultPickPreferenceService.getDefaultExcludedTagIds(userId);
            if (!defaults.isEmpty()) {
                effective = new PickRequest(
                        request == null ? null : request.categories(),
                        request == null ? null : request.tagIds(), defaults,
                        request == null ? null : request.latitude(),
                        request == null ? null : request.longitude(),
                        request == null ? null : request.maxDistance());
            }
        }
        return new NormalizedPickRequest(effective, normalizeCategories(
                effective == null ? null : effective.categories()));
    }

    private static Set<String> normalizeCategories(Set<String> raw) {
        if (raw == null) return Set.of();
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(category -> !category.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    record NormalizedPickRequest(PickRequest request, Set<String> categories) {
        NormalizedPickRequest with(Set<String> replacementCategories, Integer replacementDistance) {
            PickRequest source = request;
            return new NormalizedPickRequest(new PickRequest(
                    replacementCategories, source == null ? null : source.tagIds(),
                    source == null ? null : source.excludeTagIds(),
                    source == null ? null : source.latitude(),
                    source == null ? null : source.longitude(), replacementDistance),
                    replacementCategories);
        }
    }
}
