package com.nameless0422.MenuPick.domain.nutrition;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.history.MealSlot;
import com.nameless0422.MenuPick.domain.history.RecommendationFeedback;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 칼로리 추정(D-082). 기준 음식 검색, 메뉴 열량 설정, 기간 열량 요약.
 *
 * <p>이 서비스가 내놓는 숫자는 전부 추정치다. 메뉴 이름은 사용자가 짓고 식당마다 양이 다르므로,
 * 정확해 보이게 만드는 장치(소수점, 목표 대비 %)는 일부러 두지 않는다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NutritionService {

    static final int DEFAULT_DAYS = 7;
    static final int MAX_DAYS = 31;
    /** 요약이 읽는 끼니 수 상한. 하루 10끼 × 31일보다 넉넉하고, 픽 기록이 몰려도 한 번에 끝난다. */
    static final int MAX_ROWS = 2_000;

    private final FoodReferenceRepository foodReferences;
    private final MenuRepository menus;
    private final HistoryRepository histories;
    private final Clock clock;

    public List<NutritionDto.FoodReferenceSummary> search(String query, int size) {
        String exact = query == null ? "" : query.strip();
        if (exact.isEmpty()) {
            return List.of();
        }
        String pattern = exact.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return foodReferences.search(exact, pattern, size).stream()
                .map(NutritionDto.FoodReferenceSummary::of)
                .toList();
    }

    public NutritionDto.MenuNutrition getMenuNutrition(Long userId, Long menuId) {
        return toNutrition(findMenu(userId, menuId));
    }

    @Transactional
    public NutritionDto.MenuNutrition updateMenuNutrition(Long userId, Long menuId,
                                                          NutritionDto.MenuNutritionRequest request) {
        Menu menu = findMenu(userId, menuId);
        if (request.foodReferenceId() != null) {
            FoodReference reference = foodReferences.findById(request.foodReferenceId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.FOOD_REFERENCE_NOT_FOUND));
            menu.useFoodReference(reference.getId(), reference.getKcalPerServing());
        } else {
            menu.setCustomKcal(request.kcalPerServing());
        }
        // 응답 버전에 이번 쓰기를 반영한다 — 메뉴 편집 폼이 이 버전으로 다음 저장을 보낸다.
        menus.flush();
        return toNutrition(menu);
    }

    public NutritionDto.NutritionSummary summarize(Long userId, Integer requestedDays) {
        int days = requestedDays == null || requestedDays < 1 || requestedDays > MAX_DAYS
                ? DEFAULT_DAYS : requestedDays;
        LocalDate today = LocalDate.now(clock);
        LocalDate first = today.minusDays(days - 1L);
        // 기간은 달력 날짜다(오늘 포함 N일). "하루 합계"는 자정에 끊겨야 의미가 있다.
        LocalDateTime since = first.atStartOfDay();

        List<HistoryRepository.NutritionRow> rows = histories.findMyEatenNutritionSince(
                userId, since, RecommendationFeedback.ACCEPTED, PageRequest.of(0, MAX_ROWS + 1));
        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }

        Map<LocalDate, int[]> byDay = new LinkedHashMap<>();
        for (LocalDate day = first; !day.isAfter(today); day = day.plusDays(1)) {
            byDay.put(day, new int[3]); // kcal, 끼니 수, 열량 모르는 끼니 수
        }
        Map<MealSlot, int[]> bySlot = new EnumMap<>(MealSlot.class);
        for (MealSlot slot : MealSlot.values()) {
            bySlot.put(slot, new int[2]); // kcal, 열량을 아는 끼니 수
        }

        int total = 0;
        int known = 0;
        int unknown = 0;
        for (HistoryRepository.NutritionRow row : rows) {
            // 먹은 날로 센다. 뽑은 날은 기간 안인데 먹은 날이 밖이면(미래 시각 같은 이상값) 뽑은 날로 센다.
            int[] dayTotals = byDay.get(row.getEatenAt().toLocalDate());
            if (dayTotals == null) {
                dayTotals = byDay.get(row.getRecommendedAt().toLocalDate());
            }
            if (dayTotals == null) {
                continue;
            }
            dayTotals[1]++;
            Integer kcal = kcalOf(row);
            if (kcal == null) {
                dayTotals[2]++;
                unknown++;
                continue;
            }
            dayTotals[0] += kcal;
            MealSlot slot = row.getMealSlot() != null
                    ? row.getMealSlot() : MealSlot.at(row.getEatenAt().toLocalTime());
            bySlot.get(slot)[0] += kcal;
            bySlot.get(slot)[1]++;
            total += kcal;
            known++;
        }

        List<NutritionDto.DayNutrition> dayList = new ArrayList<>();
        int daysWithKnown = 0;
        for (Map.Entry<LocalDate, int[]> entry : byDay.entrySet()) {
            int[] v = entry.getValue();
            dayList.add(new NutritionDto.DayNutrition(entry.getKey(), v[0], v[1], v[2]));
            if (v[1] > v[2]) daysWithKnown++;
        }
        List<NutritionDto.SlotNutrition> slotList = bySlot.entrySet().stream()
                .map(entry -> new NutritionDto.SlotNutrition(entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
        // 기록이 없는 날을 0kcal로 넣으면 "적게 먹었다"는 틀린 이야기가 된다. 아는 끼니가 있던 날로만 나눈다.
        Integer average = daysWithKnown == 0 ? null : Math.round((float) total / daysWithKnown);
        return new NutritionDto.NutritionSummary(days, dayList, slotList, total, known, unknown, average, truncated);
    }

    private static Integer kcalOf(HistoryRepository.NutritionRow row) {
        if (row.getKcalPerServing() == null) return null;
        BigDecimal servings = row.getServings() != null ? row.getServings() : BigDecimal.ONE;
        return servings.multiply(BigDecimal.valueOf(row.getKcalPerServing()))
                .setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private Menu findMenu(Long userId, Long menuId) {
        return menus.findByIdAndUserIdAndDeletedAtIsNull(menuId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
    }

    private NutritionDto.MenuNutrition toNutrition(Menu menu) {
        NutritionDto.FoodReferenceSummary reference = menu.getFoodReferenceId() == null ? null
                : foodReferences.findById(menu.getFoodReferenceId())
                        .map(NutritionDto.FoodReferenceSummary::of).orElse(null);
        return new NutritionDto.MenuNutrition(menu.getId(), menu.getKcalPerServing(), reference, menu.getVersion());
    }
}
