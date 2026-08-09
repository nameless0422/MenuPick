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

    public void markVisited() {
        this.isVisited = true;
        this.visitedAt = LocalDateTime.now();
    }

    public void markVisited(Restaurant restaurant) {
        markVisited();
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
