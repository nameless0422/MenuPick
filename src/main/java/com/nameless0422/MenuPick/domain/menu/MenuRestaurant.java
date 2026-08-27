package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.domain.BaseTimeEntity;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
// DDL은 Flyway가 관리하므로 동작 변화는 없다 — 스키마(V1 uq_menu_restaurant)와 엔티티의
// 불변식을 코드에서도 드러내기 위한 문서화 목적의 선언이다.
@Table(name = "menu_restaurants",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_menu_restaurant", columnNames = {"menu_id", "restaurant_id"}))
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

    /**
     * 낙관적 락 버전 (issue #87, V9). 근거는 {@code Menu.version} 주석과 V9 마이그레이션.
     *
     * <p>이 행의 수정은 {@code update(rating, memo)} 하나뿐인데 두 필드를 함께 교체한다 —
     * 별점만 고친 탭과 메모만 고친 탭이 겹치면 한쪽이 통째로 사라진다.
     */
    @Version
    @Column(nullable = false)
    private long version;

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
