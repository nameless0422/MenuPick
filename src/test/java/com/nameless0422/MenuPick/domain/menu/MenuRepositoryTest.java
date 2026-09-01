package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class MenuRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .email("menu@example.com")
                .nickname("메뉴유저")
                .build());
    }

    @Test
    @DisplayName("메뉴를 저장하고 조회한다")
    void save_and_findById() {
        Menu menu = Menu.builder()
                .user(user)
                .name("김치찌개")
                .memo("맛있는 김치찌개")
                .weight(3)
                .build();

        Menu saved = menuRepository.save(menu);

        Menu found = menuRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("김치찌개");
        assertThat(found.getMemo()).isEqualTo("맛있는 김치찌개");
        assertThat(found.getWeight()).isEqualTo(3);
        assertThat(found.isExcluded()).isFalse();
    }

    @Test
    @DisplayName("사용자 ID로 삭제되지 않은 메뉴 목록을 조회한다")
    void findAllByUserIdAndDeletedAtIsNull() {
        Menu menu1 = menuRepository.save(Menu.builder()
                .user(user).name("김치찌개").weight(1).build());
        Menu menu2 = menuRepository.save(Menu.builder()
                .user(user).name("된장찌개").weight(1).build());
        Menu menu3 = menuRepository.save(Menu.builder()
                .user(user).name("삭제메뉴").weight(1).build());
        menu3.softDelete(LocalDateTime.now());
        menuRepository.flush();

        List<Menu> menus = menuRepository.findAllByUserIdAndDeletedAtIsNull(user.getId());
        assertThat(menus).hasSize(2);
        assertThat(menus).extracting(Menu::getName)
                .containsExactlyInAnyOrder("김치찌개", "된장찌개");
    }

    @Test
    @DisplayName("메뉴에 카테고리를 추가하고 조회한다")
    void categories() {
        Menu menu = Menu.builder()
                .user(user).name("스시").weight(1).build();
        menu.addCategory("JAPANESE");
        menu.addCategory("SEAFOOD");
        menuRepository.save(menu);

        Menu found = menuRepository.findById(menu.getId()).orElseThrow();
        assertThat(found.getCategories()).containsExactlyInAnyOrder("JAPANESE", "SEAFOOD");
    }

    @Test
    @DisplayName("제외되지 않은 메뉴만 조회한다")
    void findPickCandidates() {
        menuRepository.save(Menu.builder()
                .user(user).name("후보메뉴").weight(1).build());
        Menu excluded = menuRepository.save(Menu.builder()
                .user(user).name("제외메뉴").weight(1).build());
        excluded.exclude();
        menuRepository.flush();

        List<Menu> candidates = menuRepository
                .findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(user.getId());
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getName()).isEqualTo("후보메뉴");
    }

    /**
     * 픽 후보 조회의 계약은 세 가지인데, 여태 실행되는 것은 {@code isExcluded} 하나뿐이었다.
     *
     * <p>{@code PickServiceTest}는 순수 Mockito라 이 메서드를 통째로 스텁한다 — 이름이 무엇이든
     * 스텁이 답하므로 쿼리 자체는 한 번도 돌지 않는다. 그래서 파생 쿼리 이름에서
     * {@code AndDeletedAtIsNull}이나 {@code UserId}가 빠져도 전 구간이 초록불로 남았다.
     * 지운 메뉴가 오늘 점심으로 뽑히거나, 남의 메뉴가 내 픽에 섞이는 것은 조용히 통과할 수
     * 있는 종류의 사고가 아니다.
     */
    @Test
    @DisplayName("픽 후보 - 소프트 삭제된 메뉴는 후보에서 빠진다")
    void findPickCandidates_excludesSoftDeleted() {
        menuRepository.save(Menu.builder()
                .user(user).name("살아있는메뉴").weight(1).build());
        Menu removed = menuRepository.save(Menu.builder()
                .user(user).name("지운메뉴").weight(1).build());
        removed.softDelete(LocalDateTime.now());
        menuRepository.flush();

        List<Menu> candidates = menuRepository
                .findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(user.getId());

        assertThat(candidates).extracting(Menu::getName).containsExactly("살아있는메뉴");
    }

    @Test
    @DisplayName("픽 후보 - 남의 메뉴는 후보에 섞이지 않는다")
    void findPickCandidates_isScopedToOwner() {
        User other = userRepository.save(User.builder()
                .email("other@example.com")
                .nickname("남")
                .build());
        menuRepository.save(Menu.builder()
                .user(user).name("내메뉴").weight(1).build());
        menuRepository.save(Menu.builder()
                .user(other).name("남의메뉴").weight(1).build());
        menuRepository.flush();

        List<Menu> candidates = menuRepository
                .findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(user.getId());

        assertThat(candidates).extracting(Menu::getName).containsExactly("내메뉴");
    }

    // --- 커서 페이지네이션 경계 ---

    @Test
    @DisplayName("커서 없이 조회하면 id 내림차순으로 요청한 개수만 반환한다")
    void cursorQuery_firstPage_isDescendingAndLimited() {
        Menu first = save("메뉴1");
        Menu second = save("메뉴2");
        Menu third = save("메뉴3");

        List<Menu> page = menuRepository.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(
                user.getId(), PageRequest.of(0, 2));

        assertThat(page).extracting(Menu::getId)
                .containsExactly(third.getId(), second.getId());
        assertThat(page).extracting(Menu::getId).doesNotContain(first.getId());
    }

    @Test
    @DisplayName("커서 조회는 커서 id 자신을 제외하고 그보다 작은 id만 반환한다 (경계: id < cursor)")
    void cursorQuery_excludesCursorItself() {
        Menu first = save("메뉴1");
        Menu second = save("메뉴2");
        Menu third = save("메뉴3");

        List<Menu> page = menuRepository.findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                user.getId(), third.getId(), PageRequest.of(0, 10));

        assertThat(page).extracting(Menu::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("커서가 가장 작은 id면 다음 페이지는 비어 있다")
    void cursorQuery_atOldestId_returnsEmpty() {
        Menu first = save("메뉴1");
        save("메뉴2");

        List<Menu> page = menuRepository.findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                user.getId(), first.getId(), PageRequest.of(0, 10));

        assertThat(page).isEmpty();
    }

    @Test
    @DisplayName("커서 조회는 soft-delete된 메뉴와 타 사용자의 메뉴를 건너뛴다")
    void cursorQuery_skipsDeletedAndOtherUsersMenus() {
        User other = userRepository.save(User.builder()
                .email("menu-other@example.com").nickname("다른유저").build());

        Menu mine = save("내메뉴");
        Menu deleted = save("삭제메뉴");
        deleted.softDelete(LocalDateTime.now());
        Menu othersMenu = menuRepository.save(Menu.builder()
                .user(other).name("남의메뉴").weight(1).build());
        menuRepository.flush();

        List<Menu> page = menuRepository.findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                user.getId(), othersMenu.getId() + 1, PageRequest.of(0, 10));

        assertThat(page).extracting(Menu::getId).containsExactly(mine.getId());
        assertThat(page).extracting(Menu::getId)
                .doesNotContain(deleted.getId(), othersMenu.getId());
    }

    @Test
    @DisplayName("메뉴 보유 여부는 soft-delete된 메뉴도 \"있음\"으로 센다")
    void existsByUserId_countsSoftDeletedMenus() {
        // DefaultMenuProvisioner가 기본 메뉴를 두 번 넣지 않기 위해 쓰는 확인이다.
        // deletedAt을 걸러 버리면, 기본 메뉴를 전부 지운 사용자가 다음에 이 경로를 지날 때
        // 앱이 지운 목록을 그대로 다시 채워 넣는다.
        assertThat(menuRepository.existsByUserId(user.getId())).isFalse();

        Menu menu = save("김치찌개");
        menu.softDelete(LocalDateTime.now());
        menuRepository.flush();

        assertThat(menuRepository.existsByUserId(user.getId())).isTrue();
    }

    private Menu save(String name) {
        return menuRepository.save(Menu.builder().user(user).name(name).weight(1).build());
    }
}
