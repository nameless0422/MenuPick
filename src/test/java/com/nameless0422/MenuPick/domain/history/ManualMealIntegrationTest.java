package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryRequest;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 직접 식사 기록(D-081)을 실제 MySQL로 확인한다 — 저장 모양, 하루 상한의 직렬화, 그리고
 * "추천"을 세는 쿼리가 직접 기록을 빼고 "먹음"을 세는 쿼리가 포함하는지.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, HistoryService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ManualMealIntegrationTest extends AbstractIntegrationTest {
    /** KST 2026-10-09 12:00. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    @MockitoBean private Clock clock;
    @Autowired private HistoryService service;
    @Autowired private HistoryRepository histories;
    @Autowired private MenuRepository menus;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> fixtureUsers = new ArrayList<>();

    @BeforeEach void fixedClock() {
        given(clock.instant()).willReturn(CLOCK.instant());
        given(clock.getZone()).willReturn(CLOCK.getZone());
    }

    @AfterEach void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Long id : fixtureUsers) {
                histories.deleteFilterConditionsByUserId(id);
                histories.deleteAllByUserId(id);
                menus.deleteMenuTagsByUserId(id);
                menus.deleteMenuCategoriesByUserId(id);
                menus.deleteAllByUserId(id);
                users.deleteById(id);
            }
        });
    }

    private User user(String suffix) {
        User user = users.save(User.builder().email("manual-meal-" + suffix + "@test.com")
                .nickname("직접기록-" + suffix).build());
        fixtureUsers.add(user.getId());
        return user;
    }

    private Menu menu(User owner, String name) {
        return menus.save(Menu.builder().user(owner).name(name).build());
    }

    private HistoryRequest.ManualMealRequest meal(Menu menu, LocalDateTime eatenAt, MealSlot slot) {
        return new HistoryRequest.ManualMealRequest(menu.getId(), eatenAt, slot, null, null);
    }

    @Test void savesAsEatenManualRowWithEatenTimeAsRecommendedTime() {
        User owner = user("save");
        Menu kimchi = menu(owner, "김치찌개");
        LocalDateTime eatenAt = NOW.minusDays(1).withHour(19).withMinute(30).withSecond(0);

        HistoryResponse.HistorySummary created = service.createManualMeal(owner.getId(),
                new HistoryRequest.ManualMealRequest(kimchi.getId(), eatenAt, null, null, "  회사 앞  "));

        assertThat(created.source()).isEqualTo(HistorySource.MANUAL);
        assertThat(created.mealSlot()).as("19:30은 저녁으로 추정한다").isEqualTo(MealSlot.DINNER);
        assertThat(created.isVisited()).isTrue();
        assertThat(created.visitedAt()).isEqualTo(eatenAt);
        assertThat(created.recommendedAt()).as("기간 필터·최근 제외가 이 컬럼을 본다").isEqualTo(eatenAt);
        assertThat(created.memo()).isEqualTo("회사 앞");

        var listed = service.getHistories(owner.getId(), null, 7, null, 20).histories();
        assertThat(listed).singleElement().satisfies(row -> {
            assertThat(row.source()).isEqualTo(HistorySource.MANUAL);
            assertThat(row.mealSlot()).isEqualTo(MealSlot.DINNER);
        });
    }

    @Test void chosenSlotWinsOverTimeAndPickRowsGetEstimatedSlot() {
        User owner = user("slot");
        Menu toast = menu(owner, "토스트");
        service.createManualMeal(owner.getId(), meal(toast, NOW.withHour(9).withMinute(0).withSecond(0), MealSlot.SNACK));
        histories.save(History.builder().user(owner).menu(toast).recommendedAt(NOW.withHour(8)).build());

        var rows = service.getHistories(owner.getId(), null, 7, null, 20).histories();
        assertThat(rows).extracting(HistoryResponse.HistorySummary::source, HistoryResponse.HistorySummary::mealSlot)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(HistorySource.MANUAL, MealSlot.SNACK),
                        org.assertj.core.groups.Tuple.tuple(HistorySource.PICK, MealSlot.BREAKFAST));
    }

    @Test void rejectsFutureTooOldOthersAndDeletedMenus() {
        User owner = user("reject");
        User other = user("reject-other");
        Menu mine = menu(owner, "비빔밥");
        Menu theirs = menu(other, "냉면");
        Menu deleted = menu(owner, "지운 메뉴");
        deleted.softDelete(NOW);
        menus.save(deleted);

        assertThatThrownBy(() -> service.createManualMeal(owner.getId(), meal(mine, NOW.plusMinutes(1), null)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.createManualMeal(owner.getId(),
                meal(mine, NOW.toLocalDate().minusDays(366).atTime(12, 0), null)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.createManualMeal(owner.getId(), meal(theirs, NOW, null)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
        assertThatThrownBy(() -> service.createManualMeal(owner.getId(), meal(deleted, NOW, null)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);

        // 경계: 정확히 지금, 정확히 365일 전 0시는 받는다.
        service.createManualMeal(owner.getId(), meal(mine, NOW.withNano(0), null));
        service.createManualMeal(owner.getId(), meal(mine, NOW.toLocalDate().minusDays(365).atStartOfDay(), null));
        assertThat(histories.countManualMealsBetween(owner.getId(), NOW.minusYears(2), NOW.plusDays(1))).isEqualTo(2);
    }

    @Test void dailyLimitCountsByEatenDayNotByCreationDay() {
        User owner = user("limit");
        Menu rice = menu(owner, "국밥");
        LocalDateTime yesterday = NOW.minusDays(1).withHour(12).withMinute(0).withSecond(0);
        for (int i = 0; i < HistoryService.MANUAL_MEALS_PER_DAY; i++) {
            service.createManualMeal(owner.getId(), meal(rice, yesterday.plusMinutes(i), null));
        }
        assertThatThrownBy(() -> service.createManualMeal(owner.getId(), meal(rice, yesterday.plusHours(5), null)))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.MANUAL_MEAL_DAILY_LIMIT_EXCEEDED);
        // 같은 날 만들었어도 먹은 날이 다르면 다른 날의 몫이다.
        service.createManualMeal(owner.getId(), meal(rice, NOW.withNano(0), null));
        // 픽 기록은 세지 않는다.
        histories.save(History.builder().user(owner).menu(rice).recommendedAt(yesterday).build());
        assertThat(histories.countManualMealsBetween(owner.getId(),
                yesterday.toLocalDate().atStartOfDay(), yesterday.toLocalDate().plusDays(1).atStartOfDay()))
                .isEqualTo(HistoryService.MANUAL_MEALS_PER_DAY);
    }

    /** 상한 판정이 사용자 행 잠금으로 직렬화되는지. 잠금이 없으면 동시 요청이 모두 9건을 보고 통과한다. */
    @Test void concurrentRequestsAtTheLimitLetExactlyOneThrough() throws Exception {
        User owner = user("concurrent");
        Menu rice = menu(owner, "덮밥");
        LocalDateTime day = NOW.minusDays(2).withHour(12).withMinute(0).withSecond(0);
        for (int i = 0; i < HistoryService.MANUAL_MEALS_PER_DAY - 1; i++) {
            service.createManualMeal(owner.getId(), meal(rice, day.plusMinutes(i), null));
        }

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int offset = i;
            results.add(pool.submit(() -> {
                start.await();
                try {
                    service.createManualMeal(owner.getId(), meal(rice, day.plusHours(1).plusMinutes(offset), null));
                    return true;
                } catch (BusinessException e) {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.MANUAL_MEAL_DAILY_LIMIT_EXCEEDED);
                    return false;
                }
            }));
        }
        start.countDown();
        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) succeeded++;
        }
        pool.shutdown();
        assertThat(succeeded).isEqualTo(1);
        assertThat(histories.countManualMealsBetween(owner.getId(),
                day.toLocalDate().atStartOfDay(), day.toLocalDate().plusDays(1).atStartOfDay()))
                .isEqualTo(HistoryService.MANUAL_MEALS_PER_DAY);
    }

    @Test void pickMetricsIgnoreManualRowsButEatingCountsIncludeThem() {
        User owner = user("metrics");
        Menu pickMenu = menu(owner, "짜장면");
        Menu logMenu = menu(owner, "샐러드");
        histories.save(History.builder().user(owner).menu(pickMenu).recommendedAt(NOW.minusHours(3)).build());
        service.createManualMeal(owner.getId(), meal(logMenu, NOW.minusHours(1).withNano(0), null));
        LocalDateTime since = NOW.minusDays(1);

        Object[] mine = histories.countMyPicksAndEatenSince(owner.getId(), since, RecommendationFeedback.ACCEPTED).get(0);
        assertThat(((Number) mine[0]).longValue()).as("뽑은 수는 픽만").isEqualTo(1);
        assertThat(((Number) mine[1]).longValue()).as("먹은 수는 직접 기록 포함").isEqualTo(1);
        assertThat(((Number) mine[2]).longValue()).as("직접 기록 수").isEqualTo(1);

        assertThat(histories.countMyEatenByMenuSince(owner.getId(), since, RecommendationFeedback.ACCEPTED,
                org.springframework.data.domain.PageRequest.of(0, 5)))
                .extracting(HistoryRepository.LabelCount::getLabel).containsExactly("샐러드");

        // 최근 먹은 메뉴는 개인화 신호에 들어가야 한다 — 어제 먹은 샐러드를 오늘 또 권하지 않게.
        assertThat(histories.findMenuRecommendationSignalsSince(owner.getId(), since,
                RecommendationFeedback.ACCEPTED, RecommendationFeedback.REJECTED))
                .extracting(HistoryRepository.MenuRecommendationSignals::getMenuId)
                .containsExactlyInAnyOrder(pickMenu.getId(), logMenu.getId());

        // KPI는 다른 사용자의 행도 섞여 있어 절대값이 아니라 이 사용자 행의 기여만 본다.
        long picksBefore = ((Number) histories.countPicksAndVisitsSince(since).get(0)[0]).longValue();
        service.createManualMeal(owner.getId(), meal(logMenu, NOW.minusMinutes(30).withNano(0), null));
        long picksAfter = ((Number) histories.countPicksAndVisitsSince(since).get(0)[0]).longValue();
        assertThat(picksAfter).as("직접 기록은 픽 수에 들어가지 않는다").isEqualTo(picksBefore);
        assertThat(histories.findPickTimesForUsers(List.of(owner.getId()))).hasSize(1);
        assertThat(histories.findFirstPickPerUser()).filteredOn(row -> row[0].equals(owner.getId()))
                .singleElement().satisfies(row -> assertThat(row[1]).isEqualTo(NOW.minusHours(3)));
    }

    @Test void manualVisitTimeMovesRecommendedTimeAndCannotBeUnmarked() {
        User owner = user("visit-time");
        Menu menu = menu(owner, "라멘");
        var created = service.createManualMeal(owner.getId(), meal(menu, NOW.withNano(0), MealSlot.LUNCH));
        var visit = service.getVisitTime(owner.getId(), created.id());
        assertThat(visit.earliestVisitAt()).as("직접 기록은 추천일이 아니라 최근 1년이 하한")
                .isEqualTo(NOW.toLocalDate().minusDays(365).atStartOfDay());

        LocalDateTime earlier = NOW.minusDays(3).withHour(12).withMinute(10).withSecond(0).withNano(0);
        service.updateVisitTime(owner.getId(), created.id(),
                new HistoryRequest.VisitTimeRequest(earlier, visit.version()));
        History saved = histories.findById(created.id()).orElseThrow();
        assertThat(saved.getVisitedAt()).isEqualTo(earlier);
        assertThat(saved.getRecommendedAt()).isEqualTo(earlier);
        assertThat(saved.getMealSlot()).as("고른 끼니는 시각을 옮겨도 유지된다").isEqualTo(MealSlot.LUNCH);

        assertThatThrownBy(() -> service.unmarkVisited(owner.getId(), created.id()))
                .isInstanceOf(BusinessException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.MANUAL_HISTORY_ALWAYS_EATEN);
    }
}
