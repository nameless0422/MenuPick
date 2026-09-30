package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, MenuRestaurantService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MenuRestaurantServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MenuRestaurantService service;
    @Autowired private MenuRepository menuRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MenuRestaurantRepository linkRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void listReadsActiveRestaurantsInTwoStatements() {
        User user = userRepository.save(
                User.builder().email("link-list@test.com").nickname("식당목록유저").build());
        Menu menu = menuRepository.save(Menu.builder().user(user).name("국수").build());
        for (int i = 0; i < 5; i++) {
            Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                    .user(user).name("국숫집 " + i).address("서울")
                    .latitude(new BigDecimal("37.5")).longitude(new BigDecimal("127.0")).build());
            linkRepository.save(MenuRestaurant.builder().menu(menu).restaurant(restaurant).build());
        }
        Restaurant deleted = restaurantRepository.save(Restaurant.builder()
                .user(user).name("폐업한 국숫집").address("서울")
                .latitude(new BigDecimal("37.5")).longitude(new BigDecimal("127.0")).build());
        linkRepository.save(MenuRestaurant.builder().menu(menu).restaurant(deleted).build());
        deleted.softDelete(LocalDateTime.now());
        restaurantRepository.save(deleted);

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        var result = service.getMenuRestaurants(user.getId(), menu.getId());

        assertThat(result.menuRestaurants()).hasSize(5)
                .extracting(detail -> detail.restaurantName())
                .doesNotContain("폐업한 국숫집");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }
}
