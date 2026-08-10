package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
                .naverPlaceId("naver_123")
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
}
