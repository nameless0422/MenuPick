package com.nameless0422.MenuPick.domain.history;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class MealSlotTest {
    @ParameterizedTest
    @CsvSource({
            "00:00, SNACK", "04:59, SNACK",
            "05:00, BREAKFAST", "10:59, BREAKFAST",
            "11:00, LUNCH", "15:59, LUNCH",
            "16:00, DINNER", "21:59, DINNER",
            "22:00, SNACK", "23:59, SNACK"
    })
    void estimatesSlotFromTimeAtTheBoundaries(LocalTime time, MealSlot expected) {
        assertThat(MealSlot.at(time)).isEqualTo(expected);
    }
}
