package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantResponse;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final UserRepository userRepository;
    private final MenuRestaurantRepository menuRestaurantRepository;
    private final Clock clock;

    public List<RestaurantResponse.RestaurantSummary> getRestaurants(Long userId) {
        return restaurantRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .map(r -> new RestaurantResponse.RestaurantSummary(r.getId(), r.getName(), r.getAddress()))
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
    @Transactional
    public CreateResult createRestaurant(Long userId, RestaurantRequest.Create request) {
        Restaurant existing = findSamePlace(userId, request.kakaoPlaceId());

        if (existing != null) {
            if (!existing.isDeleted()) {
                return new CreateResult(toDetail(existing), false);
            }
            // 지웠던 식당을 다시 저장하는 경우. 새 행을 넣으면 유니크 제약에 걸리므로 되살린다.
            existing.restore(request.name(), request.address(), request.phone(),
                    request.latitude(), request.longitude(), request.naverUrl());
            return new CreateResult(toDetail(existing), true);
        }

        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .user(userRepository.getReferenceById(userId))
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .naverUrl(request.naverUrl())
                .kakaoPlaceId(request.kakaoPlaceId())
                .build());

        return new CreateResult(toDetail(restaurant), true);
    }

    /** 저장 결과. {@code created=false}면 이미 갖고 있던 식당을 그대로 돌려준 것이다. */
    public record CreateResult(RestaurantResponse.RestaurantDetail restaurant, boolean created) {}

    private Restaurant findSamePlace(Long userId, String kakaoPlaceId) {
        if (kakaoPlaceId == null || kakaoPlaceId.isBlank()) {
            return null;
        }
        return restaurantRepository.findByUserIdAndKakaoPlaceId(userId, kakaoPlaceId).orElse(null);
    }

    @Transactional
    public RestaurantResponse.RestaurantDetail updateRestaurant(Long userId, Long restaurantId,
                                                                 RestaurantRequest.Update request) {
        Restaurant restaurant = findRestaurantOrThrow(userId, restaurantId);

        restaurant.update(request.name(), request.address(), request.phone(),
                request.latitude(), request.longitude(), request.naverUrl());

        return toDetail(restaurant);
    }

    @Transactional
    public void deleteRestaurant(Long userId, Long restaurantId) {
        Restaurant restaurant = findRestaurantOrThrow(userId, restaurantId);

        // 식당은 soft-delete지만 메뉴-식당 링크는 남겨둘 이유가 없다. 남겨두면 링크 조회/수정
        // 경로마다 isDeleted() 필터에 의존해야 하고, 같은 식당을 다시 등록할 때 유니크 제약과
        // 충돌한다. 여기서 일괄 정리한다.
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

    private RestaurantResponse.RestaurantDetail toDetail(Restaurant restaurant) {
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
                restaurant.getUpdatedAt());
    }
}
