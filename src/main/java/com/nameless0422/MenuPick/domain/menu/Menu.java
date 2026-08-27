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

    /**
     * 낙관적 락 버전 (issue #87, V9).
     *
     * <p>이 필드가 붙으면 UPDATE에 {@code WHERE version = ?}가 함께 나가고, 0행이 갱신되면
     * Hibernate가 예외를 던진다. 개인 데이터라 남의 변경을 잃을 일은 없지만 자기 자신과는
     * 겹친다 — 메뉴 편집(PUT)과 가중치 일괄 조정(PATCH /menus/weights)이 같은 행을 건드리고,
     * 후자는 한 트랜잭션에서 여러 행을 read-modify-write 한다.
     *
     * <p>{@code Long}이 아니라 primitive다. null 버전은 Hibernate에게 "아직 저장된 적 없는
     * 엔티티"라는 뜻이라, 이미 DB에 있는 행을 새 행으로 오인해 INSERT를 시도하게 된다.
     */
    @Version
    @Column(nullable = false)
    private long version;

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

    /** 삭제 시각은 서비스가 주입한다 — 엔티티는 Clock 빈을 주입받을 수 없다. */
    public void softDelete(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
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
