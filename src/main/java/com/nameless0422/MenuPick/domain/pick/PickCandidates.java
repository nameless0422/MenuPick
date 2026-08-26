package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.tag.Tag;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 픽 후보를 고르는 조건을 SQL로 내린다.
 *
 * <p>예전에는 사용자의 메뉴를 <b>전부</b> 메모리로 올린 뒤 자바 스트림으로 걸렀다. 조건이
 * 하나도 없는 픽에서는 그게 곧 전량 조회였고, 카테고리 하나만 고른 픽에서도 마찬가지였다 —
 * 걸러 낼 행까지 실어 온 다음 {@code categories}·{@code tags}·{@code menuRestaurants} 세
 * 컬렉션을 메뉴 수만큼 초기화하고 나서야 버렸다. 조인에 쓸 인덱스는 이미 있었다
 * ({@code menu_categories} PK, {@code menu_tags} PK, 그리고 {@code idx_restaurants_location} —
 * V6가 "이 과제가 쓸 것"이라며 드롭하지 않고 남겨 둔 바로 그 인덱스다).
 *
 * <p><b>거리만 두 단계로 처리한다.</b> Haversine을 SQL로 내리면 인덱스를 쓸 수 없는 전 행
 * 계산이 되므로, 여기서는 좌표 바운딩 박스로 1차 축소만 하고 정밀 판정은 자바에 남긴다
 * ({@code PickService.filterByDistance}). 그래서 이 박스는 <b>절대로 좁으면 안 된다</b> —
 * 반경 안의 식당을 여기서 한 번 떨어뜨리면 자바가 되살릴 방법이 없다. 아래 상수들이 전부
 * "넉넉한 쪽"으로 잡혀 있는 이유가 그것이다.
 */
final class PickCandidates {

    /**
     * 위도 1도의 길이(m). 실제로는 극에서 111.69km, 적도에서 110.57km로 변한다.
     * 박스가 좁아지면 안 되므로 <b>가장 짧은 값</b>을 쓴다 — 나누는 값이 작을수록 박스가 커진다.
     */
    private static final double METERS_PER_LATITUDE_DEGREE = 110_574.0;

    /** 적도에서 경도 1도의 길이(m). 위도가 올라가면 {@code cos(위도)}배로 짧아진다. */
    private static final double METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR = 111_320.0;

    /**
     * 박스에 얹는 여유. 지구를 구로 놓고 계산한 값과 실제 타원체 사이의 오차, 그리고
     * {@code DECIMAL(10,7)} 반올림을 한꺼번에 덮는다. 넓혀서 치르는 비용은 자바가 다시
     * 떨어뜨릴 행 몇 개뿐이고, 좁혀서 치르는 비용은 "가까운데 결과에 안 나오는 식당"이다.
     */
    private static final double BOX_MARGIN = 1.01;

    private PickCandidates() {
    }

