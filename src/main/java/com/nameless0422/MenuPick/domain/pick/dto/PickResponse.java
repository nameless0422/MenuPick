package com.nameless0422.MenuPick.domain.pick.dto;

import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;

import java.util.List;
import java.util.Set;

public class PickResponse {

    public record PickResult(
            Long historyId,
            MenuResponse.MenuDetail menu,
            List<RestaurantWithDistance> restaurants
    ) {}

    public record RestaurantWithDistance(
            Long id,
            String name,
            String address,
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
