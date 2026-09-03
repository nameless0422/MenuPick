package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
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
import java.time.Clock;
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
    /** 후보가 빈 이유를 가려낼 때만 쓴다 — {@link #diagnoseEmpty}. */
    private final MenuRestaurantRepository menuRestaurantRepository;
    /** 추천 시각을 KST 기준으로 기록한다 — 히스토리 days 필터와 기준 시간대를 맞춘다. */
    private final Clock clock;

    @Transactional
    public PickResponse.PickResult pick(Long userId, PickRequest request) {
        // 저장 경로(MenuService.normalizeCategories)가 저장 직전에 trim하므로, 요청 쪽도 같은
        // 모양으로 맞춰야 비교가 성립한다. 맞추지 않으면 [" 한식"]이 @NotBlank를 통과하고도
        // 저장된 "한식"과 매칭되지 않아 NO_PICK_CANDIDATES가 나고, 사용자는 분명히 있는
        // 메뉴를 두고 "조건에 맞는 메뉴가 없다"는 답을 받는다. 히스토리에도 " 한식"이 남는다.
        Set<String> categories = normalizeCategories(request == null ? null : request.categories());

        List<Menu> candidates;
        if (request == null) {
            candidates = menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(userId);
        } else {
            // 카테고리·태그·거리(바운딩 박스)를 전부 SQL로 내린다 — 근거는 PickCandidates.
            candidates = menuRepository.findAll(PickCandidates.of(
                    userId, categories, request.tagIds(), request.excludeTagIds(),
                    request.latitude(), request.longitude(), request.maxDistance()));
            // 박스는 반경을 감싸는 사각형일 뿐이라 모서리에 반경 밖 식당이 남는다.
            // 정밀 판정은 여기서 한 번 더 한다 — 결과 목록을 거르는 기준과 같은 함수다.
            candidates = filterByDistance(candidates,
                    request.latitude(), request.longitude(), request.maxDistance());
        }

        if (candidates.isEmpty()) {
            throw new BusinessException(diagnoseEmpty(userId, request));
        }

        Menu picked = weightedRandom(candidates);

        BigDecimal lat = request != null ? request.latitude() : null;
        BigDecimal lng = request != null ? request.longitude() : null;
        Integer maxDistance = request != null ? request.maxDistance() : null;

        List<PickResponse.RestaurantWithDistance> restaurants =
                buildRestaurantList(picked, lat, lng, maxDistance);

        History history = saveHistory(userId, picked, findNearestRestaurant(picked, lat, lng),
                request, categories);

        return new PickResponse.PickResult(history.getId(), toDetail(picked), restaurants);
    }

    /**
     * 후보가 왜 비었는지 가려낸다. <b>사용자가 해야 할 일이 다르기 때문에</b> 가른다.
     *
     * <p>전에는 셋을 모두 {@link ErrorCode#NO_PICK_CANDIDATES}로 뭉쳤고, 화면은 그걸 받아
     * "필터를 풀거나 메뉴를 추가해 보세요"라고 안내했다. 그런데 기본 메뉴 22개를 받고 시작한
     * 신규 사용자가 거리 필터를 켜면 정확히 이 에러가 나는데, 그에게 메뉴를 더 추가하라는 것은
     * <b>틀린 조언</b>이다 — 메뉴는 넘치고 없는 것은 식당 연결이다. 반경을 늘리라는 조언도
     * 마찬가지로 소용이 없다. 연결이 0건이면 반경이 지구 반 바퀴여도 후보는 비어 있다.
     *
     * <p>후보가 빈 경로에서만 도는 추가 조회다. 정상 픽에는 한 건도 붙지 않는다.
     */
    private ErrorCode diagnoseEmpty(Long userId, PickRequest request) {
        // 1. 필터를 다 풀어도 뽑을 것이 없다 — 메뉴가 없거나 전부 추천 제외다.
        if (!menuRepository.existsByUserIdAndIsExcludedFalseAndDeletedAtIsNull(userId)) {
            return ErrorCode.NO_PICKABLE_MENUS;
        }
        // 2. 거리로 걸렀는데 애초에 식당이 연결된 메뉴가 하나도 없다.
        if (distanceRequested(request)
                && !menuRestaurantRepository.existsLinkedRestaurantForUser(userId)) {
            return ErrorCode.NO_LINKED_RESTAURANTS;
        }
        // 3. 그 밖 — 조건이 좁다. 거리를 켰지만 연결은 있는 경우(전부 반경 밖)도 여기다.
        return ErrorCode.NO_PICK_CANDIDATES;
    }

    /**
     * 셋 중 하나라도 없으면 거리 필터를 걸지 않은 요청이다 —
     * {@link #filterByDistance}·{@link #withinDistance}가 쓰는 것과 같은 판정이어야 한다.
     */
    private static boolean distanceRequested(PickRequest request) {
        return request != null
                && request.latitude() != null
                && request.longitude() != null
                && request.maxDistance() != null;
    }

    /** 앞뒤 공백만 다른 값이 다른 카테고리로 취급되지 않도록 저장 경로와 같은 모양으로 맞춘다. */
    private static Set<String> normalizeCategories(Set<String> raw) {
        if (raw == null) return Set.of();
        return raw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(c -> !c.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 반경 안에 살아 있는 식당이 하나라도 있는 메뉴만 남긴다.
     *
     * <p>SQL의 바운딩 박스({@code PickCandidates})가 이미 대부분을 걸러 낸 뒤라 여기 들어오는
     * 건 사각형 안의 메뉴뿐이다. 그래도 이 단계가 필요하다 — 사각형의 모서리는 반경 밖이다.
     */
    private List<Menu> filterByDistance(List<Menu> menus, BigDecimal lat, BigDecimal lng, Integer maxDistance) {
        if (lat == null || lng == null || maxDistance == null) return menus;
        return menus.stream()
                .filter(m -> m.getMenuRestaurants().stream()
                        .map(MenuRestaurant::getRestaurant)
                        .anyMatch(r -> !r.isDeleted() && withinDistance(r, lat, lng, maxDistance)))
                .toList();
    }

    /**
     * 기준 좌표에서 {@code maxDistance} 이내인가.
     *
     * <p>후보 메뉴를 거를 때({@link #filterByDistance})와 결과 목록을 만들 때
     * ({@link #buildRestaurantList})가 <b>같은 판정</b>을 써야 한다. 둘이 갈라지면
     * "1km 이내"로 요청한 결과에 325km 떨어진 식당이 섞여 나온다 — 메뉴는 가까운 식당
     * 하나 덕분에 후보로 남는데(anyMatch) 목록은 연결된 식당을 전부 담았기 때문이다.
     *
     * <p>셋 중 하나라도 없으면 거리 필터를 걸지 않은 요청이므로 전부 통과시킨다.
     */
    private static boolean withinDistance(
            Restaurant restaurant, BigDecimal lat, BigDecimal lng, Integer maxDistance) {
        if (lat == null || lng == null || maxDistance == null) return true;
        double distance = calculateHaversineDistance(
                lat.doubleValue(), lng.doubleValue(),
                restaurant.getLatitude().doubleValue(), restaurant.getLongitude().doubleValue());
        return distance <= maxDistance;
    }

    private Menu weightedRandom(List<Menu> menus) {
        int totalWeight = menus.stream().mapToInt(Menu::getWeight).sum();

        // weight는 1~5로 검증되지만, 과거 데이터·직접 DB 수정 등으로 합이 0 이하가 되면
        // nextInt(bound)가 IllegalArgumentException을 던진다. 균등 랜덤으로 폴백한다.
        if (totalWeight <= 0) {
            return menus.get(ThreadLocalRandom.current().nextInt(menus.size()));
        }

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

    /**
     * 픽 결과에 실을 식당 목록.
     *
     * <p>거리 필터를 건 요청이면 <b>후보 판정과 같은 기준으로</b> 목록도 거른다.
     * 이 목록이 비는 일은 없다 — 메뉴가 후보로 남았다는 것 자체가 반경 안에 식당이
     * 최소 하나 있다는 뜻이다({@link #filterByDistance}의 anyMatch).
     */
    private List<PickResponse.RestaurantWithDistance> buildRestaurantList(
            Menu menu, BigDecimal lat, BigDecimal lng, Integer maxDistance) {
        return menu.getMenuRestaurants().stream()
                .filter(mr -> !mr.getRestaurant().isDeleted())
                .filter(mr -> withinDistance(mr.getRestaurant(), lat, lng, maxDistance))
                .map(mr -> {
                    Restaurant r = mr.getRestaurant();
                    Double distance = (lat != null && lng != null)
                            ? calculateHaversineDistance(
                                    lat.doubleValue(), lng.doubleValue(),
                                    r.getLatitude().doubleValue(), r.getLongitude().doubleValue())
                            : null;
                    return new PickResponse.RestaurantWithDistance(
                            r.getId(), r.getName(), r.getAddress(),
                            r.getLatitude(), r.getLongitude(), distance);
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

    /**
     * 픽 결과를 히스토리로 남긴다.
     *
     * <p>필터 조건에는 카테고리·태그·최대거리만 기록하고 <b>기준 좌표(latitude/longitude)는
     * 의도적으로 기록하지 않는다</b> — 위치정보 최소 수집 원칙(docs/PrivacyReview.md).
     * 좌표는 픽 시점의 후보 필터링에만 쓰이고 저장되지 않는다.
     */
    private History saveHistory(Long userId, Menu picked, Restaurant restaurant,
                                PickRequest request, Set<String> categories) {
        History history = History.builder()
                .user(userRepository.getReferenceById(userId))
                .menu(picked)
                .restaurant(restaurant)
                .recommendedAt(LocalDateTime.now(clock))
                .build();

        if (request != null) {
            // 원본이 아니라 정규화된 값을 남긴다. 원본을 남기면 히스토리에는 " 한식"이 보이는데
            // 실제로 걸린 필터는 "한식"이라, 나중에 그 기록을 보고 같은 조건을 재현할 수 없다.
            categories.forEach(cat -> history.addFilterCondition("CATEGORY", cat));
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
                // open-in-view=false라 직렬화는 트랜잭션 밖에서 일어난다. LAZY 컬렉션을 그대로
                // 넘기면 필터 없는 픽(카테고리를 한 번도 건드리지 않는 경로)에서 세션이 닫힌 뒤
                // 초기화를 시도해 LazyInitializationException으로 500이 난다.
                Set.copyOf(menu.getCategories()), tags,
                menu.getCreatedAt(), menu.getUpdatedAt(), menu.getVersion());
    }
}
