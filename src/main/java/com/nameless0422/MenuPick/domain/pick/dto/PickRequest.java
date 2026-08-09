package com.nameless0422.MenuPick.domain.pick.dto;

import java.math.BigDecimal;
import java.util.Set;

public record PickRequest(
        Set<String> categories,
        Set<Long> tagIds,
        Set<Long> excludeTagIds,
        BigDecimal latitude,
        BigDecimal longitude,
        Integer maxDistance
) {}
