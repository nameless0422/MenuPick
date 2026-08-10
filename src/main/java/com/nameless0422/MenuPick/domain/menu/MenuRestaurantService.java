package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
        findMenuOrThrow(userId, menuId);

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
        Menu menu = findMenuOrThrow(userId, menuId);

        // 타인의 식당·삭제된 식당 모두 RESTAURANT_NOT_FOUND(404)로 동일하게 응답한다.
        Restaurant restaurant = restaurantRepository
                .findByIdAndUserIdAndDeletedAtIsNull(request.restaurantId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));

        // 일반 경로에서 명확한 에러를 주기 위한 사전 검사. 동시 요청 레이스는 아래 catch가 받는다.
        if (menuRestaurantRepository.existsByMenuIdAndRestaurantId(menuId, request.restaurantId())) {
            throw new BusinessException(ErrorCode.MENU_RESTAURANT_DUPLICATE);
        }

        MenuRestaurant menuRestaurant;
        try {
            menuRestaurant = menuRestaurantRepository.save(
                    MenuRestaurant.builder()
                            .menu(menu)
                            .restaurant(restaurant)
                            .rating(request.rating())
                            .memo(request.memo())
                            .build());
        } catch (DataIntegrityViolationException e) {
            // uq_menu_restaurant(menu_id, restaurant_id) 위반 — check-then-act 사이에 끼어든 동시 생성
            throw new BusinessException(ErrorCode.MENU_RESTAURANT_DUPLICATE);
        }

        return toDetail(menuRestaurant);
    }

    @Transactional
    public MenuRestaurantResponse.MenuRestaurantDetail updateMenuRestaurant(
            Long userId, Long menuId, Long restaurantId, MenuRestaurantRequest.Update request) {
        findMenuOrThrow(userId, menuId);

        MenuRestaurant menuRestaurant = findLinkOrThrow(menuId, restaurantId);

        menuRestaurant.update(request.rating(), request.memo());
        return toDetail(menuRestaurant);
    }

    @Transactional
    public void deleteMenuRestaurant(Long userId, Long menuId, Long restaurantId) {
        findMenuOrThrow(userId, menuId);

        menuRestaurantRepository.delete(findLinkOrThrow(menuId, restaurantId));
    }

    /** 타인의 메뉴·삭제된 메뉴 모두 MENU_NOT_FOUND(404)로 동일하게 응답한다. */
    private Menu findMenuOrThrow(Long userId, Long menuId) {
        return menuRepository.findByIdAndUserIdAndDeletedAtIsNull(menuId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
    }

    /**
     * 식당이 soft-delete된 링크는 조회(getMenuRestaurants)에서 이미 감춰지므로,
     * 수정·삭제에서도 존재하지 않는 링크로 취급해 화면과 동작을 일치시킨다.
     */
    private MenuRestaurant findLinkOrThrow(Long menuId, Long restaurantId) {
        return menuRestaurantRepository.findByMenuIdAndRestaurantId(menuId, restaurantId)
                .filter(mr -> !mr.getRestaurant().isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_RESTAURANT_NOT_FOUND));
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
