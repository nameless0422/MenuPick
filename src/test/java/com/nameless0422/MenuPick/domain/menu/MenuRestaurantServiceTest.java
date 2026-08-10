package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MenuRestaurantServiceTest {

    @Mock private MenuRepository menuRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @Mock private MenuRestaurantRepository menuRestaurantRepository;

    @InjectMocks private MenuRestaurantService menuRestaurantService;

    private User user;
    private Menu menu;
    private Restaurant restaurant;
    private MenuRestaurant link;

    @BeforeEach
    void setUp() {
        user = User.builder().email("test@test.com").nickname("tester").build();
        ReflectionTestUtils.setField(user, "id", 1L);

        menu = Menu.builder().user(user).name("김치찌개").memo("맛있음").weight(3).build();
        ReflectionTestUtils.setField(menu, "id", 1L);

        restaurant = Restaurant.builder()
                .user(user).name("진주회관").address("서울시 중구")
                .latitude(new BigDecimal("37.5665350"))
                .longitude(new BigDecimal("126.9779692"))
                .build();
        ReflectionTestUtils.setField(restaurant, "id", 2L);

        link = MenuRestaurant.builder().menu(menu).restaurant(restaurant).rating(4).memo("점심").build();
        ReflectionTestUtils.setField(link, "id", 3L);
    }

    // --- 생성 ---

    @Test
    @DisplayName("메뉴-식당 연결 생성 성공")
    void createMenuRestaurant_success() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(2L, 1L))
                .willReturn(Optional.of(restaurant));
        given(menuRestaurantRepository.existsByMenuIdAndRestaurantId(1L, 2L)).willReturn(false);
        given(menuRestaurantRepository.save(any(MenuRestaurant.class))).willReturn(link);

        MenuRestaurantResponse.MenuRestaurantDetail result = menuRestaurantService.createMenuRestaurant(
                1L, 1L, new MenuRestaurantRequest.Create(2L, 4, "점심"));

        assertThat(result.restaurantName()).isEqualTo("진주회관");
        assertThat(result.rating()).isEqualTo(4);
    }

    @Test
    @DisplayName("메뉴-식당 연결 생성 - 타 사용자의 메뉴면 MENU_NOT_FOUND (403 아님)")
    void createMenuRestaurant_otherUsersMenu_notFound() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> menuRestaurantService.createMenuRestaurant(
                99L, 1L, new MenuRestaurantRequest.Create(2L, 4, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
    }

    @Test
    @DisplayName("메뉴-식당 연결 생성 - 타 사용자의 식당이면 RESTAURANT_NOT_FOUND (403 아님)")
    void createMenuRestaurant_otherUsersRestaurant_notFound() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(2L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> menuRestaurantService.createMenuRestaurant(
                1L, 1L, new MenuRestaurantRequest.Create(2L, 4, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_NOT_FOUND);
    }

    @Test
    @DisplayName("메뉴-식당 연결 생성 - 사전 검사에서 중복이면 MENU_RESTAURANT_DUPLICATE")
    void createMenuRestaurant_duplicate_preCheck() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(2L, 1L))
                .willReturn(Optional.of(restaurant));
        given(menuRestaurantRepository.existsByMenuIdAndRestaurantId(1L, 2L)).willReturn(true);

        assertThatThrownBy(() -> menuRestaurantService.createMenuRestaurant(
                1L, 1L, new MenuRestaurantRequest.Create(2L, 4, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_RESTAURANT_DUPLICATE);

        verify(menuRestaurantRepository, never()).save(any(MenuRestaurant.class));
    }

    @Test
    @DisplayName("메뉴-식당 연결 생성 - 사전 검사 통과 후 유니크 제약 위반(동시 생성)도 MENU_RESTAURANT_DUPLICATE")
    void createMenuRestaurant_concurrentInsert_translatedToDuplicate() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(2L, 1L))
                .willReturn(Optional.of(restaurant));
        given(menuRestaurantRepository.existsByMenuIdAndRestaurantId(1L, 2L)).willReturn(false);
        given(menuRestaurantRepository.save(any(MenuRestaurant.class)))
                .willThrow(new DataIntegrityViolationException("uq_menu_restaurant"));

        assertThatThrownBy(() -> menuRestaurantService.createMenuRestaurant(
                1L, 1L, new MenuRestaurantRequest.Create(2L, 4, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_RESTAURANT_DUPLICATE);
    }

    // --- 조회 ---

    @Test
    @DisplayName("메뉴-식당 목록 조회 - soft-delete된 식당의 링크는 제외된다")
    void getMenuRestaurants_excludesDeletedRestaurants() {
        Restaurant deleted = Restaurant.builder()
                .user(user).name("폐업식당").address("서울")
                .latitude(new BigDecimal("37.5")).longitude(new BigDecimal("127.0"))
                .build();
        ReflectionTestUtils.setField(deleted, "id", 9L);
        deleted.softDelete();
        MenuRestaurant deletedLink = MenuRestaurant.builder().menu(menu).restaurant(deleted).build();

        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(menuRestaurantRepository.findAllByMenuId(1L)).willReturn(List.of(link, deletedLink));

        var result = menuRestaurantService.getMenuRestaurants(1L, 1L);

        assertThat(result.menuRestaurants()).hasSize(1);
        assertThat(result.menuRestaurants().get(0).restaurantName()).isEqualTo("진주회관");
    }

    // --- 수정·삭제 ---

    @Test
    @DisplayName("메뉴-식당 연결 수정 성공")
    void updateMenuRestaurant_success() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(menuRestaurantRepository.findByMenuIdAndRestaurantId(1L, 2L)).willReturn(Optional.of(link));

        var result = menuRestaurantService.updateMenuRestaurant(
                1L, 1L, 2L, new MenuRestaurantRequest.Update(5, "저녁"));

        assertThat(result.rating()).isEqualTo(5);
        assertThat(result.memo()).isEqualTo("저녁");
    }

    @Test
    @DisplayName("메뉴-식당 연결 수정 - 식당이 soft-delete면 MENU_RESTAURANT_NOT_FOUND")
    void updateMenuRestaurant_deletedRestaurant_notFound() {
        restaurant.softDelete();
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(menuRestaurantRepository.findByMenuIdAndRestaurantId(1L, 2L)).willReturn(Optional.of(link));

        assertThatThrownBy(() -> menuRestaurantService.updateMenuRestaurant(
                1L, 1L, 2L, new MenuRestaurantRequest.Update(5, "저녁")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_RESTAURANT_NOT_FOUND);
    }

    @Test
    @DisplayName("메뉴-식당 연결 삭제 성공")
    void deleteMenuRestaurant_success() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(menuRestaurantRepository.findByMenuIdAndRestaurantId(1L, 2L)).willReturn(Optional.of(link));

        menuRestaurantService.deleteMenuRestaurant(1L, 1L, 2L);

        verify(menuRestaurantRepository).delete(link);
    }

    @Test
    @DisplayName("메뉴-식당 연결 삭제 - 식당이 soft-delete면 MENU_RESTAURANT_NOT_FOUND")
    void deleteMenuRestaurant_deletedRestaurant_notFound() {
        restaurant.softDelete();
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(menuRestaurantRepository.findByMenuIdAndRestaurantId(1L, 2L)).willReturn(Optional.of(link));

        assertThatThrownBy(() -> menuRestaurantService.deleteMenuRestaurant(1L, 1L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_RESTAURANT_NOT_FOUND);

        verify(menuRestaurantRepository, never()).delete(any(MenuRestaurant.class));
    }

    @Test
    @DisplayName("메뉴-식당 연결 삭제 - 링크가 없으면 MENU_RESTAURANT_NOT_FOUND")
    void deleteMenuRestaurant_linkNotFound() {
        given(menuRepository.findByIdAndUserIdAndDeletedAtIsNull(1L, 1L)).willReturn(Optional.of(menu));
        given(menuRestaurantRepository.findByMenuIdAndRestaurantId(1L, 2L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> menuRestaurantService.deleteMenuRestaurant(1L, 1L, 2L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_RESTAURANT_NOT_FOUND);
    }
}
