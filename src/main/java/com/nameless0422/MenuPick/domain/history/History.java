package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class History {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id")
    private Menu menu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id")
    private Restaurant restaurant;

    @Column(nullable = false)
    private boolean isVisited;

    @Column(nullable = false)
    private LocalDateTime recommendedAt;

    private LocalDateTime visitedAt;

    @OneToMany(mappedBy = "history", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HistoryFilterCondition> filterConditions = new ArrayList<>();

    @Builder
    public History(User user, Menu menu, Restaurant restaurant, LocalDateTime recommendedAt) {
        this.user = user;
        this.menu = menu;
        this.restaurant = restaurant;
        this.recommendedAt = recommendedAt;
    }

    /**
     * 엔티티는 빈(Clock) 주입이 불가능하므로 현재 시각을 직접 만들지 않고
     * 서비스에서 주입받는다(시간대 고정·테스트 가능성 확보).
     */
    public void markVisited(LocalDateTime visitedAt) {
        this.isVisited = true;
        this.visitedAt = visitedAt;
    }

    public void markVisited(Restaurant restaurant, LocalDateTime visitedAt) {
        markVisited(visitedAt);
        if (restaurant != null) {
            this.restaurant = restaurant;
        }
    }

    public void addFilterCondition(String filterType, String filterValue) {
        this.filterConditions.add(
                HistoryFilterCondition.builder()
                        .history(this)
                        .filterType(filterType)
                        .filterValue(filterValue)
                        .build()
        );
    }
}
