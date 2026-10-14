package com.nameless0422.MenuPick.domain.restaurant.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public class RestaurantResponse {

    public record DeletedRestaurantSummary(Long id, String name, String address,
                                           LocalDateTime deletedAt, long version) {}

    public record DeletedRestaurantListResponse(List<DeletedRestaurantSummary> restaurants,
                                               String nextCursor, boolean hasNext) {}

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
            LocalDateTime updatedAt,
            /** 낙관적 락 버전. 수정 요청에 그대로 실어 보내야 한다 — 근거는 VersionGuard. */
            long version
    ) {}

    /**
     * 목록·카드 표시용 정보. 좌표·전화·지도 링크를 함께 내려 카드별 상세 조회를 생략한다.
     * 수정에 필요한 버전 등은 사용자가 수정 화면을 열 때 상세 API로 조회한다.
     */
    public record RestaurantSummary(
            Long id,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String phone,
            String naverUrl
    ) {}
}
