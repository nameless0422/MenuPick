package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PickService {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final MenuRepository menuRepository;
    private final HistoryRepository historyRepository;
    private final UserRepository userRepository;

    @Transactional
    public PickResponse.PickResult pick(Long userId, PickRequest request) {
        List<Menu> candidates = menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(userId);

        if (request != null) {
            candidates = filterByCategories(candidates, request.categories());
            candidates = filterByTags(candidates, request.tagIds(), request.excludeTagIds());
            candidates = filterByDistance(candidates, request.latitude(), request.longitude(), request.maxDistance());
        }

        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.NO_PICK_CANDIDATES);
        }

        Menu picked = weightedRandom(candidates);

        List<PickResponse.RestaurantWithDistance> restaurants = buildRestaurantList(
                picked, request != null ? request.latitude() : null,
                request != null ? request.longitude() : null);

        History history = saveHistory(userId, picked, request);

        return new PickResponse.PickResult(toDetail(picked), restaurants);
    }

    private List<Menu> filterByCategories(List<Menu> menus, Set<String> categories) {
        if (categories == null || categories.isEmpty()) return menus;
        return menus.stream()
                .filter(m -> !Collections.disjoint(m.getCategories(), categories))
                .collect(Collectors.toList());
    }

    private List<Menu> filterByTags(List<Menu> menus, Set<Long> includeTagIds, Set<Long> excludeTagIds) {
        List<Menu> result = menus;

        if (includeTagIds != null && !includeTagIds.isEmpty()) {
            result = result.stream()
                    .filter(m -> {
                        Set<Long> menuTagIds = m.getTags().stream()
                                .map(Tag::getId).collect(Collectors.toSet());
                        return menuTagIds.containsAll(includeTagIds);
                    })
                    .collect(Collectors.toList());
        }

        if (excludeTagIds != null && !excludeTagIds.isEmpty()) {
            result = result.stream()
                    .filter(m -> {
                        Set<Long> menuTagIds = m.getTags().stream()
                                .map(Tag::getId).collect(Collectors.toSet());
                        return Collections.disjoint(menuTagIds, excludeTagIds);
                    })
                    .collect(Collectors.toList());
        }

        return result;
    }

    private List<Menu> filterByDistance(List<Menu> menus, BigDecimal lat, BigDecimal lng, Integer maxDistance) {
        if (lat == null || lng == null || maxDistance == null) return menus;
        return menus.stream()
                .filter(m -> m.getMenuRestaurants().stream()
                        .anyMatch(mr -> {
                            Restaurant r = mr.getRestaurant();
                            double dist = calculateHaversineDistance(
                                    lat.doubleValue(), lng.doubleValue(),
                                    r.getLatitude().doubleValue(), r.getLongitude().doubleValue());
                            return dist <= maxDistance;
                        }))
                .collect(Collectors.toList());
    }

    private Menu weightedRandom(List<Menu> menus) {
        int totalWeight = menus.stream().mapToInt(Menu::getWeight).sum();
        int random = ThreadLocalRandom.current().nextInt(totalWeight);
        int cumulative = 0;
        for (Menu menu : menus) {
            cumulative += menu.getWeight();
            if (random < cumulative) {
                return menu;
            }
        }
        return menus.get(menus.size() - 1);
    }

    private List<PickResponse.RestaurantWithDistance> buildRestaurantList(
            Menu menu, BigDecimal lat, BigDecimal lng) {
        return menu.getMenuRestaurants().stream()
                .map(mr -> {
                    Restaurant r = mr.getRestaurant();
                    Double distance = (lat != null && lng != null)
                            ? calculateHaversineDistance(
                                    lat.doubleValue(), lng.doubleValue(),
                                    r.getLatitude().doubleValue(), r.getLongitude().doubleValue())
                            : null;
                    return new PickResponse.RestaurantWithDistance(
                            r.getId(), r.getName(), r.getAddress(), distance);
                })
                .sorted(Comparator.comparing(PickResponse.RestaurantWithDistance::distance,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    static double calculateHaversineDistance(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }

    private History saveHistory(Long userId, Menu picked, PickRequest request) {
        History history = History.builder()
                .user(userRepository.getReferenceById(userId))
                .menu(picked)
                .recommendedAt(LocalDateTime.now())
                .build();

        if (request != null) {
            if (request.categories() != null) {
                request.categories().forEach(cat ->
                        history.addFilterCondition("CATEGORY", cat));
            }
            if (request.tagIds() != null) {
                request.tagIds().forEach(tagId ->
                        history.addFilterCondition("TAG_INCLUDE", String.valueOf(tagId)));
            }
            if (request.excludeTagIds() != null) {
                request.excludeTagIds().forEach(tagId ->
                        history.addFilterCondition("TAG_EXCLUDE", String.valueOf(tagId)));
            }
            if (request.maxDistance() != null) {
                history.addFilterCondition("MAX_DISTANCE", String.valueOf(request.maxDistance()));
            }
        }

        return historyRepository.save(history);
    }

    private MenuResponse.MenuDetail toDetail(Menu menu) {
        List<MenuResponse.TagSummary> tags = menu.getTags().stream()
                .map(t -> new MenuResponse.TagSummary(t.getId(), t.getName()))
                .toList();
        return new MenuResponse.MenuDetail(
                menu.getId(), menu.getName(), menu.getMemo(),
                menu.getWeight(), menu.isExcluded(),
                menu.getCategories(), tags,
                menu.getCreatedAt(), menu.getUpdatedAt());
    }
}
