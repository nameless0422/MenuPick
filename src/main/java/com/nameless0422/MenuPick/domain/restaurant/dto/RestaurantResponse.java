package com.nameless0422.MenuPick.domain.restaurant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class RestaurantResponse {

    public record RestaurantDetail(
            Long id,
            String name,
            String address,
            String phone,
            BigDecimal latitude,
            BigDecimal longitude,
            String naverUrl,
            String kakaoPlaceId,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    /**
     * 목록용 요약. 좌표는 상세에도 있지만 여기에도 둔다 — 지도에 마커를 찍으려면
     * 목록 응답만으로 위치를 알 수 있어야 하고, 없으면 화면이 식당 수만큼 상세를
     * 추가로 조회하게 된다.
     */
    public record RestaurantSummary(
            Long id,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude
    ) {}
}
