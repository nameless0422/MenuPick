package com.nameless0422.MenuPick.domain.nutrition;

import com.nameless0422.MenuPick.domain.history.MealSlot;
import com.nameless0422.MenuPick.domain.menu.Macros;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class NutritionDto {
    private NutritionDto() {}

    public record FoodReferenceSummary(
            Long id, String name, String category, BigDecimal servingGrams, int kcalPerServing,
            BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG,
            BigDecimal sugarsG, Integer sodiumMg) {
        public FoodReferenceSummary(Long id, String name, String category, BigDecimal servingGrams, int kcalPerServing,
                                    BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG) {
            this(id, name, category, servingGrams, kcalPerServing, carbsG, proteinG, fatG, null);
        }

        public FoodReferenceSummary(Long id, String name, String category, BigDecimal servingGrams, int kcalPerServing,
                                    BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG) {
            this(id, name, category, servingGrams, kcalPerServing, carbsG, proteinG, fatG, fiberG, null, null);
        }

        static FoodReferenceSummary of(FoodReference reference) {
            return new FoodReferenceSummary(reference.getId(), reference.getName(), reference.getCategory(),
                    reference.getServingGrams(), reference.getKcalPerServing(),
                    reference.getCarbsG(), reference.getProteinG(), reference.getFatG(), reference.getFiberG(),
                    reference.getSugarsG(), reference.getSodiumMg());
        }
    }

    /**
     * 메뉴 열량 설정. {@code foodReferenceId}가 있으면 그 음식의 값을 쓰고 {@code kcalPerServing}은 무시한다.
     * 둘 다 비우면 열량을 지운다(모름).
     *
     * <p>직접 적을 때 탄단지(g)를 함께 줄 수 있다. 셋은 함께 주거나 함께 비운다 — 하나만 있으면 비율을
     * 그릴 수 없다. 식이섬유는 따로 비워 둘 수 있다. 기준 음식을 고르면 이 값들은 무시한다(D-084).
     */
    public record MenuNutritionRequest(
            Long foodReferenceId,
            @Min(value = 1, message = "열량은 1kcal 이상이어야 합니다.")
            @Max(value = 5000, message = "1인분 열량은 5,000kcal 이하로 적어주세요.")
            Integer kcalPerServing,
            @DecimalMin(value = "0.0", message = "탄수화물은 0g 이상이어야 합니다.")
            @DecimalMax(value = "999.9", message = "탄수화물은 999.9g 이하로 적어주세요.")
            @Digits(integer = 3, fraction = 1, message = "탄수화물은 소수점 한 자리까지 적어주세요.")
            BigDecimal carbsG,
            @DecimalMin(value = "0.0", message = "단백질은 0g 이상이어야 합니다.")
            @DecimalMax(value = "999.9", message = "단백질은 999.9g 이하로 적어주세요.")
            @Digits(integer = 3, fraction = 1, message = "단백질은 소수점 한 자리까지 적어주세요.")
            BigDecimal proteinG,
            @DecimalMin(value = "0.0", message = "지방은 0g 이상이어야 합니다.")
            @DecimalMax(value = "999.9", message = "지방은 999.9g 이하로 적어주세요.")
            @Digits(integer = 3, fraction = 1, message = "지방은 소수점 한 자리까지 적어주세요.")
            BigDecimal fatG,
            @DecimalMin(value = "0.0", message = "식이섬유는 0g 이상이어야 합니다.")
            @DecimalMax(value = "999.9", message = "식이섬유는 999.9g 이하로 적어주세요.")
            @Digits(integer = 3, fraction = 1, message = "식이섬유는 소수점 한 자리까지 적어주세요.")
            BigDecimal fiberG,
            @DecimalMin(value = "0.0", message = "당류는 0g 이상이어야 합니다.")
            @DecimalMax(value = "999.9", message = "당류는 999.9g 이하로 적어주세요.")
            @Digits(integer = 3, fraction = 1, message = "당류는 소수점 한 자리까지 적어주세요.")
            BigDecimal sugarsG,
            // 짬뽕 한 그릇이 4,000mg 안팎이다. 1인분으로 20,000mg을 넘으면 단위를 잘못 적은 것이다.
            @Min(value = 0, message = "나트륨은 0mg 이상이어야 합니다.")
            @Max(value = 20000, message = "1인분 나트륨은 20,000mg 이하로 적어주세요.")
            Integer sodiumMg
    ) {
        public MenuNutritionRequest(Long foodReferenceId, Integer kcalPerServing) {
            this(foodReferenceId, kcalPerServing, null, null, null, null);
        }

        public MenuNutritionRequest(Long foodReferenceId, Integer kcalPerServing,
                                    BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG) {
            this(foodReferenceId, kcalPerServing, carbsG, proteinG, fatG, fiberG, null, null);
        }

        Macros macros() {
            return new Macros(carbsG, proteinG, fatG, fiberG, sugarsG, sodiumMg);
        }
    }

    /**
     * 메뉴의 열량 설정. {@code foodReference}가 null이고 {@code kcalPerServing}이 있으면 직접 적은 값이다.
     * {@code version}은 메뉴의 낙관적 락 버전 — 열량을 바꾸면 메뉴 버전도 오른다.
     */
    public record MenuNutrition(Long menuId, Integer kcalPerServing, FoodReferenceSummary foodReference, long version,
                                BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG,
                                BigDecimal sugarsG, Integer sodiumMg) {
        public MenuNutrition(Long menuId, Integer kcalPerServing, FoodReferenceSummary foodReference, long version) {
            this(menuId, kcalPerServing, foodReference, version, null, null, null, null);
        }

        public MenuNutrition(Long menuId, Integer kcalPerServing, FoodReferenceSummary foodReference, long version,
                             BigDecimal carbsG, BigDecimal proteinG, BigDecimal fatG, BigDecimal fiberG) {
            this(menuId, kcalPerServing, foodReference, version, carbsG, proteinG, fatG, fiberG, null, null);
        }
    }

    /**
     * 하루치. {@code unknownMeals}는 메뉴 열량을 몰라 합계에서 빠진 끼니 수다.
     * {@code slots}는 그날 열량을 끼니별로 나눈 것으로, 늘 네 끼니가 {@link MealSlot} 순서로 온다
     * (그래프가 막대를 끼니별로 쌓는다). 열량을 모르는 끼니는 어느 끼니에도 들어가지 않는다.
     * {@code macros}는 그날 탄단지 합계다(날짜별 탄단지 그래프, D-085).
     */
    public record DayNutrition(LocalDate date, int kcal, int meals, int unknownMeals, List<SlotNutrition> slots,
                               DayMacros macros) {
        public DayNutrition(LocalDate date, int kcal, int meals, int unknownMeals) {
            this(date, kcal, meals, unknownMeals, List.of());
        }

        public DayNutrition(LocalDate date, int kcal, int meals, int unknownMeals, List<SlotNutrition> slots) {
            this(date, kcal, meals, unknownMeals, slots, DayMacros.EMPTY);
        }
    }

    /**
     * 하루 탄단지 합계(g, 반올림한 정수). {@code meals}는 그날 탄단지를 아는 끼니 수다. 0이면 셋 다 null —
     * 그날 막대를 그리지 않는다(0g으로 보이면 "안 먹었다"가 된다).
     */
    public record DayMacros(int meals, Integer carbsG, Integer proteinG, Integer fatG) {
        public static final DayMacros EMPTY = new DayMacros(0, null, null, null);
    }

    public record SlotNutrition(MealSlot slot, int kcal, int meals) {}

    /**
     * 기간 열량 요약. 전부 <b>추정치</b>다 — 식당마다 양이 달라서 정확한 숫자인 척하지 않는다.
     *
     * <p>{@code averageKcal}은 열량을 아는 끼니가 하루라도 있었던 날만으로 나눈다. 기록이 없는 날을
     * 0kcal로 넣으면 "적게 먹었다"는 틀린 이야기가 된다.
     */
    public record NutritionSummary(
            int periodDays, List<DayNutrition> days, List<SlotNutrition> slots,
            int totalKcal, int knownMeals, int unknownMeals, Integer averageKcal, boolean truncated,
            MacroSummary macros) {
        public NutritionSummary(int periodDays, List<DayNutrition> days, List<SlotNutrition> slots,
                                int totalKcal, int knownMeals, int unknownMeals, Integer averageKcal, boolean truncated) {
            this(periodDays, days, slots, totalKcal, knownMeals, unknownMeals, averageKcal, truncated, MacroSummary.EMPTY);
        }
    }

    /**
     * 기간 탄단지·식이섬유 <b>하루 평균</b>(g, 반올림한 정수). 열량 평균과 같이, 그 영양소를 아는 끼니가 있던
     * 날로만 나눈다. {@code meals}는 탄단지를 아는 끼니 수, {@code fiberMeals}는 식이섬유를 아는 끼니 수다.
     * 모르면 평균은 null이다(D-084). 당류(g)·나트륨(mg)도 식이섬유처럼 하나씩 따로 센다(D-085).
     */
    public record MacroSummary(int meals, Integer carbsG, Integer proteinG, Integer fatG,
                               int fiberMeals, Integer fiberG,
                               int sugarsMeals, Integer sugarsG, int sodiumMeals, Integer sodiumMg) {
        public static final MacroSummary EMPTY = new MacroSummary(0, null, null, null, 0, null, 0, null, 0, null);

        public MacroSummary(int meals, Integer carbsG, Integer proteinG, Integer fatG, int fiberMeals, Integer fiberG) {
            this(meals, carbsG, proteinG, fatG, fiberMeals, fiberG, 0, null, 0, null);
        }
    }
}
