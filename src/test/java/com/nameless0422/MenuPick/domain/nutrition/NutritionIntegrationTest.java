package com.nameless0422.MenuPick.domain.nutrition;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.history.HistoryService;
import com.nameless0422.MenuPick.domain.history.MealSlot;
import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;
import com.nameless0422.MenuPick.domain.history.dto.HistoryRequest;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/** 칼로리 추정(D-082)을 실제 MySQL로 확인한다 — 검색 순서, 메뉴 열량 복사, 기간 합계. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, NutritionService.class, HistoryService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NutritionIntegrationTest extends AbstractIntegrationTest {
    /** KST 2026-10-09(금) 20:00. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T11:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @MockitoBean private Clock clock;
    @Autowired private NutritionService service;
    @Autowired private HistoryService historyService;
    @Autowired private FoodReferenceRepository references;
    @Autowired private HistoryRepository histories;
    @Autowired private MenuRepository menus;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> fixtureUsers = new ArrayList<>();
    private final List<Long> fixtureReferences = new ArrayList<>();
    /** 다른 테스트의 기준 음식과 섞이지 않게 이름에 붙이는 고유 접두어. */
    private final String tag = "t" + UUID.randomUUID().toString().substring(0, 6);

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
            references.deleteAllById(fixtureReferences);
        });
    }

    private User user(String suffix) {
        User user = users.save(User.builder().email("nutrition-" + suffix + "-" + tag + "@test.com")
                .nickname("열량-" + suffix + tag).build());
        fixtureUsers.add(user.getId());
        return user;
    }

    private FoodReference reference(String name, int kcal) {
        FoodReference saved = references.save(FoodReference.of(tag + "-" + name, name, "찌개", new BigDecimal("400.0"), kcal, NOW));
        fixtureReferences.add(saved.getId());
        return saved;
    }

    private Menu menu(User owner, String name, Integer kcal) {
        Menu menu = Menu.builder().user(owner).name(name).build();
        if (kcal != null) menu.setCustomKcal(kcal);
        return menus.save(menu);
    }

    @Test void searchPutsExactThenPrefixThenShortestFirstAndEscapesWildcards() {
        String base = tag + "김치찌개";
        reference("돼지고기" + base, 300);
        reference(base + "정식", 700);
        reference(base, 200);
        reference(tag + "100%_주스", 120);
        reference(tag + "1000_주스", 130);

        assertThat(service.search(base, 10)).extracting(NutritionDto.FoodReferenceSummary::name)
                .containsExactly(base, base + "정식", "돼지고기" + base);
        assertThat(service.search(tag + "100%_", 10)).extracting(NutritionDto.FoodReferenceSummary::name)
                .as("%와 _는 와일드카드가 아니라 글자다").containsExactly(tag + "100%_주스");
        assertThat(service.search("   ", 10)).isEmpty();
        assertThat(service.search(base, 1)).hasSize(1);
    }

    @Test void choosingReferenceCopiesItsKcalAndCustomOrClearDropsTheLink() {
        User owner = user("menu");
        Menu menu = menu(owner, "김치찌개", null);
        FoodReference kimchi = reference("김치찌개", 196);
        long before = menu.getVersion();

        var linked = service.updateMenuNutrition(owner.getId(), menu.getId(),
                new NutritionDto.MenuNutritionRequest(kimchi.getId(), 9999));
        assertThat(linked.kcalPerServing()).as("기준 음식이 있으면 적은 값은 무시한다").isEqualTo(196);
        assertThat(linked.foodReference().name()).isEqualTo("김치찌개");
        assertThat(linked.version()).as("응답 버전에 이번 쓰기가 반영된다").isGreaterThan(before);

        var custom = service.updateMenuNutrition(owner.getId(), menu.getId(),
                new NutritionDto.MenuNutritionRequest(null, 550));
        assertThat(custom.kcalPerServing()).isEqualTo(550);
        assertThat(custom.foodReference()).isNull();

        var cleared = service.updateMenuNutrition(owner.getId(), menu.getId(),
                new NutritionDto.MenuNutritionRequest(null, null));
        assertThat(cleared.kcalPerServing()).isNull();
        assertThat(service.getMenuNutrition(owner.getId(), menu.getId()).kcalPerServing()).isNull();
    }

    @Test void othersMenusAndUnknownReferencesAreRejected() {
        User owner = user("owner");
        User other = user("other");
        Menu theirs = menu(other, "냉면", null);
        Menu mine = menu(owner, "비빔밥", null);
        assertThatThrownBy(() -> service.updateMenuNutrition(owner.getId(), theirs.getId(),
                new NutritionDto.MenuNutritionRequest(null, 500)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
        assertThatThrownBy(() -> service.getMenuNutrition(owner.getId(), theirs.getId()))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
        assertThatThrownBy(() -> service.updateMenuNutrition(owner.getId(), mine.getId(),
                new NutritionDto.MenuNutritionRequest(Long.MAX_VALUE, null)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.FOOD_REFERENCE_NOT_FOUND);
    }

    @Test void summaryAddsServingsTimesKcalPerDayAndSlotAndCountsUnknownMeals() {
        User owner = user("summary");
        User other = user("summary-other");
        Menu stew = menu(owner, "김치찌개", 500);
        Menu salad = menu(owner, "샐러드", 300);
        Menu mystery = menu(owner, "이름 모를 것", null);
        Menu othersMenu = menu(other, "남의 것", 9000);

        // 오늘: 점심 김치찌개 1.5인분(750), 저녁 이름 모를 것(모름)
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                stew.getId(), TODAY.atTime(12, 0), null, null, null, new BigDecimal("1.5")));
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                mystery.getId(), TODAY.atTime(19, 0), null, null, null, null));
        // 2일 전: 픽 → 방문한 샐러드(300, 저녁으로 추정), 뽑기만 한 김치찌개(안 먹음 → 빠짐)
        History visited = History.builder().user(owner).menu(salad).recommendedAt(TODAY.minusDays(2).atTime(18, 0)).build();
        visited.markVisited(TODAY.minusDays(2).atTime(18, 30));
        histories.save(visited);
        histories.save(History.builder().user(owner).menu(stew).recommendedAt(TODAY.minusDays(2).atTime(12, 0)).build());
        // 2일 전 점심: 수락만 한 샐러드(300, 먹은 것으로 센다)
        History accepted = History.builder().user(owner).menu(salad).recommendedAt(TODAY.minusDays(2).atTime(12, 30)).build();
        accepted.recordFeedback(RecommendationFeedback.ACCEPTED);
        histories.save(accepted);
        // 기간 밖(7일 전) — 빠진다
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                stew.getId(), TODAY.minusDays(7).atTime(12, 0), null, null, null, null));
        // 남의 기록 — 빠진다
        historyService.createManualMeal(other.getId(), new HistoryRequest.ManualMealRequest(
                othersMenu.getId(), TODAY.atTime(12, 0), null, null, null, null));

        var summary = service.summarize(owner.getId(), 7);
        assertThat(summary.periodDays()).isEqualTo(7);
        assertThat(summary.days()).hasSize(7);
        assertThat(summary.days().get(0).date()).isEqualTo(TODAY.minusDays(6));
        assertThat(summary.days().get(6)).extracting("date", "kcal", "meals", "unknownMeals")
                .containsExactly(TODAY, 750, 2, 1);
        assertThat(summary.days().get(4)).extracting("date", "kcal", "meals", "unknownMeals")
                .containsExactly(TODAY.minusDays(2), 600, 2, 0);
        assertThat(summary.days().get(6).slots()).as("모르는 저녁은 어느 끼니에도 안 들어간다")
                .extracting(NutritionDto.SlotNutrition::kcal).containsExactly(0, 750, 0, 0);
        assertThat(summary.days().get(4).slots())
                .extracting(NutritionDto.SlotNutrition::kcal).containsExactly(0, 300, 300, 0);
        assertThat(summary.days().get(0).slots()).as("기록 없는 날도 네 끼니가 온다")
                .extracting(NutritionDto.SlotNutrition::meals).containsExactly(0, 0, 0, 0);
        assertThat(summary.totalKcal()).isEqualTo(1350);
        assertThat(summary.knownMeals()).isEqualTo(3);
        assertThat(summary.unknownMeals()).isEqualTo(1);
        assertThat(summary.averageKcal()).as("아는 끼니가 있던 이틀로만 나눈다").isEqualTo(675);
        assertThat(summary.slots()).extracting(NutritionDto.SlotNutrition::slot, NutritionDto.SlotNutrition::kcal)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(MealSlot.BREAKFAST, 0),
                        org.assertj.core.groups.Tuple.tuple(MealSlot.LUNCH, 1050),
                        org.assertj.core.groups.Tuple.tuple(MealSlot.DINNER, 300),
                        org.assertj.core.groups.Tuple.tuple(MealSlot.SNACK, 0));
        assertThat(summary.truncated()).isFalse();

        assertThat(service.summarize(owner.getId(), 99).periodDays()).as("범위 밖은 7일").isEqualTo(7);
        assertThat(service.summarize(owner.getId(), 1).days()).singleElement()
                .extracting(NutritionDto.DayNutrition::date).isEqualTo(TODAY);
    }

    @Test void emptyPeriodHasNoAverageAndHistoryRowsCarryKcalAndServings() {
        User owner = user("empty");
        assertThat(service.summarize(owner.getId(), null).averageKcal()).isNull();

        Menu stew = menu(owner, "김치찌개", 333);
        var created = historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                stew.getId(), NOW.withNano(0), null, null, null, new BigDecimal("0.5")));
        assertThat(created.servings()).isEqualByComparingTo("0.5");
        assertThat(created.kcal()).as("반올림").isEqualTo(167);

        assertThatThrownBy(() -> historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                stew.getId(), NOW.withNano(0), null, null, null, new BigDecimal("0.75"))))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // 메뉴 열량을 모르면 null이지 0이 아니다.
        stew.setCustomKcal(null);
        menus.save(stew);
        assertThat(historyService.getHistories(owner.getId(), null, 7, null, 20).histories())
                .singleElement().satisfies(row -> assertThat(row.kcal()).isNull());
    }
}
