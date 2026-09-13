package com.nameless0422.MenuPick.domain.pick.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Set;

public record PickAlternativesResponse(List<Alternative> alternatives) {
    public enum Type {
        EXPAND_DISTANCE,
        CLEAR_CATEGORIES,
        CLEAR_CATEGORIES_AND_EXPAND_DISTANCE
    }

    public record Alternative(Type type, int candidateCount, Changes changes) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Changes(Integer maxDistance, Set<String> categories) {
        public static Changes distance(int meters) {
            return new Changes(meters, null);
        }

        public static Changes clearCategories() {
            return new Changes(null, Set.of());
        }

        public static Changes both(int meters) {
            return new Changes(meters, Set.of());
        }
    }
}
