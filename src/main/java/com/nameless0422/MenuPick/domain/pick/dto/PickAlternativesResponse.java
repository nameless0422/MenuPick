package com.nameless0422.MenuPick.domain.pick.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Set;

public record PickAlternativesResponse(List<Alternative> alternatives) {
    public enum Type {
        EXPAND_DISTANCE,
        CLEAR_CATEGORIES,
        CLEAR_CATEGORIES_AND_EXPAND_DISTANCE,
        /** 최근에 뽑은 메뉴를 다시 포함한다. */
        DROP_RECENT_EXCLUSION
    }

    public record Alternative(Type type, int candidateCount, Changes changes) {
    }

    /**
     * 바뀌는 조건만 담는다. null은 "그대로"라는 뜻이고, 그래서 "끈다"를 null로 표현할 수 없다
     * — 카테고리는 빈 집합이, 최근 제외는 {@code true} 플래그가 그 자리를 맡는다.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Changes(Integer maxDistance, Set<String> categories, Boolean clearRecentExclusion) {
        public static Changes distance(int meters) {
            return new Changes(meters, null, null);
        }

        public static Changes clearCategories() {
            return new Changes(null, Set.of(), null);
        }

        public static Changes both(int meters) {
            return new Changes(meters, Set.of(), null);
        }

        public static Changes dropRecentExclusion() {
            return new Changes(null, null, true);
        }
    }
}
