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

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final UserRepository userRepository;
    private final MenuRestaurantRepository menuRestaurantRepository;

    public List<RestaurantResponse.RestaurantSummary> getRestaurants(Long userId) {
        return restaurantRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .map(r -> new RestaurantResponse.RestaurantSummary(r.getId(), r.getName(), r.getAddress()))
                .toList();
    }

    public RestaurantResponse.RestaurantDetail getRestaurant(Long userId, Long restaurantId) {
        return toDetail(findRestaurantOrThrow(userId, restaurantId));
    }

    @Transactional
    public RestaurantResponse.RestaurantDetail createRestaurant(Long userId, RestaurantRequest.Create request) {
        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .user(userRepository.getReferenceById(userId))
                .name(request.name())
                .address(request.address())
                .phone(request.phone())
                .latitude(request.latitude())
                .longitude(request.longitude())
                .naverUrl(request.naverUrl())
                .naverPlaceId(request.naverPlaceId())
                .build());

        return toDetail(restaurant);
    }

    @Transactional
    public RestaurantResponse.RestaurantDetail updateRestaurant(Long userId, Long restaurantId,
                                                                 RestaurantRequest.Update request) {
        Restaurant restaurant = findRestaurantOrThrow(userId, restaurantId);

        restaurant.update(request.name(), request.address(), request.phone(),
                request.latitude(), request.longitude(),
                request.naverUrl(), request.naverPlaceId());

        return toDetail(restaurant);
    }

    @Transactional
    public void deleteRestaurant(Long userId, Long restaurantId) {
        Restaurant restaurant = findRestaurantOrThrow(userId, restaurantId);

        // 식당은 soft-delete지만 메뉴-식당 링크는 남겨둘 이유가 없다. 남겨두면 링크 조회/수정
        // 경로마다 isDeleted() 필터에 의존해야 하고, 같은 식당을 다시 등록할 때 유니크 제약과
        // 충돌한다. 여기서 일괄 정리한다.
        menuRestaurantRepository.deleteByRestaurantId(restaurantId);

        restaurant.softDelete();
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
                restaurant.getNaverPlaceId(),
                restaurant.getCreatedAt(),
                restaurant.getUpdatedAt());
    }
}
