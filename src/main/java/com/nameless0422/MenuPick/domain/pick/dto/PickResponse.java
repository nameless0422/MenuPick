package com.nameless0422.MenuPick.domain.pick.dto;

import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public class PickResponse {

    public record PickResult(
            Long historyId,
            MenuResponse.MenuDetail menu,
            List<RestaurantWithDistance> restaurants,
            List<String> reasons
    ) {
        /** 기존 호출부와 API 테스트가 이유 필드 추가와 독립적으로 동작하도록 둔 호환 생성자. */
        public PickResult(Long historyId, MenuResponse.MenuDetail menu,
                          List<RestaurantWithDistance> restaurants) {
            this(historyId, menu, restaurants, List.of());
        }
    }

    /**
     * 좌표를 함께 내려준다 — 픽 결과 화면이 추천 식당을 지도에 찍는데, 이게 없으면
     * 식당 목록을 따로 조회해 id로 좌표를 짜맞춰야 한다. distance는 요청에 기준 좌표가
     * 없으면 null이지만 좌표는 항상 있다(restaurants 테이블이 NOT NULL).
     */
    public record RestaurantWithDistance(
            Long id,
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            Double distance
    ) {}

    /**
     * 게스트 데모 픽 결과. 시연용 샘플이라 id·가중치·히스토리처럼 사용자에 귀속되는 값이
     * 없다 — 저장되지 않는 결과에 식별자를 붙이면 저장된 것처럼 오해를 준다.
     */
    public record DemoPickResult(
            String name,
            Set<String> categories
    ) {}
}
