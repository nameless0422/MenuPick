package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class RestaurantRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private RestaurantRepository restaurantRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .email("rest@example.com")
                .nickname("식당유저")
                .build());
    }

    @Test
    @DisplayName("식당을 저장하고 조회한다")
    void save_and_findById() {
        Restaurant restaurant = Restaurant.builder()
                .user(user)
                .name("진주회관")
                .address("서울시 중구")
                .phone("02-1234-5678")
                .latitude(new BigDecimal("37.5665350"))
                .longitude(new BigDecimal("126.9779690"))
                .kakaoPlaceId("kakao_123")
                .build();

        Restaurant saved = restaurantRepository.save(restaurant);

        Restaurant found = restaurantRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("진주회관");
        assertThat(found.getLatitude()).isEqualByComparingTo(new BigDecimal("37.5665350"));
    }

    @Test
    @DisplayName("사용자 ID로 삭제되지 않은 식당 목록을 조회한다")
    void findAllByUserIdAndDeletedAtIsNull() {
        restaurantRepository.save(Restaurant.builder()
                .user(user).name("식당A")
                .latitude(new BigDecimal("37.0000000"))
                .longitude(new BigDecimal("127.0000000"))
                .build());
        Restaurant deleted = restaurantRepository.save(Restaurant.builder()
                .user(user).name("삭제식당")
                .latitude(new BigDecimal("37.0000000"))
                .longitude(new BigDecimal("127.0000000"))
                .build());
        deleted.softDelete(LocalDateTime.now());
        restaurantRepository.flush();

        List<Restaurant> restaurants = restaurantRepository
                .findAllByUserIdAndDeletedAtIsNull(user.getId());
        assertThat(restaurants).hasSize(1);
        assertThat(restaurants.get(0).getName()).isEqualTo("식당A");
    }

    @Test
    @DisplayName("이름과 주소를 검색하되 다른 사용자와 삭제한 식당은 제외한다")
    void searchSavedRestaurants_scopedNameAndAddress() {
        Restaurant byName = saveRestaurant(user, "중구식당", null);
        Restaurant byAddress = saveRestaurant(user, "진주회관", "서울 중구 세종대로");
        saveRestaurant(user, "강남식당", "서울 강남구");
        Restaurant deleted = saveRestaurant(user, "중구삭제식당", "중구");
        deleted.softDelete(LocalDateTime.now());
        User other = userRepository.save(User.builder().email("other-rest@example.com").nickname("다른유저").build());
        saveRestaurant(other, "중구타인식당", "중구");
        restaurantRepository.flush();

        assertThat(restaurantRepository.searchSavedRestaurants(user.getId(), "%중구%"))
                .extracting(Restaurant::getId).containsExactly(byAddress.getId(), byName.getId());
        assertThat(restaurantRepository.searchSavedRestaurants(user.getId(), "%없는식당%"))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"%,%!%%", "_,%!_%", "!,%!!%", "!%_,%!!!%!_%"})
    @DisplayName("LIKE 특수 문자를 문자 그대로 이름과 주소에서 검색한다")
    void searchSavedRestaurants_literalWildcards(String keyword, String pattern) {
        Restaurant byName = saveRestaurant(user, "가게" + keyword + "이름", null);
        Restaurant byAddress = saveRestaurant(user, "주소로찾는곳", "서울 " + keyword + " 거리");
        saveRestaurant(user, "일반가게", "서울 아무거리");
        restaurantRepository.flush();

        assertThat(restaurantRepository.searchSavedRestaurants(user.getId(), pattern))
                .extracting(Restaurant::getId).containsExactly(byAddress.getId(), byName.getId());
    }

    private Restaurant saveRestaurant(User owner, String name, String address) {
        return restaurantRepository.save(Restaurant.builder().user(owner).name(name).address(address)
                .latitude(new BigDecimal("37.5665")).longitude(new BigDecimal("126.978"))
                .build());
    }
}
