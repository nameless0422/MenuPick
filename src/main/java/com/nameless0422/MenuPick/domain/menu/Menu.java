package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.domain.BaseTimeEntity;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "menus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Menu extends BaseTimeEntity {

    private static final int DEFAULT_WEIGHT = 1;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String memo;

    @Column(nullable = false)
    private boolean isExcluded;

    @Column(nullable = false)
    private int weight = DEFAULT_WEIGHT;

    private LocalDateTime deletedAt;

    @ElementCollection
    @CollectionTable(name = "menu_categories",
            joinColumns = @JoinColumn(name = "menu_id"))
    @Column(name = "category", length = 20)
    private Set<String> categories = new HashSet<>();

    @ManyToMany
    @JoinTable(name = "menu_tags",
            joinColumns = @JoinColumn(name = "menu_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    /**
     * 읽기 전용 매핑 — 픽 시 연결 식당/거리를 조회하는 용도로만 사용한다(PickService).
     * 연결의 저장·수정·삭제는 이 컬렉션이 아니라 MenuRestaurantRepository를 직접 사용한다
     * (cascade/orphanRemoval 없음).
     */
    @OneToMany(mappedBy = "menu")
    private List<MenuRestaurant> menuRestaurants = new ArrayList<>();

    /**
     * {@code @Builder}가 클래스가 아니라 생성자에 붙어 있어 Lombok의 {@code @Builder.Default}를
     * 쓸 수 없다. weight를 지정하지 않고 build()하면 primitive 기본값 0이 필드 초기값 1을
     * 덮어쓰므로, Integer로 받아 null일 때 1로 보정한다.
     */
    @Builder
    public Menu(User user, String name, String memo, Integer weight) {
        this.user = user;
        this.name = name;
        this.memo = memo;
        this.weight = (weight != null) ? weight : DEFAULT_WEIGHT;
    }

    public void update(String name, String memo, int weight) {
        this.name = name;
        this.memo = memo;
        this.weight = weight;
    }

    public void updateWeight(int weight) {
        this.weight = weight;
    }

    public void exclude() {
        this.isExcluded = true;
    }

    public void include() {
        this.isExcluded = false;
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void addCategory(String category) {
        this.categories.add(category);
    }

    public void removeCategory(String category) {
        this.categories.remove(category);
    }

    public void addTag(Tag tag) {
        this.tags.add(tag);
    }

    public void removeTag(Tag tag) {
        this.tags.remove(tag);
    }
}
