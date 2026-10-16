package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.*;
import com.nameless0422.MenuPick.domain.restaurant.*;
import com.nameless0422.MenuPick.domain.user.*;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@DataJpaTest(properties = "spring.datasource.hikari.maximum-pool-size=2")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, HistoryExportService.class})
@ActiveProfiles("integration")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class HistoryExportIntegrationTest extends AbstractIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    @MockitoBean private Clock clock;
    @Autowired private HistoryExportService service;
    @Autowired private HistoryRepository histories;
    @Autowired private UserRepository users;
    @Autowired private MenuRepository menus;
    @Autowired private RestaurantRepository restaurants;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    private User owner;

    @BeforeEach void setup() {
        given(clock.instant()).willReturn(CLOCK.instant());
        given(clock.getZone()).willReturn(CLOCK.getZone());
        owner = user("owner");
    }
    private User user(String name) { return users.save(User.builder().email("csv-" + name + "@test.com").nickname("csv" + name).build()); }
    private Menu menu(String name) { return menus.save(Menu.builder().user(owner).name(name).build()); }
    private History history(User user, Menu menu, Restaurant restaurant, LocalDateTime at, boolean visited) {
        History row = History.builder().user(user).menu(menu).restaurant(restaurant).recommendedAt(at).build();
        if (visited) row.markVisited(NOW);
        row.updateMemo("메모, \"보존\"\n다음 줄");
        return histories.save(row);
    }
    private org.hibernate.stat.Statistics clearStatistics() {
        em.flush(); em.clear();
        var statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true); statistics.clear();
        return statistics;
    }

    @Test void filtersByOwnerPeriodVisitAndLiteralNamesWithOneQuery() {
        Menu food = menu("100%_!국수");
        history(owner, food, null, NOW.minusDays(1), true);
        history(owner, food, null, NOW.minusDays(1), false);
        history(owner, food, null, NOW.minusDays(8), true);
        history(owner, food, null, NOW.minusDays(7), true); // 목록과 같은 배타적 경계
        history(user("other"), food, null, NOW, true);
        history(owner, menu("100XX국수"), null, NOW, true);
        var statistics = clearStatistics();
        String csv = new String(service.export(owner.getId(), 7, true, " 100%_! ").content(), StandardCharsets.UTF_8);
        assertThat(csv).contains("100%_!국수").doesNotContain("100XX국수", "미방문");
        assertThat(csv.split("\r\n")).hasSize(2);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test void includesDeletedNamesAndMissingReferencesInDescendingIdOrder() {
        Menu food = menu("삭제된 국수"); food.softDelete(NOW); menus.save(food);
        Restaurant place = restaurants.save(Restaurant.builder().user(owner).name("검색 식당")
                .latitude(new BigDecimal("37.5")).longitude(new BigDecimal("127")).build());
        place.softDelete(NOW); restaurants.save(place);
        History named = history(owner, food, place, NOW, true);
        named.recordFeedback(RecommendationFeedback.ACCEPTED);
        History missing = history(owner, null, null, NOW, false);
        var statistics = clearStatistics();
        String all = new String(service.export(owner.getId(), 7, null, null).content(), StandardCharsets.UTF_8);
        assertThat(all).contains("삭제된 국수", "검색 식당", "수락");
        assertThat(all.indexOf("\"" + missing.getId() + "\"")).isLessThan(all.indexOf("\"" + named.getId() + "\""));
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(new String(service.export(owner.getId(), 7, null, "검색 식당").content(), StandardCharsets.UTF_8))
                .contains("삭제된 국수", "검색 식당");
    }

    @Test void exactlyOneThousandRowsSucceedAndOneMoreIsRejected() {
        em.flush();
        jdbc.batchUpdate("INSERT INTO histories(user_id,is_visited,recommended_at,version) VALUES (?,false,?,0)",
                java.util.stream.IntStream.range(0, 1000).mapToObj(i -> new Object[]{owner.getId(), NOW}).toList());
        var statistics = clearStatistics();
        String csv = new String(service.export(owner.getId(), 7, null, null).content(), StandardCharsets.UTF_8);
        assertThat(csv.split("\r\n")).hasSize(1001);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        jdbc.update("INSERT INTO histories(user_id,is_visited,recommended_at,version) VALUES (?,false,?,0)", owner.getId(), NOW);
        statistics.clear();
        assertThatThrownBy(() -> service.export(owner.getId(), 7, null, null)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.HISTORY_EXPORT_LIMIT_EXCEEDED);
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }
}
