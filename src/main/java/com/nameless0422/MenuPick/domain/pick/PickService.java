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
import com.nameless0422.MenuPick.domain.tag.TagRepository;
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
    private final TagRepository tagRepository;

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

        BigDecimal lat = request != null ? request.latitude() : null;
        BigDecimal lng = request != null ? request.longitude() : null;

        List<PickResponse.RestaurantWithDistance> restaurants = buildRestaurantList(picked, lat, lng);

        History history = saveHistory(userId, picked, findNearestRestaurant(picked, lat, lng), request);

        return new PickResponse.PickResult(history.getId(), toDetail(picked), restaurants);
    }

    private List<Menu> filterByCategories(List<Menu> menus, Set<String> categories) {
        if (categories == null || categories.isEmpty()) return menus;
        return menus.stream()
                .filter(m -> !Collections.disjoint(m.getCategories(), categories))
                .toList();
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
                    .toList();
        }

        if (excludeTagIds != null && !excludeTagIds.isEmpty()) {
            result = result.stream()
                    .filter(m -> {
                        Set<Long> menuTagIds = m.getTags().stream()
                                .map(Tag::getId).collect(Collectors.toSet());
                        return Collections.disjoint(menuTagIds, excludeTagIds);
                    })
                    .toList();
        }

        return result;
    }

    private List<Menu> filterByDistance(List<Menu> menus, BigDecimal lat, BigDecimal lng, Integer maxDistance) {
        if (lat == null || lng == null || maxDistance == null) return menus;
        return menus.stream()
                .filter(m -> m.getMenuRestaurants().stream()
                        .anyMatch(mr -> {
                            Restaurant r = mr.getRestaurant();
                            if (r.isDeleted()) return false;
                            double dist = calculateHaversineDistance(
                                    lat.doubleValue(), lng.doubleValue(),
                                    r.getLatitude().doubleValue(), r.getLongitude().doubleValue());
                            return dist <= maxDistance;
                        }))
                .toList();
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
                .filter(mr -> !mr.getRestaurant().isDeleted())
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

    /**
     * 픽 시점의 대표 식당: 위치가 있으면 최근접 식당, 없으면 연결 식당이
     * 하나뿐일 때만 그 식당을 기록한다 (여러 개면 임의 기록 대신 미기록).
     * 실제 방문 식당은 방문 처리 시 덮어쓸 수 있다.
     */
    private Restaurant findNearestRestaurant(Menu menu, BigDecimal lat, BigDecimal lng) {
        List<Restaurant> active = menu.getMenuRestaurants().stream()
                .map(MenuRestaurant::getRestaurant)
                .filter(r -> !r.isDeleted())
                .toList();

        if (active.isEmpty()) {
            return null;
        }
        if (lat == null || lng == null) {
            return active.size() == 1 ? active.get(0) : null;
        }
        return active.stream()
                .min(Comparator.comparingDouble(r -> calculateHaversineDistance(
                        lat.doubleValue(), lng.doubleValue(),
                        r.getLatitude().doubleValue(), r.getLongitude().doubleValue())))
                .orElse(null);
    }

    private History saveHistory(Long userId, Menu picked, Restaurant restaurant, PickRequest request) {
        History history = History.builder()
                .user(userRepository.getReferenceById(userId))
                .menu(picked)
                .restaurant(restaurant)
                .recommendedAt(LocalDateTime.now())
                .build();

        if (request != null) {
            if (request.categories() != null) {
                request.categories().forEach(cat ->
                        history.addFilterCondition("CATEGORY", cat));
            }
            Map<Long, String> tagNames = resolveTagNames(userId, request.tagIds(), request.excludeTagIds());
            if (request.tagIds() != null) {
                request.tagIds().forEach(tagId ->
                        history.addFilterCondition("TAG_INCLUDE", tagNames.getOrDefault(tagId, String.valueOf(tagId))));
            }
            if (request.excludeTagIds() != null) {
                request.excludeTagIds().forEach(tagId ->
                        history.addFilterCondition("TAG_EXCLUDE", tagNames.getOrDefault(tagId, String.valueOf(tagId))));
            }
            if (request.maxDistance() != null) {
                history.addFilterCondition("MAX_DISTANCE", String.valueOf(request.maxDistance()));
            }
        }

        return historyRepository.save(history);
    }

    /**
     * 히스토리 필터 조건에는 태그 ID 대신 태그 이름을 남긴다 — 태그 ID를 이름으로 되돌리는
     * 조회 API가 없어 화면에서 표시할 수 없기 때문. 본인 소유가 아니거나 존재하지 않는
     * ID는 이름 확인 없이 원값(ID 문자열)을 그대로 남긴다.
     */
    private Map<Long, String> resolveTagNames(Long userId, Set<Long> includeTagIds, Set<Long> excludeTagIds) {
        Set<Long> allIds = new HashSet<>();
        if (includeTagIds != null) allIds.addAll(includeTagIds);
        if (excludeTagIds != null) allIds.addAll(excludeTagIds);
        if (allIds.isEmpty()) return Map.of();
        return tagRepository.findAllByIdInAndUserId(allIds, userId).stream()
                .collect(Collectors.toMap(Tag::getId, Tag::getName));
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
