package com.nameless0422.MenuPick.domain.pick.dto;

import com.nameless0422.MenuPick.domain.pick.PickPreset;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 프리셋 응답.
 *
 * <p><b>{@code userId}·좌표·장소를 담지 않는다.</b> 소유자는 인증 주체로만 정해지므로
 * 응답에 실을 이유가 없고, 좌표는 애초에 저장하지 않는다.
 */
public final class PickPresetResponse {

    private PickPresetResponse() {
    }

    public record Detail(
            Long id,
            String name,
            Set<String> categories,
            Set<Long> includeTagIds,
            /** <b>추가</b> 제외만. 기본 제외는 실행 시점에 합쳐지므로 여기 없다. */
            Set<Long> additionalExcludeTagIds,
            Integer maxDistance,
            /** 참조 태그가 지워져 실행이 막힌 상태인가. 화면이 경고와 편집을 유도한다. */
            boolean needsReview,
            Long version,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        public static Detail from(PickPreset preset) {
            return new Detail(
                    preset.getId(),
                    preset.getName(),
                    Set.copyOf(preset.getCategories()),
                    preset.includeTagIds(),
                    preset.excludeTagIds(),
                    preset.getMaxDistance(),
                    preset.isNeedsReview(),
                    preset.getVersion(),
                    preset.getCreatedAt(),
                    preset.getUpdatedAt());
        }
    }

    /**
     * 실행 결과 — 픽 결과에 <b>서버가 실제로 적용한 조건</b>을 함께 싣는다.
     *
     * <p>미리보기를 띄운 뒤 다른 탭에서 기본 제외 태그를 바꿨다면 화면이 보여준 조건과 실제
     * 적용된 조건이 다르다. 그때 권위 있는 값은 서버가 방금 쓴 이것이고, 화면은 결과에
     * "실제로 이 조건으로 뽑았다"를 이 값으로 표시한다.
     */
    public record ExecutionResult(
            PickResponse.PickResult pick,
            AppliedFilters appliedFilters
    ) {}

    public record AppliedFilters(
            Set<String> categories,
            Set<Long> includeTagIds,
            /** 기본 제외 + 추가 제외의 합집합. 프리셋에 저장된 값이 아니라 실행 시점 값이다. */
            Set<Long> effectiveExcludeTagIds,
            Integer maxDistance
    ) {
        /** 화면이 "기본 제외가 몇 개 더해졌는지"를 설명할 수 있도록 셈을 돕는다. */
        public int effectiveExcludeCount() {
            return effectiveExcludeTagIds.size();
        }
    }

    public record ListResult(List<Detail> presets, int limit) {
        public static ListResult of(List<Detail> presets) {
            return new ListResult(presets, PickPreset.MAX_PER_USER);
        }
    }
}
