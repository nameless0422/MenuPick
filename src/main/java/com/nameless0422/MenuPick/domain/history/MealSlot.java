package com.nameless0422.MenuPick.domain.history;

import java.time.LocalTime;

/** 끼니. 직접 기록은 사용자가 고르고, 픽 기록은 먹은(또는 뽑은) 시각으로 추정한다. */
public enum MealSlot {
    BREAKFAST,
    LUNCH,
    DINNER,
    SNACK;

    /**
     * 시각으로 끼니를 추정한다. 경계는 한국의 흔한 식사 시간대에 맞춘 근사다 —
     * 정확할 필요가 없는 값이라(사용자가 고르면 그게 이긴다) 단순하게 둔다.
     * 새벽(00:00~04:59)과 늦은 밤(22:00~)은 간식으로 본다.
     */
    public static MealSlot at(LocalTime time) {
        int hour = time.getHour();
        if (hour >= 5 && hour < 11) return BREAKFAST;
        if (hour >= 11 && hour < 16) return LUNCH;
        if (hour >= 16 && hour < 22) return DINNER;
        return SNACK;
    }
}
