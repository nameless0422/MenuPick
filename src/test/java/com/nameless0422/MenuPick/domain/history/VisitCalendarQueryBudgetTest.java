package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class VisitCalendarQueryBudgetTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, KST).toInstant(), KST);

    @Autowired private HistoryRepository historyRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    @Test
    void calendarProjectionUsesOneStatementWithoutNPlusOne() {
        User user = userRepository.save(User.builder().email("calendar-budget@example.com").nickname("달력예산").build());
        Menu menu = menuRepository.save(Menu.builder().user(user).name("달력메뉴").weight(1).build());
        for (int index = 0; index < 40; index++) {
            LocalDateTime visitedAt = LocalDateTime.of(2026, 1, 1, 0, 0).plusHours(index);
            History history = History.builder().user(user).menu(menu).recommendedAt(visitedAt.minusMinutes(1)).build();
            history.markVisited(visitedAt);
            historyRepository.save(history);
        }

        entityManager.flush();
        entityManager.clear();
        Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();

        HistoryService service = new HistoryService(historyRepository, restaurantRepository, CLOCK);
        var response = service.getVisitCalendar(user.getId(), "2026-01");

        assertThat(response.entries()).hasSize(40);
        assertThat(statistics.getPrepareStatementCount())
                .as("방문 달력 서비스 호출의 JDBC 구문 수")
                .isEqualTo(1L);
    }
}
