package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 식당 저장 한 번의 DB 작업. 재시도 판단은 이 메서드의 트랜잭션 경계 밖에서 한다. */
@Service
@RequiredArgsConstructor
public class RestaurantCreation {
    private final RestaurantRepository restaurantRepository;
    private final UserRepository userRepository;

    @Transactional
    public RestaurantService.CreateResult create(Long userId, RestaurantRequest.Create request) {
        // 트랜잭션의 첫 조회로 잠근다. 먼저 읽으면 스냅샷이 고정돼 잠근 뒤에도 옛 개수를 본다.
        userRepository.findByIdForUpdate(userId);
        Restaurant existing = null;
        if (request.kakaoPlaceId() != null && !request.kakaoPlaceId().isBlank()) {
            existing = restaurantRepository.findByUserIdAndKakaoPlaceId(userId, request.kakaoPlaceId()).orElse(null);
        }
        if (existing != null) {
            if (!existing.isDeleted()) {
                return new RestaurantService.CreateResult(RestaurantService.toDetail(existing), false);
            }
            existing.restore(request.name(), request.address(), request.phone(),
                    request.latitude(), request.longitude(), request.naverUrl());
            // 복원 응답에도 저장 후 버전을 담아 바로 수정할 수 있게 한다.
            restaurantRepository.flush();
            return new RestaurantService.CreateResult(RestaurantService.toDetail(existing), true);
        }
        if (restaurantRepository.countByUserId(userId) >= Restaurant.MAX_PER_USER) {
            throw new BusinessException(ErrorCode.RESTAURANT_LIMIT_EXCEEDED);
        }
        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .user(userRepository.getReferenceById(userId))
                .name(request.name()).address(request.address()).phone(request.phone())
                .latitude(request.latitude()).longitude(request.longitude())
                .naverUrl(request.naverUrl()).kakaoPlaceId(request.kakaoPlaceId()).build());
        return new RestaurantService.CreateResult(RestaurantService.toDetail(restaurant), true);
    }
}
