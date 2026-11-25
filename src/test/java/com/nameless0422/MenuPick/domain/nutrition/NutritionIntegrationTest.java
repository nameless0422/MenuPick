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
import com.nameless0422.MenuPick.domain.menu.Macros;
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

    @Test void kcalReferenceStartsEmptyIsSavedAndClearedAndRidesOnTheSummary() {
        User owner = user("ref");
        assertThat(service.getKcalReference(owner.getId()).dailyKcal()).isNull();
        assertThat(service.summarize(owner.getId(), 7).dailyKcalReference()).isNull();

        service.updateKcalReference(owner.getId(), new NutritionDto.KcalReference(2000));
        assertThat(service.getKcalReference(owner.getId()).dailyKcal()).isEqualTo(2000);
        assertThat(service.summarize(owner.getId(), 7).dailyKcalReference()).isEqualTo(2000);

        service.updateKcalReference(owner.getId(), new NutritionDto.KcalReference(null));
        assertThat(service.summarize(owner.getId(), 7).dailyKcalReference()).isNull();
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

    @Test void sameNameRanksRestaurantBeforeSchoolMealsAndCollapsesIdenticalRows() {
        String name = tag + "된장찌개";
        saveFixture(FoodReference.of("D5" + tag + "-a", name, "찌개", new BigDecimal("200.0"), 38, NOW).withServingUnit("ml"));
        FoodReference canteen = saveFixture(FoodReference.of("D7" + tag + "-a", name, "찌개", new BigDecimal("200.0"), 38, NOW)
                .withServingUnit("ml"));
        FoodReference home = saveFixture(FoodReference.of("D1" + tag + "-a", name, "찌개", new BigDecimal("200.0"), 92, NOW));
        FoodReference restaurant = saveFixture(FoodReference.of("D3" + tag + "-a", name, "찌개", new BigDecimal("400.0"), 244, NOW));

        List<NutritionDto.FoodReferenceSummary> found = service.search(name, 10);

        assertThat(found).extracting(NutritionDto.FoodReferenceSummary::id)
                .as("외식 → 가정식 → 급식, 값이 같은 급식 두 줄은 하나로")
                .containsExactly(restaurant.getId(), home.getId(), canteen.getId());
        assertThat(found.get(0).origin()).isEqualTo("외식");
        assertThat(found.get(2).origin()).isEqualTo("단체 급식");
        assertThat(found.get(2).servingUnit()).isEqualTo("ml");
        assertThat(service.search(name, 2)).hasSize(2);
    }

    private FoodReference saveFixture(FoodReference reference) {
        FoodReference saved = references.save(reference);
        fixtureReferences.add(saved.getId());
        return saved;
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

    private static BigDecimal g(String value) {
        return new BigDecimal(value);
    }

    private Menu menuWithMacros(User owner, String name, int kcal, String carbs, String protein, String fat, String fiber) {
        Menu menu = Menu.builder().user(owner).name(name).build();
        menu.setCustomNutrition(kcal, new Macros(carbs == null ? null : g(carbs), protein == null ? null : g(protein),
                fat == null ? null : g(fat), fiber == null ? null : g(fiber)));
        return menus.save(menu);
    }

    @Test void choosingReferenceCopiesMacrosAndCustomMacrosAreAllOrNothing() {
        User owner = user("macros");
        Menu menu = menu(owner, "김치찌개", null);
        FoodReference kimchi = references.save(FoodReference.of(tag + "-macro", "김치찌개", "찌개", g("400.0"), 196, NOW)
                .withMacros(g("12.8"), g("14.0"), g("10.4"), g("4.4")));
        fixtureReferences.add(kimchi.getId());

        var linked = service.updateMenuNutrition(owner.getId(), menu.getId(),
                new NutritionDto.MenuNutritionRequest(kimchi.getId(), null, g("1.0"), g("1.0"), g("1.0"), null));
        // 기준 음식이 있으면 적은 영양소는 무시하고 기준 값을 복사한다.
        assertThat(linked.carbsG()).isEqualByComparingTo("12.8");
        assertThat(linked.proteinG()).isEqualByComparingTo("14.0");
        assertThat(linked.fatG()).isEqualByComparingTo("10.4");
        assertThat(linked.fiberG()).isEqualByComparingTo("4.4");
        assertThat(linked.foodReference().fiberG()).isEqualByComparingTo("4.4");

        var custom = service.updateMenuNutrition(owner.getId(), menu.getId(),
                new NutritionDto.MenuNutritionRequest(null, 600, g("80"), g("20.5"), g("15"), null));
        assertThat(custom.carbsG()).isEqualByComparingTo("80");
        assertThat(custom.fiberG()).as("식이섬유는 따로 비워 둘 수 있다").isNull();

        assertThatThrownBy(() -> service.updateMenuNutrition(owner.getId(), menu.getId(),
                new NutritionDto.MenuNutritionRequest(null, 600, g("80"), null, null, null)))
                .as("탄단지 하나만은 안 된다")
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
        assertThatThrownBy(() -> service.updateMenuNutrition(owner.getId(), menu.getId(),
                new NutritionDto.MenuNutritionRequest(null, null, null, null, null, g("3"))))
                .as("열량 없이 영양소만은 안 된다")
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        var kcalOnly = service.updateMenuNutrition(owner.getId(), menu.getId(), new NutritionDto.MenuNutritionRequest(null, 500));
        assertThat(kcalOnly.carbsG()).as("열량만 다시 적으면 이전 영양소는 남지 않는다").isNull();
    }

    @Test void summaryAveragesMacrosPerDayOverDaysWhereTheyAreKnown() {
        User owner = user("macro-summary");
        Menu stew = menuWithMacros(owner, "김치찌개", 500, "60", "30", "15", "5");
        Menu salad = menuWithMacros(owner, "샐러드", 300, "20", "10", "12", null);
        Menu kcalOnly = menu(owner, "열량만", 400);

        // 오늘: 김치찌개 1.5인분 + 샐러드 1인분 + 열량만 아는 메뉴
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                stew.getId(), TODAY.atTime(12, 0), null, null, null, g("1.5")));
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                salad.getId(), TODAY.atTime(19, 0), null, null, null, null));
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                kcalOnly.getId(), TODAY.atTime(8, 0), null, null, null, null));
        // 3일 전: 샐러드만
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                salad.getId(), TODAY.minusDays(3).atTime(12, 0), null, null, null, null));
        // 2일 전: 열량만 아는 메뉴만 — 탄단지 평균의 분모에 들어가지 않는다
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                kcalOnly.getId(), TODAY.minusDays(2).atTime(12, 0), null, null, null, null));

        var macros = service.summarize(owner.getId(), 7).macros();
        assertThat(macros.meals()).isEqualTo(3);
        // 탄수화물: 오늘 90 + 20 = 110, 3일 전 20 → (110 + 20) / 2일 = 65
        assertThat(macros.carbsG()).isEqualTo(65);
        // 단백질: 오늘 45 + 10 = 55, 3일 전 10 → 32.5 → 33(반올림)
        assertThat(macros.proteinG()).isEqualTo(33);
        // 지방: 오늘 22.5 + 12 = 34.5, 3일 전 12 → 23.25 → 23
        assertThat(macros.fatG()).isEqualTo(23);
        // 식이섬유는 김치찌개만 안다: 7.5g, 하루 → 8(반올림)
        assertThat(macros.fiberMeals()).isEqualTo(1);
        assertThat(macros.fiberG()).isEqualTo(8);

        User empty = user("macro-empty");
        assertThat(service.summarize(empty.getId(), 7).macros()).isEqualTo(NutritionDto.MacroSummary.EMPTY);
    }

    @Test void sugarsAndSodiumAreCopiedAveragedAndDayAndMealMacrosAreReported() {
        User owner = user("sodium");
        FoodReference jjamppong = references.save(FoodReference.of(tag + "-sodium", "짬뽕", "면류", g("900.0"), 720, NOW)
                .withMacros(g("90.0"), g("36.0"), g("22.5"), null).withSugarsAndSodium(g("13.5"), 3780));
        fixtureReferences.add(jjamppong.getId());
        Menu noodle = menu(owner, "짬뽕", null);
        var linked = service.updateMenuNutrition(owner.getId(), noodle.getId(),
                new NutritionDto.MenuNutritionRequest(jjamppong.getId(), null));
        assertThat(linked.sugarsG()).isEqualByComparingTo("13.5");
        assertThat(linked.sodiumMg()).isEqualTo(3780);
        assertThat(linked.foodReference().sodiumMg()).isEqualTo(3780);

        // 직접 적을 때는 당류·나트륨만 따로 적을 수 있고, 열량 없이는 안 된다.
        Menu cider = menu(owner, "사이다", null);
        var custom = service.updateMenuNutrition(owner.getId(), cider.getId(),
                new NutritionDto.MenuNutritionRequest(null, 100, null, null, null, null, g("24.5"), 15));
        assertThat(custom.sugarsG()).isEqualByComparingTo("24.5");
        assertThat(custom.carbsG()).isNull();
        assertThatThrownBy(() -> service.updateMenuNutrition(owner.getId(), cider.getId(),
                new NutritionDto.MenuNutritionRequest(null, null, null, null, null, null, null, 15)))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        // 오늘: 짬뽕 0.5인분 + 사이다, 어제: 사이다
        var meal = historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                noodle.getId(), TODAY.atTime(12, 0), null, null, null, g("0.5")));
        assertThat(meal.macros()).as("기록 한 줄의 탄단지는 먹은 양을 곱해 반올림한다")
                .isEqualTo(new com.nameless0422.MenuPick.domain.history.dto.HistoryResponse.MealMacros(45, 18, 11));
        var ciderMeal = historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                cider.getId(), TODAY.atTime(15, 0), null, null, null, null));
        assertThat(ciderMeal.macros()).as("탄단지를 모르면 null").isNull();
        historyService.createManualMeal(owner.getId(), new HistoryRequest.ManualMealRequest(
                cider.getId(), TODAY.minusDays(1).atTime(15, 0), null, null, null, null));

        var summary = service.summarize(owner.getId(), 7);
        var macros = summary.macros();
        // 당류: 오늘 6.75 + 24.5 = 31.25, 어제 24.5 → 55.75 / 2일 = 27.875 → 28
        assertThat(macros.sugarsMeals()).isEqualTo(3);
        assertThat(macros.sugarsG()).isEqualTo(28);
        // 나트륨: 오늘 1890 + 15, 어제 15 → 1920 / 2 = 960
        assertThat(macros.sodiumMeals()).isEqualTo(3);
        assertThat(macros.sodiumMg()).isEqualTo(960);

        assertThat(summary.days().get(6).macros()).as("오늘 탄단지 합계, 아는 끼니만")
                .isEqualTo(new NutritionDto.DayMacros(1, 45, 18, 11));
        assertThat(summary.days().get(5).macros()).as("탄단지를 아는 끼니가 없던 날")
                .isEqualTo(NutritionDto.DayMacros.EMPTY);
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
