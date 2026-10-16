package com.nameless0422.MenuPick.domain.history;

import java.time.Clock;
import java.time.LocalDateTime;

/** 목록과 내보내기의 기간·이름 검색 규칙을 함께 유지한다. */
record HistoryFilters(LocalDateTime after, String pattern) {
    static HistoryFilters of(Clock clock, Integer days, String keyword) {
        String search = keyword == null ? "" : keyword.trim();
        String pattern = search.isEmpty() ? null : "%" + search.replace("!", "!!")
                .replace("%", "!%").replace("_", "!_") + "%";
        return new HistoryFilters(LocalDateTime.now(clock).minusDays(days == null ? 7 : days), pattern);
    }
}
