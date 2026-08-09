package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RestaurantServiceTest {

    @Mock private RestaurantRepository restaurantRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private RestaurantService restaurantService;

    private User user;
    private Restaurant restaurant;

    @BeforeEach
    void setUp() throws Exception {
        user = User.builder().email("test@test.com").nickname("tester").build();
        setId(user, 1L);

        restaurant = Restaurant.builder()
                .user(user).name("진주회관").address("서울시 중구")
                .phone("02-1234-5678")
                .latitude(new BigDecimal("37.5665350"))
                .longitude(new BigDecimal("126.9779692"))
                .naverUrl("https://naver.me/abc").naverPlaceId("12345")
                .build();
        setId(restaurant, 1L);
    }

    @Test
    @DisplayName("식당 목록 조회 - 사용자 소유 식당만 반환")
    void getRestaurants_success() throws Exception {
        Restaurant r2 = Restaurant.builder()
                .user(user).name("을지면옥").address("서울시 중구")
                .latitude(new BigDecimal("37.5660000")).longitude(new BigDecimal("126.9900000"))
                .build();
        setId(r2, 2L);
        given(restaurantRepository.findAllByUserIdAndDeletedAtIsNull(1L))
                .willReturn(List.of(restaurant, r2));

        List<RestaurantResponse.RestaurantSummary> result = restaurantService.getRestaurants(1L);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("진주회관");
    }

    @Test
    @DisplayName("식당 상세 조회 성공")
    void getRestaurant_success() {
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        RestaurantResponse.RestaurantDetail result = restaurantService.getRestaurant(1L, 1L);

        assertThat(result.name()).isEqualTo("진주회관");
        assertThat(result.latitude()).isEqualByComparingTo(new BigDecimal("37.5665350"));
    }

    @Test
    @DisplayName("식당 상세 조회 - 미존재 시 RESTAURANT_NOT_FOUND")
    void getRestaurant_notFound() {
        given(restaurantRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.getRestaurant(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_NOT_FOUND);
    }

    @Test
    @DisplayName("식당 상세 조회 - 타 사용자 접근 시 RESTAURANT_ACCESS_DENIED")
    void getRestaurant_otherUser_forbidden() {
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> restaurantService.getRestaurant(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_ACCESS_DENIED);
    }

    @Test
    @DisplayName("식당 상세 조회 - 삭제된 식당은 RESTAURANT_NOT_FOUND")
    void getRestaurant_deleted_notFound() {
        restaurant.softDelete();
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> restaurantService.getRestaurant(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_NOT_FOUND);
    }

    @Test
    @DisplayName("식당 생성 성공")
    void createRestaurant_success() {
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(restaurantRepository.save(any(Restaurant.class))).willAnswer(inv -> {
            Restaurant saved = inv.getArgument(0);
            try { setId(saved, 100L); } catch (Exception ignored) {}
            return saved;
        });

        RestaurantResponse.RestaurantDetail result = restaurantService.createRestaurant(1L,
                new RestaurantRequest.Create("새 식당", "주소", "010-1234",
                        new BigDecimal("37.5"), new BigDecimal("127.0"), null, null));

        assertThat(result.name()).isEqualTo("새 식당");
    }

    @Test
    @DisplayName("식당 수정 성공")
    void updateRestaurant_success() {
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        RestaurantResponse.RestaurantDetail result = restaurantService.updateRestaurant(1L, 1L,
                new RestaurantRequest.Update("수정된 식당", "새 주소", "010-9999",
                        new BigDecimal("37.5"), new BigDecimal("127.0"), null, null));

        assertThat(result.name()).isEqualTo("수정된 식당");
    }

    @Test
    @DisplayName("식당 삭제 성공 (soft delete)")
    void deleteRestaurant_success() {
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        restaurantService.deleteRestaurant(1L, 1L);

        assertThat(restaurant.isDeleted()).isTrue();
    }

    // --- 엣지 케이스 ---

    @Test
    @DisplayName("식당 수정 — 타 사용자 접근 시 RESTAURANT_ACCESS_DENIED")
    void updateRestaurant_otherUser_forbidden() {
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> restaurantService.updateRestaurant(2L, 1L,
                new RestaurantRequest.Update("수정", "주소", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_ACCESS_DENIED);
    }

    @Test
    @DisplayName("식당 삭제 — 타 사용자 접근 시 RESTAURANT_ACCESS_DENIED")
    void deleteRestaurant_otherUser_forbidden() {
        given(restaurantRepository.findById(1L)).willReturn(Optional.of(restaurant));

        assertThatThrownBy(() -> restaurantService.deleteRestaurant(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_ACCESS_DENIED);
    }

    @Test
    @DisplayName("식당 삭제 — 미존재 시 RESTAURANT_NOT_FOUND")
    void deleteRestaurant_notFound() {
        given(restaurantRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.deleteRestaurant(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_NOT_FOUND);
    }

    @Test
    @DisplayName("식당 수정 — 미존재 시 RESTAURANT_NOT_FOUND")
    void updateRestaurant_notFound() {
        given(restaurantRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> restaurantService.updateRestaurant(1L, 999L,
                new RestaurantRequest.Update("수정", "주소", null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_NOT_FOUND);
    }

    @Test
    @DisplayName("식당 생성 — optional 필드 null로 생성")
    void createRestaurant_withNullOptionalFields() {
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(restaurantRepository.save(any(Restaurant.class))).willAnswer(inv -> {
            Restaurant saved = inv.getArgument(0);
            try { setId(saved, 100L); } catch (Exception ignored) {}
            return saved;
        });

        RestaurantResponse.RestaurantDetail result = restaurantService.createRestaurant(1L,
                new RestaurantRequest.Create("미니멀 식당", "서울", null, null, null, null, null));

        assertThat(result.name()).isEqualTo("미니멀 식당");
        assertThat(result.phone()).isNull();
        assertThat(result.latitude()).isNull();
        assertThat(result.naverPlaceId()).isNull();
    }

    @Test
    @DisplayName("식당 목록 — 빈 목록 반환")
    void getRestaurants_emptyList() {
        given(restaurantRepository.findAllByUserIdAndDeletedAtIsNull(1L))
                .willReturn(List.of());

        List<RestaurantResponse.RestaurantSummary> result = restaurantService.getRestaurants(1L);

        assertThat(result).isEmpty();
    }

    private void setId(Object entity, Long id) throws Exception {
        Field idField = entity.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }
}
