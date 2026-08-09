package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuRestaurantService {

    private final MenuRepository menuRepository;
    private final RestaurantRepository restaurantRepository;
    private final MenuRestaurantRepository menuRestaurantRepository;

    public MenuRestaurantResponse.MenuRestaurantListResponse getMenuRestaurants(Long userId, Long menuId) {
        Menu menu = findMenuOrThrow(menuId);
        verifyMenuOwnership(menu, userId);

        List<MenuRestaurantResponse.MenuRestaurantDetail> details = menuRestaurantRepository.findAllByMenuId(menuId)
                .stream()
                .filter(mr -> !mr.getRestaurant().isDeleted())
                .map(this::toDetail)
                .toList();

        return new MenuRestaurantResponse.MenuRestaurantListResponse(details);
    }

    @Transactional
    public MenuRestaurantResponse.MenuRestaurantDetail createMenuRestaurant(
            Long userId, Long menuId, MenuRestaurantRequest.Create request) {
        Menu menu = findMenuOrThrow(menuId);
        verifyMenuOwnership(menu, userId);

        Restaurant restaurant = restaurantRepository.findById(request.restaurantId())
                .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));
        if (restaurant.isDeleted()) {
            throw new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND);
        }
        if (!restaurant.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.RESTAURANT_ACCESS_DENIED);
        }

        if (menuRestaurantRepository.existsByMenuIdAndRestaurantId(menuId, request.restaurantId())) {
            throw new BusinessException(ErrorCode.MENU_RESTAURANT_DUPLICATE);
        }

        MenuRestaurant menuRestaurant = menuRestaurantRepository.save(
                MenuRestaurant.builder()
                        .menu(menu)
                        .restaurant(restaurant)
                        .rating(request.rating())
                        .memo(request.memo())
                        .build());

        return toDetail(menuRestaurant);
    }

    @Transactional
    public MenuRestaurantResponse.MenuRestaurantDetail updateMenuRestaurant(
            Long userId, Long menuId, Long restaurantId, MenuRestaurantRequest.Update request) {
        Menu menu = findMenuOrThrow(menuId);
        verifyMenuOwnership(menu, userId);

        MenuRestaurant menuRestaurant = menuRestaurantRepository
                .findByMenuIdAndRestaurantId(menuId, restaurantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_RESTAURANT_NOT_FOUND));

        menuRestaurant.update(request.rating(), request.memo());
        return toDetail(menuRestaurant);
    }

    @Transactional
    public void deleteMenuRestaurant(Long userId, Long menuId, Long restaurantId) {
        Menu menu = findMenuOrThrow(menuId);
        verifyMenuOwnership(menu, userId);

        MenuRestaurant menuRestaurant = menuRestaurantRepository
                .findByMenuIdAndRestaurantId(menuId, restaurantId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_RESTAURANT_NOT_FOUND));

        menuRestaurantRepository.delete(menuRestaurant);
    }

    private Menu findMenuOrThrow(Long menuId) {
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
        if (menu.isDeleted()) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
        }
        return menu;
    }

    private void verifyMenuOwnership(Menu menu, Long userId) {
        if (!menu.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.MENU_ACCESS_DENIED);
        }
    }

    private MenuRestaurantResponse.MenuRestaurantDetail toDetail(MenuRestaurant mr) {
        Restaurant r = mr.getRestaurant();
        return new MenuRestaurantResponse.MenuRestaurantDetail(
                mr.getMenu().getId(),
                r.getId(),
                r.getName(),
                r.getAddress(),
                mr.getRating(),
                mr.getMemo(),
                mr.getCreatedAt(),
                mr.getUpdatedAt());
    }
}
