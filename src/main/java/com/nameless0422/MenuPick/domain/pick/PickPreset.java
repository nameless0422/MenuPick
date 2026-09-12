package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.domain.BaseTimeEntity;
import com.nameless0422.MenuPick.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 이름 붙여 저장한 픽 조건 — 상황별 빠른 픽({@code docs/PickPresetDesign.md}).
 *
 * <h2>저장하지 않는 두 가지</h2>
 *
 * <p><b>좌표를 담지 않는다.</b> 거리 조건은 "몇 m 이내"만 저장하고 기준점은 실행할 때마다
 * 브라우저에서 새로 받는다. 좌표를 굳히면 회사에서 만든 "회사 점심"을 집에서 실행했을 때
 * 조용히 회사 주변을 뽑는다.
 *
 * <p><b>기본 제외 태그를 복사하지 않는다.</b> {@link #excludeTagIds}는 <b>추가</b> 제외일
 * 뿐이고, 실행할 때마다 사용자의 최신 기본 제외와 합친다. 저장 시점에 복사하면 나중에
 * 알레르기 태그를 추가해도 옛 프리셋은 그걸 모른 채 계속 뽑는다. 같은 이유로
 * <b>프리셋이 기본 제외를 해제할 수단은 없다</b> — 이 엔티티에 그런 필드를 두지 않는 것이
 * 그 불변식의 구현이다.
 */
@Entity
@Table(name = "pick_presets")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickPreset extends BaseTimeEntity {

    /** 사용자당 상한. 고르는 화면이 목록이라 이보다 많아지면 "빠른" 픽이 아니게 된다. */
    public static final int MAX_PER_USER = 10;

    public static final int NAME_MAX_LENGTH = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    /**
     * 참조하던 태그가 삭제되면 켜진다. 켜져 있는 동안 <b>실행을 막는다.</b>
     *
     * <p>태그가 사라지면 그 조건만 조용히 빠진 채 추천이 넓어진다 — "견과류 제외"를 걸어 둔
     * 프리셋이 그 태그를 지운 뒤 견과류를 뽑는 상황이다. 자동으로 좁히거나 넓히지 않고
     * 사용자가 편집 화면에서 전체 조건을 다시 확인해 저장해야 풀린다.
     */
    @Column(nullable = false)
    private boolean needsReview;

    /** NULL이면 거리 조건 없음. 값이 있으면 {@link PickPresetDistance}의 허용값만 들어온다. */
    private Integer maxDistance;

    /** 낙관적 락. 수정·삭제·실행이 모두 확인한다(V9의 다른 테이블과 같은 관례). */
    @Version
    @Column(nullable = false)
    private Long version;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "pick_preset_categories",
            joinColumns = @JoinColumn(name = "preset_id"))
    @Column(name = "category", length = 20, nullable = false)
    private Set<String> categories = new LinkedHashSet<>();

    /**
     * 태그 조건. 포함·추가 제외를 {@code filter_type}으로 한 테이블에 담는다.
     *
     * <p>따로 두지 않는 이유는 둘의 제약이 같기 때문이다 — 같은 소유자 검사, 같은 개수 상한,
     * 같은 cascade. 나누면 스키마가 둘로 늘고 검증이 두 벌이 된다.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "pick_preset_tags",
            joinColumns = @JoinColumn(name = "preset_id"))
    private Set<PickPresetTag> tags = new LinkedHashSet<>();

    @Builder
    public PickPreset(User user, String name, Integer maxDistance) {
        this.user = user;
        this.name = name;
        this.maxDistance = maxDistance;
        this.needsReview = false;
    }

    /**
     * 조건을 통째로 갈아 끼운다. 부분 수정이 아니라 <b>전체 교체</b>다.
     *
     * <p>PUT이 전체 교체인 이유는 검토 복구와 맞물려 있다 — 태그가 지워진 프리셋을 되살리려면
     * 사용자가 남은 조건 전부를 다시 보고 승인해야 하므로, 그 화면이 보내는 것이 곧 전체다.
     */
    public void replaceConditions(String name, Integer maxDistance,
                                  Set<String> categories, Set<PickPresetTag> tags) {
        this.name = name;
        this.maxDistance = maxDistance;
        this.categories.clear();
        this.categories.addAll(categories);
        this.tags.clear();
        this.tags.addAll(tags);
    }

    /**
     * 검토 필요 표시를 켠다. 태그 삭제 경로가 부른다.
     *
     * <p>이 엔티티로는 부르지 않는다 — 삭제되는 태그를 참조하는 프리셋이 여럿일 수 있어
     * 벌크 UPDATE로 처리하고, 이 메서드는 그 의미를 코드에 남겨 두기 위한 것이다.
     */
    public void markNeedsReview() {
        this.needsReview = true;
    }

    /** 사용자가 전체 조건을 다시 확인해 저장했을 때만 풀린다(설계 7절). */
    public void clearNeedsReview() {
        this.needsReview = false;
    }

    public Set<Long> includeTagIds() {
        return tagIdsOf(PickPresetTag.FilterType.INCLUDE);
    }

    /** <b>추가</b> 제외만이다. 기본 제외는 실행 시점에 합쳐진다 — 클래스 주석 참고. */
    public Set<Long> excludeTagIds() {
        return tagIdsOf(PickPresetTag.FilterType.EXCLUDE);
    }

    private Set<Long> tagIdsOf(PickPresetTag.FilterType type) {
        Set<Long> ids = new LinkedHashSet<>();
        for (PickPresetTag tag : tags) {
            if (tag.getFilterType() == type) {
                ids.add(tag.getTagId());
            }
        }
        return ids;
    }
}
