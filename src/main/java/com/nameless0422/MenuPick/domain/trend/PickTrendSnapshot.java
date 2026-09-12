package com.nameless0422.MenuPick.domain.trend;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/** 행이 0개인 성공 집계도 표현하는 singleton 스냅샷 메타데이터. */
@Entity @Table(name = "pick_trend_snapshots") @Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickTrendSnapshot {
    public static final long ID = 1L;
    @Id private Long id;
    private LocalDateTime computedAt;
    private int windowDays;
    private int minUsers;
    private int maxLabels;
    public void complete(LocalDateTime at, PickTrendProperties properties) {
        computedAt = at; windowDays = properties.windowDays();
        minUsers = properties.minUsers(); maxLabels = properties.maxLabels();
    }
}
