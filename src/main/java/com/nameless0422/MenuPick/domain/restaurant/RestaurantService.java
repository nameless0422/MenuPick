package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.domain.VersionGuard;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.springframework.data.domain.PageRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.time.format.DateTimeParseException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final RestaurantCreation restaurantCreation;
    private final MenuRestaurantRepository menuRestaurantRepository;
    private final Clock clock;

    public RestaurantResponse.DeletedRestaurantListResponse getDeletedRestaurants(Long userId, String cursor, int size) {
        TrashCursor anchor = decodeTrashCursor(cursor);
        List<Restaurant> restaurants = restaurantRepository.findDeletedRestaurants(userId,
                anchor == null ? null : anchor.deletedAt(), anchor == null ? null : anchor.id(),
                PageRequest.of(0, size + 1));
        boolean hasNext = restaurants.size() > size;
        List<Restaurant> result = hasNext ? restaurants.subList(0, size) : restaurants;
        String nextCursor = hasNext ? encodeTrashCursor(result.get(result.size() - 1)) : null;
        return new RestaurantResponse.DeletedRestaurantListResponse(result.stream()
                .map(r -> new RestaurantResponse.DeletedRestaurantSummary(
                        r.getId(), r.getName(), r.getAddress(), r.getDeletedAt(), r.getVersion()))
                .toList(), nextCursor, hasNext);
    }

    @Transactional
    public void restoreRestaurant(Long userId, Long restaurantId, RestaurantRequest.Restore request) {
        Restaurant restaurant = restaurantRepository.findByIdAndUserId(restaurantId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));
        if (!restaurant.isDeleted()) return;
        VersionGuard.requireCurrentVersion(restaurant.getVersion(), request.version());
        restaurant.restore();
    }

    private record TrashCursor(LocalDateTime deletedAt, long id) {}

    private static String encodeTrashCursor(Restaurant restaurant) {
        String value = restaurant.getDeletedAt() + "|" + restaurant.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static TrashCursor decodeTrashCursor(String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 100) throw new IllegalArgumentException();
            String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 2) throw new IllegalArgumentException();
            LocalDateTime deletedAt = LocalDateTime.parse(values[0]);
            long id = Long.parseLong(values[1]);
            if (id <= 0 || deletedAt.getYear() < 1000 || deletedAt.getYear() > 9999) throw new IllegalArgumentException();
            return new TrashCursor(deletedAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "휴지통 커서가 올바르지 않습니다.");
        }
    }

    public List<RestaurantResponse.RestaurantSummary> getRestaurants(Long userId) {
        return getRestaurants(userId, null);
    }

    public List<RestaurantResponse.RestaurantSummary> getRestaurants(Long userId, String keyword) {
        String search = keyword == null ? "" : keyword.trim();
        // 사용자가 입력한 %, _도 상호나 주소의 글자로 검색한다.
        String pattern = "%" + search.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        List<Restaurant> restaurants = search.isEmpty()
                ? restaurantRepository.findAllByUserIdAndDeletedAtIsNull(userId)
                : restaurantRepository.searchSavedRestaurants(userId, pattern);
        return restaurants.stream()
                .map(r -> new RestaurantResponse.RestaurantSummary(
                        r.getId(), r.getName(), r.getAddress(), r.getLatitude(), r.getLongitude()))
                .toList();
    }

    public RestaurantResponse.RestaurantDetail getRestaurant(Long userId, Long restaurantId) {
        return toDetail(findRestaurantOrThrow(userId, restaurantId));
    }

    /**
     * 식당을 저장한다. 같은 장소를 이미 저장해 뒀으면 새로 만들지 않고 그것을 돌려준다.
     *
     * <p>판정 기준은 카카오 장소 id뿐이다. 이름은 쓰지 않는다 — 상호가 같은 다른 가게가 실제로
     * 있어서, 이름으로 합치면 서로 다른 지점이 하나로 뭉개진다. id가 없는 요청(장소 검색을
     * 거치지 않은 저장)은 비교할 근거가 없으므로 그대로 새로 만든다.
     *
     * <p>살아 있는 식당을 찾으면 <b>덮어쓰지 않고 그대로</b> 돌려준다. 사용자가 이름·주소를
     * 고쳐 뒀을 수 있는데, 검색 결과를 다시 저장했다는 이유로 그 편집을 되돌리면 안 된다.
     *
     * @return 이미 있던 식당이면 {@code created=false}. 호출부가 201과 200을 구분하는 데 쓴다 —
     *         사용자에게 "새로 저장됐다"와 "이미 있다"는 다른 사실이다.
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public CreateResult createRestaurant(Long userId, RestaurantRequest.Create request) {
        // 픽 기록의 식당 선택은 저장·연결·이력 변경을 한 트랜잭션으로 묶는다.
        // 상위 트랜잭션 안에서는 재시도하지 않고 오류를 전달해 전체를 롤백한다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return restaurantCreation.create(userId, request);
        }
        try {
            return restaurantCreation.create(userId, request);
        } catch (DataIntegrityViolationException failure) {
            if (request.kakaoPlaceId() == null || request.kakaoPlaceId().isBlank() || !isPlaceDuplicate(failure)) {
                throw failure;
            }
            // 실패한 저장 트랜잭션은 이미 종료됐다. 새 세션으로 한 번만 다시 조회·저장한다.
            return restaurantCreation.create(userId, request);
        }
    }

    private static boolean isPlaceDuplicate(DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                String name = violation.getConstraintName();
                return name != null && (name.equals("uq_restaurants_user_place")
                        || name.endsWith(".uq_restaurants_user_place"));
            }
        }
        return false;
    }

    public record CreateResult(RestaurantResponse.RestaurantDetail restaurant, boolean created) {}

    @Transactional
    public RestaurantResponse.RestaurantDetail updateRestaurant(Long userId, Long restaurantId,
                                                                 RestaurantRequest.Update request) {
        Restaurant restaurant = findRestaurantOrThrow(userId, restaurantId);
        // 아무것도 고치기 전에 확인한다 — 근거는 VersionGuard.
        VersionGuard.requireCurrentVersion(restaurant.getVersion(), request.version());

        restaurant.update(request.name(), request.address(), request.phone(),
                request.latitude(), request.longitude(), request.naverUrl());

        // 버전은 flush 시점에 올라간다. 그 전에 DTO로 옮기면 응답에 **저장 전 버전**이 실려,
        // 화면은 방금 저장하고도 곧바로 오래된 값을 들게 된다 — 같은 폼에서 한 번 더 저장하면
        // 아무도 건드리지 않았는데 409가 난다. 그래서 매핑 전에 flush를 강제한다.
        restaurantRepository.flush();
        return toDetail(restaurant);
    }

    @Transactional
    public void deleteRestaurant(Long userId, Long restaurantId) {
        Restaurant restaurant = findRestaurantOrThrow(userId, restaurantId);

        // 기존 삭제 계약대로 메뉴 연결과 연결별 별점·메모를 물리 삭제한다.
        // 휴지통 복원은 식당 정보만 복구하며, 연결은 사용자가 다시 만든다.
        menuRestaurantRepository.deleteByRestaurantId(restaurantId);

        restaurant.softDelete(LocalDateTime.now(clock));
    }

    /**
     * 소유자 범위로 한정해 조회한다. 타인의 식당·삭제된 식당 모두 RESTAURANT_NOT_FOUND(404)로
     * 동일하게 응답해 리소스 존재 여부가 노출되지 않게 한다.
     */
    private Restaurant findRestaurantOrThrow(Long userId, Long restaurantId) {
        return restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(restaurantId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));
    }

    static RestaurantResponse.RestaurantDetail toDetail(Restaurant restaurant) {
        return new RestaurantResponse.RestaurantDetail(
                restaurant.getId(),
                restaurant.getName(),
                restaurant.getAddress(),
                restaurant.getPhone(),
                restaurant.getLatitude(),
                restaurant.getLongitude(),
                restaurant.getNaverUrl(),
                restaurant.getKakaoPlaceId(),
                restaurant.getCreatedAt(),
                restaurant.getUpdatedAt(),
                restaurant.getVersion());
    }
}
