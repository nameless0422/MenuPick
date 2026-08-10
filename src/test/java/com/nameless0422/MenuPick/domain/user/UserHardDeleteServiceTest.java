package com.nameless0422.MenuPick.domain.user;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import com.nameless0422.MenuPick.domain.auth.RefreshTokenStore;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, UserHardDeleteService.class})
@ActiveProfiles("integration")
class UserHardDeleteServiceTest extends AbstractIntegrationTest {

    @Autowired private UserHardDeleteService userHardDeleteService;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthProviderRepository authProviderRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private HistoryRepository historyRepository;
    @Autowired private EntityManager em;

    /** @DataJpaTest 슬라이스에는 Redis가 없다 — 키 삭제 호출 여부만 검증한다. */
    @MockitoBean private RefreshTokenStore refreshTokenStore;

    private User targetUser;
    private User otherUser;

    @BeforeEach
    void setUp() {
        targetUser = createUserWithFullData("target@example.com", "삭제대상");
        otherUser = createUserWithFullData("other@example.com", "다른유저");
    }

    @Test
    @DisplayName("purge 시 유저와 모든 연관 데이터가 FK 위반 없이 하드 삭제된다")
    void purge_deletesUserAndAllRelatedData() {
        userHardDeleteService.purge(targetUser.getId());
        em.flush();
        em.clear();

        assertThat(userRepository.findById(targetUser.getId())).isEmpty();
        assertThat(authProviderRepository.findAllByUserId(targetUser.getId())).isEmpty();
        assertThat(menuRepository.findAllByUserIdAndDeletedAtIsNull(targetUser.getId())).isEmpty();
        assertThat(tagRepository.findByUserIdAndName(targetUser.getId(), "혼밥")).isEmpty();
        assertThat(restaurantRepository.findAllByUserIdAndDeletedAtIsNull(targetUser.getId())).isEmpty();
        assertThat(historyRepository.findByUserIdOrderByRecommendedAtDesc(targetUser.getId())).isEmpty();
    }

    @Test
    @DisplayName("purge 시 Redis의 Refresh Token 키도 함께 삭제한다")
    void purge_deletesRefreshToken() {
        userHardDeleteService.purge(targetUser.getId());

        verify(refreshTokenStore).delete(targetUser.getId());
    }

    @Test
    @DisplayName("purge는 다른 유저의 데이터에 영향을 주지 않는다")
    void purge_doesNotAffectOtherUsers() {
        userHardDeleteService.purge(targetUser.getId());
        em.flush();
        em.clear();

        assertThat(userRepository.findById(otherUser.getId())).isPresent();
        assertThat(authProviderRepository.findAllByUserId(otherUser.getId())).hasSize(1);
        assertThat(menuRepository.findAllByUserIdAndDeletedAtIsNull(otherUser.getId())).hasSize(1);
        assertThat(tagRepository.findByUserIdAndName(otherUser.getId(), "혼밥")).isPresent();
        assertThat(restaurantRepository.findAllByUserIdAndDeletedAtIsNull(otherUser.getId())).hasSize(1);
        assertThat(historyRepository.findByUserIdOrderByRecommendedAtDesc(otherUser.getId())).hasSize(1);
    }

    private User createUserWithFullData(String email, String nickname) {
        User user = userRepository.save(User.builder().email(email).nickname(nickname).build());

        authProviderRepository.save(AuthProvider.builder()
                .user(user).provider("KAKAO").socialId("kakao_" + email).build());

        Tag tag = tagRepository.save(Tag.builder().user(user).name("혼밥").build());

        Menu menu = Menu.builder().user(user).name("김치찌개").weight(1).build();
        menu.addCategory("KOREAN");
        menu.addTag(tag);
        menu = menuRepository.save(menu);

        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .user(user).name("맛집")
                .latitude(BigDecimal.valueOf(37.5)).longitude(BigDecimal.valueOf(127.0))
                .build());

        menuRestaurantRepository.save(MenuRestaurant.builder()
                .menu(menu).restaurant(restaurant).rating(5).build());

        History history = History.builder()
                .user(user).menu(menu).recommendedAt(LocalDateTime.now()).build();
        history.addFilterCondition("CATEGORY", "KOREAN");
        historyRepository.save(history);

        return user;
    }
}
