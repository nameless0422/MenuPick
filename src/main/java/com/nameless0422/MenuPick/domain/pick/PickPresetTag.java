package com.nameless0422.MenuPick.domain.pick;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 프리셋이 참조하는 태그 하나와 그 역할.
 *
 * <p>{@code tag_id}를 FK로만 들고 {@code Tag} 엔티티를 참조하지 않는다. 프리셋을 읽을 때
 * 필요한 것은 <b>id 집합</b>뿐이고({@code PickService}가 id로 거른다), 엔티티로 매핑하면
 * 목록 조회마다 태그를 함께 끌고 와 N+1의 입구가 된다. 태그 이름이 필요한 화면은 이미
 * 자기 태그 목록을 갖고 있다.
 *
 * <p>DB의 FK cascade는 그대로 걸려 있어 태그가 지워지면 이 행도 사라진다. 그때 부모 프리셋이
 * 조용히 넓어지지 않도록 {@code TagService.deleteTag}가 삭제 <b>전에</b> needsReview를 켠다.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EqualsAndHashCode
public class PickPresetTag {

    public enum FilterType {
        /** 이 태그를 모두 가진 메뉴만 후보로 둔다. */
        INCLUDE,
        /**
         * 이 태그를 가진 메뉴를 후보에서 뺀다.
         *
         * <p>사용자의 <b>기본 제외와는 별개</b>다. 기본 제외는 프리셋에 복사되지 않고 실행할
         * 때마다 최신 값이 합쳐진다 — 프리셋은 기본 제외를 더할 수만 있고 뺄 수는 없다.
         */
        EXCLUDE
    }

    @Column(name = "tag_id", nullable = false)
    private Long tagId;

    @Enumerated(EnumType.STRING)
    @Column(name = "filter_type", nullable = false, length = 10)
    private FilterType filterType;

    private PickPresetTag(Long tagId, FilterType filterType) {
        this.tagId = tagId;
        this.filterType = filterType;
    }

    public static PickPresetTag include(Long tagId) {
        return new PickPresetTag(tagId, FilterType.INCLUDE);
    }

    public static PickPresetTag exclude(Long tagId) {
        return new PickPresetTag(tagId, FilterType.EXCLUDE);
    }
}
