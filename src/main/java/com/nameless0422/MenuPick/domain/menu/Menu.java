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
    private int weight = 1;

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

    @OneToMany(mappedBy = "menu")
    private List<MenuRestaurant> menuRestaurants = new ArrayList<>();

    @Builder
    public Menu(User user, String name, String memo, int weight) {
        this.user = user;
        this.name = name;
        this.memo = memo;
        this.weight = weight;
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
