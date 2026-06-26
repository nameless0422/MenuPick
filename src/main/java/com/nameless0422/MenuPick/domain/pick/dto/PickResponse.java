package com.nameless0422.MenuPick.domain.pick.dto;

import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;

import java.util.List;

public class PickResponse {

    public record PickResult(
            MenuResponse.MenuDetail menu,
            List<RestaurantWithDistance> restaurants
    ) {}

    public record RestaurantWithDistance(
            Long id,
            String name,
            String address,
            Double distance
    ) {}
}
