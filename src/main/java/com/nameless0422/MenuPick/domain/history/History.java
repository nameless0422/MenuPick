package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

    /** 픽에서 생긴 기록인지, 직접 적은 끼니인지. 근거는 {@link HistorySource}. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private HistorySource source = HistorySource.PICK;

    /** 직접 기록에서 사용자가 고른 끼니. 픽 기록은 null이고 {@link #effectiveMealSlot()}이 시각으로 추정한다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private MealSlot mealSlot;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private RecommendationFeedback recommendationFeedback;

    @Column(nullable = false)
    private LocalDateTime recommendedAt;

    private LocalDateTime visitedAt;

    @Column(length = 500)
    private String memo;

    @Version
    @Column(nullable = false)
    private long version;

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
     * 추천 없이 먹은 끼니를 기록한다. 만들 때부터 먹은 것이다 — 뽑은 시각과 먹은 시각이 같다.
     *
     * <p>{@code recommendedAt}에도 먹은 시각을 넣는 이유: 기간 필터·목록 정렬·최근 제외가 전부
     * 이 컬럼을 기준으로 돈다. 따로 두면 "어제 먹은 것"이 그 쿼리들에서 빠진다.
     */
    public static History manual(User user, Menu menu, Restaurant restaurant,
                                 LocalDateTime eatenAt, MealSlot mealSlot, String memo) {
        History history = new History(user, menu, restaurant, Objects.requireNonNull(eatenAt));
        history.source = HistorySource.MANUAL;
        history.mealSlot = mealSlot != null ? mealSlot : MealSlot.at(eatenAt.toLocalTime());
        history.markVisited(eatenAt);
        history.memo = memo;
        return history;
    }

    public boolean isManual() {
        return source == HistorySource.MANUAL;
    }

    /** 고른 끼니가 있으면 그것, 없으면 먹은(먹기 전이면 뽑은) 시각으로 추정한 끼니. */
    public MealSlot effectiveMealSlot() {
        if (mealSlot != null) return mealSlot;
        LocalDateTime at = visitedAt != null ? visitedAt : recommendedAt;
        return MealSlot.at(at.toLocalTime());
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

    /** 방문 여부만 되돌린다. 고른 식당과 추천 수락 기록은 별개의 결정으로 남긴다. */
    public void unmarkVisited() {
        // 직접 기록은 "먹었다"는 사실 자체다. 방문을 취소하면 추천도 먹음도 아닌 행이 남는다 —
        // 잘못 적었으면 지우면 된다.
        if (isManual()) {
            throw new BusinessException(ErrorCode.MANUAL_HISTORY_ALWAYS_EATEN);
        }
        this.isVisited = false;
        this.visitedAt = null;
    }

    /**
     * 먹으러 갈 식당을 고른다. 식당을 적고 수락으로 표시하지만 <b>방문 처리는 하지 않는다</b> —
     * 아직 가기 전이다. 나중에 {@link #markVisited(LocalDateTime)}로 방문 처리해도 여기서 적은
     * 식당은 그대로 남는다.
     */
    public void choosePlace(Restaurant restaurant) {
        this.restaurant = Objects.requireNonNull(restaurant);
        this.recommendationFeedback = RecommendationFeedback.ACCEPTED;
    }

    public void recordFeedback(RecommendationFeedback feedback) {
        this.recommendationFeedback = Objects.requireNonNull(feedback);
    }

    public void updateMemo(String memo) {
        this.memo = memo;
    }

    /** 방문 여부와 식당·피드백·메모를 유지하고 방문 시각만 바꾼다. */
    public void changeVisitedAt(LocalDateTime visitedAt) {
        if (!isVisited || this.visitedAt == null) {
            throw new BusinessException(ErrorCode.HISTORY_NOT_VISITED);
        }
        this.visitedAt = Objects.requireNonNull(visitedAt);
        // 직접 기록은 뽑은 시각이 곧 먹은 시각이다(manual 참고). 같이 옮기지 않으면 기간 필터와
        // 최근 제외가 고치기 전 시각을 계속 본다.
        if (isManual()) {
            this.recommendedAt = this.visitedAt;
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
