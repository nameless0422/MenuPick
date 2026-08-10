package com.nameless0422.MenuPick.domain.pick.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 랜덤 픽 필터 요청.
 *
 * <p>모든 필드가 선택 값이다 — 본문 자체가 없으면(required=false) 검증도 수행되지 않고
 * 필터 없는 전체 후보 픽으로 동작한다. 값이 들어온 경우에만 아래 범위 제약이 적용된다.
 */
public record PickRequest(
        Set<String> categories,
        Set<Long> tagIds,
        Set<Long> excludeTagIds,
        @DecimalMin(value = "-90", message = "위도는 -90 이상이어야 합니다.")
        @DecimalMax(value = "90", message = "위도는 90 이하여야 합니다.")
        BigDecimal latitude,
        @DecimalMin(value = "-180", message = "경도는 -180 이상이어야 합니다.")
        @DecimalMax(value = "180", message = "경도는 180 이하여야 합니다.")
        BigDecimal longitude,
        @Positive(message = "최대 거리는 0보다 커야 합니다.")
        Integer maxDistance
) {}
