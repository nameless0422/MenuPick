package com.nameless0422.MenuPick.domain.history;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "history_filter_conditions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HistoryFilterCondition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "history_id", nullable = false)
    private History history;

    @Column(nullable = false, length = 20)
    private String filterType;

    @Column(nullable = false, length = 100)
    private String filterValue;

    @Builder
    public HistoryFilterCondition(History history, String filterType, String filterValue) {
        this.history = history;
        this.filterType = filterType;
        this.filterValue = filterValue;
    }
}
