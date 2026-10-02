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
                        request == null ? null : request.maxDistance(),
                        request == null ? null : request.excludeRecentDays());
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
            // 최근 제외는 그대로 들고 간다. 여기서 흘리면 대안이 세는 후보 수가 실제보다
            // 많아져, 화면이 "거리를 넓히면 3개"라고 한 뒤 눌러도 또 0개가 된다.
            return new NormalizedPickRequest(new PickRequest(
                    replacementCategories, source == null ? null : source.tagIds(),
                    source == null ? null : source.excludeTagIds(),
                    source == null ? null : source.latitude(),
                    source == null ? null : source.longitude(), replacementDistance,
                    source == null ? null : source.excludeRecentDays()),
                    replacementCategories);
        }

        /** 최근 제외만 풀고 나머지는 그대로 둔 요청. 대안("최근 제외 끄기")의 후보 수를 센다. */
        NormalizedPickRequest withoutRecentExclusion() {
            PickRequest source = request;
            if (source == null || source.excludeRecentDays() == null) return this;
            return new NormalizedPickRequest(new PickRequest(
                    source.categories(), source.tagIds(), source.excludeTagIds(),
                    source.latitude(), source.longitude(), source.maxDistance(), null),
                    categories);
        }
    }
}
