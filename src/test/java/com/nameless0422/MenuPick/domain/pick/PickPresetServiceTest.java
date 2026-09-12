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
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 상황별 빠른 픽 — CRUD·실행의 계약({@code docs/PickPresetDesign.md} 11절).
 *
 * <p>여기서 보는 것은 대부분 <b>조용히 잘못될 수 있는</b> 것들이다. 조건이 하나 빠진 채로
 * 추천이 나가거나, 남의 프리셋이 보이거나, 기본 제외가 무시되는 상황은 전부 결과가
 * "그럴듯하게" 나와서 눈으로는 알아채기 어렵다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickPresetServiceTest extends AbstractIntegrationTest {

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

    private PickPresetService service;
    private DefaultPickPreferenceService defaultPreferences;
    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        defaultPreferences = new DefaultPickPreferenceService(tagRepository);
        PickService pickService = new PickService(menuRepository, historyRepository, userRepository,
                tagRepository, defaultPreferences, menuRestaurantRepository, FIXED_CLOCK);
        service = new PickPresetService(presetRepository, tagRepository, userRepository,
                defaultPreferences, pickService);

        me = userRepository.save(User.builder().email("me@example.com").nickname("나").build());
        other = userRepository.save(User.builder().email("other@example.com").nickname("남").build());
    }

    private Tag tag(User owner, String name) {
        return tagRepository.save(Tag.builder().user(owner).name(name).build());
    }

    private Menu menu(String name, String category, Tag... tags) {
        Menu menu = Menu.builder().user(me).name(name).weight(1).build();
        if (category != null) {
            menu.addCategory(category);
        }
        for (Tag t : tags) {
            menu.addTag(t);
        }
        return menuRepository.save(menu);
    }

    private PickPresetRequest.Create create(String name, Set<String> categories,
                                            Set<Long> include, Set<Long> exclude,
                                            Integer maxDistance) {
        return new PickPresetRequest.Create(name, categories, include, exclude, maxDistance);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    // ── CRUD ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("생성 - 조건을 그대로 저장하고 버전 0으로 시작한다")
    void create_storesConditions() {
        Tag honbap = tag(me, "혼밥");
        Tag spicy = tag(me, "매움");

        PickPresetResponse.Detail saved = service.create(me.getId(),
                create("회사 점심", Set.of("한식", "중식"), Set.of(honbap.getId()),
                        Set.of(spicy.getId()), 500));

        assertThat(saved.name()).isEqualTo("회사 점심");
        assertThat(saved.categories()).containsExactlyInAnyOrder("한식", "중식");
        assertThat(saved.includeTagIds()).containsExactly(honbap.getId());
        assertThat(saved.additionalExcludeTagIds()).containsExactly(spicy.getId());
        assertThat(saved.maxDistance()).isEqualTo(500);
        assertThat(saved.needsReview()).isFalse();
        assertThat(saved.version()).isZero();
    }

    /** 이름 비교는 컬럼 collation(utf8mb4_0900_ai_ci)을 따르므로 대소문자를 구분하지 않는다. */
    @Test
    @DisplayName("생성 - 같은 이름은 409, 대소문자만 다른 것도 같은 이름이다")
    void create_duplicateName() {
        service.create(me.getId(), create("Lunch", Set.of(), Set.of(), Set.of(), null));

        assertThatThrownBy(() -> service.create(me.getId(),
                create("lunch", Set.of(), Set.of(), Set.of(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_PRESET_NAME_DUPLICATE);
    }

    @Test
    @DisplayName("생성 - 이름은 trim 후 저장한다")
    void create_trimsName() {
        PickPresetResponse.Detail saved = service.create(me.getId(),
                create("  회사 점심  ", Set.of(), Set.of(), Set.of(), null));

        assertThat(saved.name()).isEqualTo("회사 점심");
    }

    @Test
    @DisplayName("생성 - 10개를 넘기면 409")
    void create_limit() {
        for (int i = 0; i < PickPreset.MAX_PER_USER; i++) {
            service.create(me.getId(), create("프리셋" + i, Set.of(), Set.of(), Set.of(), null));
        }

        assertThatThrownBy(() -> service.create(me.getId(),
                create("하나 더", Set.of(), Set.of(), Set.of(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_PRESET_LIMIT_EXCEEDED);
    }

    /** 403이 아니라 404다 — 403은 "그 id는 존재한다"를 알려 준다. */
    @Test
    @DisplayName("생성 - 남의 태그를 참조하면 404다 (403이 아니다)")
    void create_foreignTagIsNotFound() {
        Tag foreign = tag(other, "남의태그");

        assertThatThrownBy(() -> service.create(me.getId(),
                create("남의 것", Set.of(), Set.of(foreign.getId()), Set.of(), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TAG_NOT_FOUND);
    }

    @Test
    @DisplayName("생성 - 포함과 추가 제외에 같은 태그를 넣으면 400")
    void create_directConflict() {
        Tag t = tag(me, "혼밥");

        assertThatThrownBy(() -> service.create(me.getId(),
                create("모순", Set.of(), Set.of(t.getId()), Set.of(t.getId()), null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("생성 - 허용되지 않은 거리 값은 400")
    void create_invalidDistance() {
        assertThatThrownBy(() -> service.create(me.getId(),
                create("이상한 거리", Set.of(), Set.of(), Set.of(), 777)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("조회 - 남의 프리셋은 없는 것과 같은 404다")
    void get_ownershipIsNotFound() {
        PickPresetResponse.Detail mine = service.create(me.getId(),
                create("내 것", Set.of(), Set.of(), Set.of(), null));

        assertThatThrownBy(() -> service.get(other.getId(), mine.id()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_PRESET_NOT_FOUND);
    }

    @Test
    @DisplayName("수정 - 오래된 버전이면 409")
    void update_staleVersion() {
        PickPresetResponse.Detail saved = service.create(me.getId(),
                create("점심", Set.of(), Set.of(), Set.of(), null));

        assertThatThrownBy(() -> service.update(me.getId(), saved.id(),
                new PickPresetRequest.Update("점심", Set.of(), Set.of(), Set.of(), null,
                        saved.version() + 99, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test
    @DisplayName("삭제 - 오래된 버전이면 409, 맞으면 지워진다")
    void delete_versionGuarded() {
        PickPresetResponse.Detail saved = service.create(me.getId(),
                create("점심", Set.of(), Set.of(), Set.of(), null));

        assertThatThrownBy(() -> service.delete(me.getId(), saved.id(), saved.version() + 1))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        service.delete(me.getId(), saved.id(), saved.version());
        flushAndClear();
        assertThat(presetRepository.findById(saved.id())).isEmpty();
    }

    // ── 실행 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("실행 - 저장 조건으로 뽑고 적용된 조건을 함께 돌려준다")
    void execute_usesStoredConditions() {
        Tag honbap = tag(me, "혼밥");
        menu("김치찌개", "한식", honbap);
        menu("짜장면", "중식");
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("혼밥 한식", Set.of("한식"), Set.of(honbap.getId()), Set.of(), null));
        flushAndClear();

        PickPresetResponse.ExecutionResult result = service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null));

        assertThat(result.pick().menu().name()).isEqualTo("김치찌개");
        assertThat(result.appliedFilters().categories()).containsExactly("한식");
        assertThat(result.appliedFilters().includeTagIds()).containsExactly(honbap.getId());
    }

    /**
     * 이 테스트가 이 기능의 핵심 불변식이다. 기본 제외를 저장 시점에 복사하면, 나중에
     * 알레르기 태그를 추가해도 옛 프리셋은 그걸 모른 채 계속 뽑는다.
     */
    @Test
    @DisplayName("실행 - 기본 제외는 저장값이 아니라 실행 시점의 최신값을 합친다")
    void execute_unionsLatestDefaultExcludes() {
        Tag nuts = tag(me, "견과류");
        menu("땅콩국수", null, nuts);
        menu("김치찌개", null);
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("아무거나", Set.of(), Set.of(), Set.of(), null));
        flushAndClear();

        // 프리셋을 만든 **뒤에** 기본 제외를 추가한다.
        defaultPreferences.update(me.getId(),
                new com.nameless0422.MenuPick.domain.pick.dto.PickPreferenceRequest(Set.of(nuts.getId())));
        flushAndClear();

        PickPresetResponse.ExecutionResult result = service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null));

        assertThat(result.appliedFilters().effectiveExcludeTagIds()).containsExactly(nuts.getId());
        assertThat(result.pick().menu().name())
                .as("기본 제외가 적용됐다면 견과류 메뉴는 뽑히지 않는다")
                .isEqualTo("김치찌개");
    }

    @Test
    @DisplayName("실행 - 기본 제외가 포함 태그와 겹치면 409로 멈춘다")
    void execute_conflictWithDefaults() {
        Tag honbap = tag(me, "혼밥");
        menu("김치찌개", null, honbap);
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("혼밥", Set.of(), Set.of(honbap.getId()), Set.of(), null));
        defaultPreferences.update(me.getId(),
                new com.nameless0422.MenuPick.domain.pick.dto.PickPreferenceRequest(Set.of(honbap.getId())));
        flushAndClear();

        assertThatThrownBy(() -> service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_PRESET_TAG_CONFLICT);
    }

    /**
     * 거리 조건을 저장해 둔 프리셋을 좌표 없이 실행하면 <b>거리 없는 픽으로 폴백하지 않는다.</b>
     * 사용자는 "가까운 곳"을 기대했는데 전혀 다른 결과를 받게 되기 때문이다.
     */
    @Test
    @DisplayName("실행 - 거리 조건이 있는데 좌표가 없으면 400이고 조용히 폴백하지 않는다")
    void execute_distanceRequiresCoordinates() {
        menu("김치찌개", null);
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("가까운 곳", Set.of(), Set.of(), Set.of(), 500));
        flushAndClear();

        assertThatThrownBy(() -> service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);

        assertThat(historyRepository.count()).as("거절됐으면 히스토리가 없어야 한다").isZero();
    }

    @Test
    @DisplayName("실행 - 거리 조건이 없는데 좌표를 보내면 400")
    void execute_coordinatesWithoutDistance() {
        menu("김치찌개", null);
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("아무거나", Set.of(), Set.of(), Set.of(), null));
        flushAndClear();

        assertThatThrownBy(() -> service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(),
                        new java.math.BigDecimal("37.5"), new java.math.BigDecimal("127.0"))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("실행 - 성공하면 히스토리가 정확히 하나 생긴다")
    void execute_writesExactlyOneHistory() {
        menu("김치찌개", null);
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("아무거나", Set.of(), Set.of(), Set.of(), null));
        flushAndClear();

        service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null));
        flushAndClear();

        assertThat(historyRepository.count()).isEqualTo(1);
    }

    /** 실행은 조회 행위다 — 성공해도 프리셋 버전이 올라가면 화면이 매번 새로고침해야 한다. */
    @Test
    @DisplayName("실행은 프리셋 버전을 올리지 않는다")
    void execute_doesNotBumpVersion() {
        menu("김치찌개", null);
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("아무거나", Set.of(), Set.of(), Set.of(), null));
        flushAndClear();

        service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null));
        flushAndClear();

        assertThat(service.get(me.getId(), preset.id()).version()).isEqualTo(preset.version());
    }

    @Test
    @DisplayName("실행 - 오래된 버전이면 409")
    void execute_staleVersion() {
        menu("김치찌개", null);
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("아무거나", Set.of(), Set.of(), Set.of(), null));
        flushAndClear();

        assertThatThrownBy(() -> service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version() + 1, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    // ── 유효 제외 상한 ──────────────────────────────────────────────────

    @Test
    @DisplayName("실행 - 유효 제외 50개는 통과하고 51개는 409로 멈춘다 (자르지 않는다)")
    void execute_effectiveExcludeLimit() {
        menu("김치찌개", null);
        Set<Long> fifty = new LinkedHashSet<>();
        for (int i = 0; i < 50; i++) {
            fifty.add(tag(me, "기본제외" + i).getId());
        }
        defaultPreferences.update(me.getId(),
                new com.nameless0422.MenuPick.domain.pick.dto.PickPreferenceRequest(fifty));
        PickPresetResponse.Detail preset = service.create(me.getId(),
                create("아무거나", Set.of(), Set.of(), Set.of(), null));
        flushAndClear();

        // 50개: 통과
        PickPresetResponse.ExecutionResult ok = service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null));
        assertThat(ok.appliedFilters().effectiveExcludeTagIds()).hasSize(50);

        // 51번째 기본 제외가 추가되면 멈춘다 — 잘라 내면 사용자가 빼 두라고 한 태그가
        // 조용히 다시 들어와 추천된다.
        Set<Long> fiftyOne = new LinkedHashSet<>(fifty);
        fiftyOne.add(tag(me, "하나 더").getId());
        defaultPreferences.update(me.getId(),
                new com.nameless0422.MenuPick.domain.pick.dto.PickPreferenceRequest(fiftyOne));
        flushAndClear();

        assertThatThrownBy(() -> service.execute(me.getId(), preset.id(),
                new PickPresetRequest.Execute(preset.version(), null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_PRESET_EXCLUDE_LIMIT);
    }
}
