package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;

import java.math.BigDecimal;
import java.util.List;

final class PickDistance {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private PickDistance() {}

    static List<Menu> filter(List<Menu> menus, PickRequest request) {
        if (request == null || request.latitude() == null || request.longitude() == null
                || request.maxDistance() == null) return menus;
        return menus.stream().filter(menu -> menu.getMenuRestaurants().stream()
                .map(MenuRestaurant::getRestaurant)
                .anyMatch(restaurant -> !restaurant.isDeleted() && within(restaurant,
                        request.latitude(), request.longitude(), request.maxDistance()))).toList();
    }

    static boolean within(Restaurant restaurant, BigDecimal lat, BigDecimal lng, Integer maxDistance) {
        if (lat == null || lng == null || maxDistance == null) return true;
        return meters(lat.doubleValue(), lng.doubleValue(), restaurant.getLatitude().doubleValue(),
                restaurant.getLongitude().doubleValue()) <= maxDistance;
    }

    static double meters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
