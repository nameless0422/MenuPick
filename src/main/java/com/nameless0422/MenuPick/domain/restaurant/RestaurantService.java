package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
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

    public List<RestaurantResponse.RestaurantSummary> getRestaurants(Long userId) {
        return restaurantRepository.findAllByUserIdAndDeletedAtIsNull(userId).stream()
                .map(r -> new RestaurantResponse.RestaurantSummary(r.getId(), r.getName(), r.getAddress()))
                .toList();
    }

    public RestaurantResponse.RestaurantDetail getRestaurant(Long userId, Long restaurantId) {
        Restaurant restaurant = findRestaurantOrThrow(restaurantId);
        verifyOwnership(restaurant, userId);
        return toDetail(restaurant);
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
        Restaurant restaurant = findRestaurantOrThrow(restaurantId);
        verifyOwnership(restaurant, userId);

        restaurant.update(request.name(), request.address(), request.phone(),
                request.latitude(), request.longitude(),
                request.naverUrl(), request.naverPlaceId());

        return toDetail(restaurant);
    }

    @Transactional
    public void deleteRestaurant(Long userId, Long restaurantId) {
        Restaurant restaurant = findRestaurantOrThrow(restaurantId);
        verifyOwnership(restaurant, userId);
        restaurant.softDelete();
    }

    private Restaurant findRestaurantOrThrow(Long restaurantId) {
        Restaurant restaurant = restaurantRepository.findById(restaurantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));
        if (restaurant.isDeleted()) {
            throw new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND);
        }
        return restaurant;
    }

    private void verifyOwnership(Restaurant restaurant, Long userId) {
        if (!restaurant.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESTAURANT_ACCESS_DENIED);
        }
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
