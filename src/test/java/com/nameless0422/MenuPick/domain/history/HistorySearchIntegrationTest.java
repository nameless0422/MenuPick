package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, HistoryService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HistorySearchIntegrationTest extends AbstractIntegrationTest {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 10, 9, 12, 0, 0, 0, KST).toInstant(), KST);
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    @MockitoBean private Clock clock;
    @BeforeEach void fixedClock() {
        given(clock.instant()).willReturn(CLOCK.instant());
        given(clock.getZone()).willReturn(KST);
    }

    @Autowired private HistoryService service;
    @Autowired private HistoryRepository histories;
    @Autowired private UserRepository users;
    @Autowired private MenuRepository menus;
    @Autowired private RestaurantRepository restaurants;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> fixtureUsers = new ArrayList<>();

    @AfterEach
    void removeCommittedFixtures() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Long userId : fixtureUsers) {
                histories.deleteFilterConditionsByUserId(userId);
                histories.deleteAllByUserId(userId);
                menus.deleteAllByUserId(userId);
                restaurants.deleteAllByUserId(userId);
                users.deleteById(userId);
            }
        });
    }

    private User user(String suffix) {
        User user = users.save(User.builder().email("history-search-" + suffix + "@test.com")
                .nickname("기록검색-" + suffix).build());
        fixtureUsers.add(user.getId());
        return user;
    }
    private Menu menu(User user, String name) {
        return menus.save(Menu.builder().user(user).name(name).weight(1).build());
    }
    private Restaurant restaurant(User user, String name) {
        return restaurants.save(Restaurant.builder().user(user).name(name).address("서울")
                .latitude(new BigDecimal("37.56")).longitude(new BigDecimal("126.97")).build());
    }
    private History history(User user, Menu menu, Restaurant restaurant, LocalDateTime at, boolean visited) {
        History history = History.builder().user(user).menu(menu).restaurant(restaurant).recommendedAt(at).build();
        if (visited) history.markVisited(at.plusMinutes(1));
        history.addFilterCondition("CATEGORY", "한식");
        return histories.save(history);
    }

    @Test
    void namesMatchEitherNullableReferenceAndPreserveDeletedNames() {
        User user = user("names");
        Menu menu = menu(user, "김치찌개");
        Restaurant restaurant = restaurant(user, "김치 식당");
        History menuOnly = history(user, menu, null, NOW, false);
        History restaurantOnly = history(user, null, restaurant, NOW, false);
        history(user, null, null, NOW, false);
        history(user, menu(user, "파스타"), null, NOW, false);
        menu.softDelete(NOW);
        menus.save(menu);
        restaurant.softDelete(NOW);
        restaurants.save(restaurant);

        var result = service.getHistories(user.getId(), null, 7, null, 20, "  김치  ");
        assertThat(result.histories()).extracting(h -> h.id())
                .containsExactly(restaurantOnly.getId(), menuOnly.getId());
        assertThat(result.histories().get(0).restaurantName()).isEqualTo("김치 식당");
        assertThat(result.histories().get(1).menuName()).isEqualTo("김치찌개");
        assertThat(result.histories().get(1).menuId()).isNull();
        assertThat(result.histories().get(1).filterConditions()).hasSize(1);
    }

    @Test
    void ownerPeriodVisitAndCursorApplyTogetherBeforeLimit() {
        User user = user("filters");
        Menu menu = menu(user, "김치찌개");
        History first = history(user, menu, null, NOW.minusDays(2), true);
        History second = history(user, menu, null, NOW.minusDays(1), true);
        History third = history(user, menu, null, NOW, true);
        history(user, menu, null, NOW, false);
        history(user, menu, null, NOW.minusDays(7), true);
        history(user, menu, null, NOW.minusDays(8), true);
        User other = user("other");
        history(other, menu(other, "김치찌개"), null, NOW, true);

        var page = service.getHistories(user.getId(), null, 7, true, 2, "김치");
        assertThat(page.histories()).extracting(h -> h.id()).containsExactly(third.getId(), second.getId());
        assertThat(page.hasNext()).isTrue();
        assertThat(page.nextCursor()).isEqualTo(second.getId());
        var next = service.getHistories(user.getId(), page.nextCursor(), 7, true, 2, "김치");
        assertThat(next.histories()).extracting(h -> h.id()).containsExactly(first.getId());
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextCursor()).isNull();
        assertThat(service.getHistories(user.getId(), null, 7, false, 20, "김치").histories()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\"})
    void likeCharactersAreLiteral(String symbol) {
        User user = user("literal-" + (int) symbol.charAt(0));
        History menuMatch = history(user, menu(user, "메뉴" + symbol + "이름"), null, NOW, false);
        History restaurantMatch = history(user, null, restaurant(user, "식당" + symbol + "이름"), NOW, false);
        history(user, menu(user, "일반 메뉴"), restaurant(user, "일반 식당"), NOW, false);
        assertThat(service.getHistories(user.getId(), null, 7, null, 20, symbol).histories())
                .extracting(h -> h.id()).containsExactly(restaurantMatch.getId(), menuMatch.getId());
    }

    @Test
    void nullEmptyAndWhitespaceKeepOriginalListIncludingMissingReferences() {
        User user = user("blank");
        History history = history(user, null, null, NOW, false);
        for (String keyword : new String[]{null, "", "  "}) {
            assertThat(service.getHistories(user.getId(), null, null, null, 20, keyword).histories())
                    .extracting(h -> h.id()).containsExactly(history.getId());
        }
    }

    @Test
    void searchPageLoadsDifferentMenusRestaurantsAndConditionsWithTwoStatements() {
        User user = user("budget");
        for (int i = 0; i < 30; i++) {
            history(user, menu(user, "김치" + i), restaurant(user, "식당" + i), NOW, false);
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            var page = service.getHistories(user.getId(), null, 7, null, 20, "김치");
            assertThat(page.histories()).hasSize(20);
            assertThat(page.hasNext()).isTrue();
            assertThat(page.histories()).allSatisfy(h -> {
                assertThat(h.restaurantName()).startsWith("식당");
                assertThat(h.filterConditions()).hasSize(1);
            });
            assertThat(statistics.getPrepareStatementCount()).as("검색 페이지의 JDBC 구문 수").isEqualTo(2);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
