package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetResponse;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.tag.TagService;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 태그가 지워졌을 때 빠른 픽이 어떻게 되는가({@code docs/PickPresetDesign.md} 7절).
 *
 * <p>이 파일이 지키는 것은 하나다 — <b>조건이 조용히 넓어진 채로 추천이 나가지 않는다.</b>
 * "견과류 제외"를 걸어 둔 프리셋에서 그 태그를 지우면, 아무 표시 없이 견과류를 뽑기 시작하는
 * 것이 가장 나쁜 결과다. 결과가 그럴듯해서 눈으로는 알아챌 수 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickPresetLifecycleTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, KST).toInstant(), KST);

    @Autowired private PickPresetRepository presetRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;
    @Autowired private HistoryRepository historyRepository;
    @Autowired private EntityManager entityManager;

    private PickPresetService presetService;
    private TagService tagService;
    private User me;

    @BeforeEach
    void setUp() {
        DefaultPickPreferenceService defaults = new DefaultPickPreferenceService(tagRepository);
        PickService pickService = new PickService(menuRepository, historyRepository, userRepository,
                tagRepository, defaults, menuRestaurantRepository, FIXED_CLOCK);
        presetService = new PickPresetService(presetRepository, tagRepository, userRepository,
                defaults, pickService);
        tagService = new TagService(tagRepository, userRepository, presetRepository);

        me = userRepository.save(User.builder().email("me@example.com").nickname("나").build());
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Tag tag(String name) {
        return tagRepository.save(Tag.builder().user(me).name(name).build());
    }

    private void menu(String name, Tag... tags) {
        Menu menu = Menu.builder().user(me).name(name).weight(1).build();
        for (Tag t : tags) {
            menu.addTag(t);
        }
        menuRepository.save(menu);
    }

    @Test
    @DisplayName("태그를 지우면 그 태그를 쓰던 프리셋에 검토 필요가 켜지고 버전이 오른다")
    void tagDeletion_marksNeedsReview() {
        Tag nuts = tag("견과류");
        PickPresetResponse.Detail preset = presetService.create(me.getId(),
                new PickPresetRequest.Create("안전식", Set.of(), Set.of(), Set.of(nuts.getId()), null));
        flushAndClear();

        tagService.deleteTag(me.getId(), nuts.getId());
        flushAndClear();

        PickPresetResponse.Detail after = presetService.get(me.getId(), preset.id());
        assertThat(after.needsReview()).isTrue();
        assertThat(after.version())
                .as("버전이 그대로면 미리보기 화면이 들고 있던 값으로 그대로 실행된다")
                .isGreaterThan(preset.version());
        assertThat(after.additionalExcludeTagIds())
                .as("FK cascade가 자식 행을 지운다")
                .isEmpty();
    }

    /** 이 파일의 핵심. 조건이 줄어든 프리셋이 아무 말 없이 다시 도는 것을 막는다. */
    @Test
    @DisplayName("검토 필요 상태에서는 실행이 막힌다 — 조건이 넓어진 채로 뽑지 않는다")
    void needsReview_blocksExecution() {
        Tag nuts = tag("견과류");
        menu("땅콩국수", nuts);
        menu("김치찌개");
        PickPresetResponse.Detail preset = presetService.create(me.getId(),
                new PickPresetRequest.Create("안전식", Set.of(), Set.of(), Set.of(nuts.getId()), null));
        flushAndClear();

        tagService.deleteTag(me.getId(), nuts.getId());
        flushAndClear();

        PickPresetResponse.Detail after = presetService.get(me.getId(), preset.id());
        assertThatThrownBy(() -> presetService.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(after.version(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_PRESET_NEEDS_REVIEW);

        assertThat(historyRepository.count()).as("막혔으면 히스토리가 없어야 한다").isZero();
    }

    @Test
    @DisplayName("검토 상태는 명시적 승인 없이 풀리지 않는다")
    void needsReview_requiresAcknowledgement() {
        Tag nuts = tag("견과류");
        PickPresetResponse.Detail preset = presetService.create(me.getId(),
                new PickPresetRequest.Create("안전식", Set.of(), Set.of(), Set.of(nuts.getId()), null));
        flushAndClear();
        tagService.deleteTag(me.getId(), nuts.getId());
        flushAndClear();

        PickPresetResponse.Detail after = presetService.get(me.getId(), preset.id());

        // 승인 없이 저장하면 거절된다 — 사용자가 "지금 조건이 이게 맞다"고 확인하지 않았다.
        assertThatThrownBy(() -> presetService.update(me.getId(), preset.id(),
                new PickPresetRequest.Update("안전식", Set.of(), Set.of(), Set.of(), null,
                        after.version(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_PRESET_NEEDS_REVIEW);
    }

    @Test
    @DisplayName("승인과 함께 전체 조건을 다시 저장하면 검토가 풀리고 실행된다")
    void acknowledgement_restoresExecution() {
        Tag nuts = tag("견과류");
        menu("김치찌개");
        PickPresetResponse.Detail preset = presetService.create(me.getId(),
                new PickPresetRequest.Create("안전식", Set.of(), Set.of(), Set.of(nuts.getId()), null));
        flushAndClear();
        tagService.deleteTag(me.getId(), nuts.getId());
        flushAndClear();

        PickPresetResponse.Detail stale = presetService.get(me.getId(), preset.id());
        PickPresetResponse.Detail restored = presetService.update(me.getId(), preset.id(),
                new PickPresetRequest.Update("안전식", Set.of(), Set.of(), Set.of(), null,
                        stale.version(), true));
        flushAndClear();

        assertThat(restored.needsReview()).isFalse();
        assertThat(presetService.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(restored.version(), null, null)).pick().menu().name())
                .isEqualTo("김치찌개");
    }

    /**
     * 삭제된 태그 id가 그대로 남은 오래된 요청은 <b>404이고 검토 상태를 풀지 않는다.</b>
     * 풀어 주면 사용자가 화면을 새로 고치지 않고 그대로 저장 버튼을 눌러 승인이 성립해 버린다.
     */
    @Test
    @DisplayName("삭제된 태그를 다시 보내면 404이고 검토 상태가 그대로 남는다")
    void staleRequestWithDeletedTag_isRejected() {
        Tag nuts = tag("견과류");
        PickPresetResponse.Detail preset = presetService.create(me.getId(),
                new PickPresetRequest.Create("안전식", Set.of(), Set.of(), Set.of(nuts.getId()), null));
        flushAndClear();
        Long deletedTagId = nuts.getId();
        tagService.deleteTag(me.getId(), deletedTagId);
        flushAndClear();

        PickPresetResponse.Detail stale = presetService.get(me.getId(), preset.id());

        assertThatThrownBy(() -> presetService.update(me.getId(), preset.id(),
                new PickPresetRequest.Update("안전식", Set.of(), Set.of(), Set.of(deletedTagId), null,
                        stale.version(), true)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TAG_NOT_FOUND);

        flushAndClear();
        assertThat(presetService.get(me.getId(), preset.id()).needsReview())
                .as("거절된 요청이 검토 상태를 풀면 안 된다")
                .isTrue();
    }

    @Test
    @DisplayName("한 프리셋이 태그 하나를 여러 조건으로 쓰지 않으므로 버전은 한 번만 오른다")
    void versionBumpsOnce() {
        Tag nuts = tag("견과류");
        Tag spicy = tag("매움");
        PickPresetResponse.Detail preset = presetService.create(me.getId(),
                new PickPresetRequest.Create("안전식", Set.of(), Set.of(spicy.getId()),
                        Set.of(nuts.getId()), null));
        flushAndClear();

        tagService.deleteTag(me.getId(), nuts.getId());
        flushAndClear();

        assertThat(presetService.get(me.getId(), preset.id()).version())
                .isEqualTo(preset.version() + 1);
    }

    @Test
    @DisplayName("관계없는 태그를 지우면 프리셋은 그대로다")
    void unrelatedTagDeletion_leavesPresetAlone() {
        Tag nuts = tag("견과류");
        Tag unrelated = tag("상관없음");
        PickPresetResponse.Detail preset = presetService.create(me.getId(),
                new PickPresetRequest.Create("안전식", Set.of(), Set.of(), Set.of(nuts.getId()), null));
        flushAndClear();

        tagService.deleteTag(me.getId(), unrelated.getId());
        flushAndClear();

        PickPresetResponse.Detail after = presetService.get(me.getId(), preset.id());
        assertThat(after.needsReview()).isFalse();
        assertThat(after.version()).isEqualTo(preset.version());
    }
}
