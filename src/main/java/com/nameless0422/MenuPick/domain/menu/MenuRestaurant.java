package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.domain.BaseTimeEntity;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu_restaurants")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuRestaurant extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restaurant_id", nullable = false)
    private Restaurant restaurant;

    private Integer rating;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @Builder
    public MenuRestaurant(Menu menu, Restaurant restaurant, Integer rating, String memo) {
        this.menu = menu;
        this.restaurant = restaurant;
        this.rating = rating;
        this.memo = memo;
    }

    public void update(Integer rating, String memo) {
        this.rating = rating;
        this.memo = memo;
    }
}