    /**
     * @param categories    정규화(trim)된 카테고리. 하나라도 가진 메뉴를 남긴다(OR).
     * @param includeTagIds <b>전부</b> 가진 메뉴만 남긴다(AND).
     * @param excludeTagIds 하나라도 가진 메뉴를 뺀다.
     */
    static Specification<Menu> of(Long userId,
                                  Set<String> categories,
                                  Set<Long> includeTagIds,
                                  Set<Long> excludeTagIds,
                                  BigDecimal latitude,
                                  BigDecimal longitude,
                                  Integer maxDistance) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("user").get("id"), userId));
            predicates.add(cb.isFalse(root.get("isExcluded")));
            predicates.add(cb.isNull(root.get("deletedAt")));

            if (categories != null && !categories.isEmpty()) {
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<Menu> subMenu = sub.from(Menu.class);
                Join<Menu, String> joined = subMenu.join("categories");
                sub.select(cb.literal(1))
                        .where(cb.equal(subMenu, root), joined.in(categories));
                predicates.add(cb.exists(sub));
            }

            Set<Long> includes = includeTagIds == null ? Set.of() : includeTagIds;
            if (!includes.isEmpty()) {
                // contains(null)로 묻지 않는다 — Set.of()가 만든 불변 집합은 null 조회
                // 자체를 NPE로 거절한다. 컨트롤러가 넘기는 집합의 구현을 여기서 가정할 수 없다.
                if (includes.stream().anyMatch(Objects::isNull)) {
                    // null인 태그 id는 어떤 메뉴도 가질 수 없다. "요청한 태그를 전부 가진
                    // 메뉴"를 요구하는 조건이므로 그런 값이 하나라도 섞이면 결과는 언제나
                    // 공집합이다 — 자바로 거르던 시절의 containsAll도 같은 답을 냈다.
                    predicates.add(cb.disjunction());
                } else {
                    Subquery<Long> sub = query.subquery(Long.class);
                    Root<Menu> subMenu = sub.from(Menu.class);
                    Join<Menu, Tag> joined = subMenu.join("tags");
                    sub.select(cb.countDistinct(joined.get("id")))
                            .where(cb.equal(subMenu, root), joined.get("id").in(includes));
                    // 요청한 것 중 몇 개를 가졌는지 세어 요청 개수와 맞춘다 — 이게 곧 AND다.
                    predicates.add(cb.equal(sub, (long) includes.size()));
                }
            }

            Set<Long> excludes = withoutNulls(excludeTagIds);
            if (!excludes.isEmpty()) {
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<Menu> subMenu = sub.from(Menu.class);
                Join<Menu, Tag> joined = subMenu.join("tags");
                sub.select(cb.literal(1))
                        .where(cb.equal(subMenu, root), joined.get("id").in(excludes));
                predicates.add(cb.not(cb.exists(sub)));
            }

            BoundingBox box = BoundingBox.around(latitude, longitude, maxDistance);
            if (box != null) {
                Subquery<Integer> sub = query.subquery(Integer.class);
                Root<MenuRestaurant> link = sub.from(MenuRestaurant.class);
                Join<MenuRestaurant, Restaurant> restaurant = link.join("restaurant");

                List<Predicate> inBox = new ArrayList<>();
                inBox.add(cb.equal(link.get("menu"), root));
                // 지운 식당은 후보 근거가 되지 못한다 — 결과 목록에서도 빠지므로,
                // 이 조건이 없으면 "식당이 하나도 없는 픽 결과"가 나온다.
                inBox.add(cb.isNull(restaurant.get("deletedAt")));
                inBox.add(cb.between(restaurant.<BigDecimal>get("latitude"), box.minLat(), box.maxLat()));
                if (box.boundsLongitude()) {
                    inBox.add(cb.between(restaurant.<BigDecimal>get("longitude"), box.minLng(), box.maxLng()));
                }
                sub.select(cb.literal(1)).where(cb.and(inBox.toArray(Predicate[]::new)));
                predicates.add(cb.exists(sub));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /**
     * 제외 태그에서 null을 걷어낸다. 제외는 "가진 것을 뺀다"는 조건이라 가질 수 없는 값은
     * 아무 일도 하지 않는다 — 자바로 거르던 시절의 {@code disjoint}도 그냥 통과시켰다.
     * 걷어내지 않고 그대로 IN에 넣으면 드라이버가 파라미터 바인딩에서 걸린다.
     */
    private static Set<Long> withoutNulls(Set<Long> ids) {
        if (ids == null) {
            return Set.of();
        }
        Set<Long> result = new LinkedHashSet<>(ids);
        result.remove(null);
        return result;
    }

    /**
     * 반경을 감싸는 위경도 사각형. 정밀 판정 전의 1차 축소용이라 <b>반경보다 넓기만 하면</b> 된다.
     *
     * @param boundsLongitude 경도까지 조건으로 걸 수 있는지. 박스가 날짜변경선을 넘거나 극
     *                        근처라 경도 폭이 한 바퀴에 가까워지면 조건을 걸지 않는다 —
     *                        그때 억지로 {@code between}을 쓰면 범위가 뒤집혀 반경 안의 식당까지
     *                        전부 떨어진다. 위도만으로 줄이고 나머지는 자바가 판정한다.
     */
    private record BoundingBox(BigDecimal minLat, BigDecimal maxLat,
                               BigDecimal minLng, BigDecimal maxLng,
                               boolean boundsLongitude) {

        static BoundingBox around(BigDecimal latitude, BigDecimal longitude, Integer maxDistance) {
            if (latitude == null || longitude == null || maxDistance == null) {
                return null;
            }

            double lat = latitude.doubleValue();
            double lng = longitude.doubleValue();
            double radius = maxDistance * BOX_MARGIN;

            double latDelta = radius / METERS_PER_LATITUDE_DEGREE;

            // 경도 1도의 실제 길이는 위도가 높을수록 짧아지고, 그만큼 같은 거리를 담는 데
            // 필요한 경도 폭은 넓어진다. 박스 안에서 적도로부터 가장 먼 위도를 기준으로
            // 잡아야 박스가 좁아지지 않는다.
            double worstLatitude = Math.min(Math.abs(lat) + latDelta, 90.0);
            double cos = Math.cos(Math.toRadians(worstLatitude));
            double lngDelta = cos <= 0 ? 360.0 : radius / (METERS_PER_LONGITUDE_DEGREE_AT_EQUATOR * cos);

            boolean boundsLongitude = lngDelta < 180.0
                    && lng - lngDelta >= -180.0
                    && lng + lngDelta <= 180.0;

            return new BoundingBox(
                    BigDecimal.valueOf(Math.max(lat - latDelta, -90.0)),
                    BigDecimal.valueOf(Math.min(lat + latDelta, 90.0)),
                    BigDecimal.valueOf(lng - lngDelta),
                    BigDecimal.valueOf(lng + lngDelta),
                    boundsLongitude);
        }
    }
}
