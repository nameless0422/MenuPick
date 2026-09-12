package com.nameless0422.MenuPick.domain.trend.dto;
import com.nameless0422.MenuPick.domain.trend.PickTrend;
import java.time.LocalDateTime;
import java.util.List;
public record PickTrendResponse(List<Entry> categories, List<Entry> menus, Status status,
                                int windowDays, int minUsers, int maxLabels, LocalDateTime computedAt) {
    public enum Status { DISABLED, NOT_READY, STALE, READY }
    public record Entry(String label, int userCount, int rankOrder) {
        public static Entry from(PickTrend trend) {
            return new Entry(trend.getLabel(), trend.getUserCount(), trend.getRankOrder());
        }
    }
}
