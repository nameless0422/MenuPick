package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스 트랜잭션이 닫힌 뒤(open-in-view=false 직렬화 시점과 동일 조건) DTO의
 * LAZY 컬렉션에 접근해도 안전한지 검증한다. 테스트 자체를 트랜잭션으로 감싸면
 * 세션이 살아 있어 재현되지 않으므로 NOT_SUPPORTED로 비활성화한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, MenuService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MenuServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MenuService menuService;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("메뉴 목록 조회 결과의 categories는 트랜잭션 종료 후에도 접근 가능하다")
    void getMenus_categoriesAccessibleAfterTransaction() {
        User user = userRepository.save(
                User.builder().email("menu-list-int@test.com").nickname("목록유저").build());
        Menu menu = Menu.builder().user(user).name("김치찌개").memo(null).weight(1).build();
        menu.addCategory("한식");
        menu.addCategory("찌개");
        menuRepository.save(menu);

        MenuResponse.MenuListResponse response = menuService.getMenus(user.getId(), null, 10);

        assertThat(response.menus()).hasSize(1);
        assertThat(response.menus().get(0).categories())
                .containsExactlyInAnyOrder("한식", "찌개");
    }

    @Test
    @DisplayName("메뉴 단건 조회 결과의 categories는 트랜잭션 종료 후에도 접근 가능하다")
    void getMenu_categoriesAccessibleAfterTransaction() {
        User user = userRepository.save(
                User.builder().email("menu-detail-int@test.com").nickname("단건유저").build());
        Menu menu = Menu.builder().user(user).name("파스타").memo("점심").weight(2).build();
        menu.addCategory("양식");
        Long menuId = menuRepository.save(menu).getId();

        MenuResponse.MenuDetail detail = menuService.getMenu(user.getId(), menuId);

        assertThat(detail.categories()).containsExactly("양식");
    }

    /**
     * 온보딩이 22개를 한 번에 보낸다. 일부만 반영되면 사용자는 뺐다고 믿는데 그 메뉴가 계속 나온다.
     */
    @org.junit.jupiter.api.Test
    @DisplayName("일괄 제외 - 담긴 메뉴만 바꾸고 나머지는 건드리지 않는다")
    void batchUpdateExclusion_changesOnlyListed() {
        User user = userRepository.save(
                User.builder().email("bulk-exclude@test.com").nickname("일괄유저").build());
        Menu kimchi = menuRepository.save(Menu.builder().user(user).name("김치찌개").weight(1).build());
        Menu mara = menuRepository.save(Menu.builder().user(user).name("마라탕").weight(1).build());
        Menu untouched = menuRepository.save(Menu.builder().user(user).name("비빔밥").weight(1).build());
        untouched.exclude();
        menuRepository.save(untouched);

        menuService.batchUpdateExclusion(user.getId(), new MenuRequest.BatchUpdateExclusion(java.util.List.of(
                new MenuRequest.ExclusionEntry(mara.getId(), true),
                new MenuRequest.ExclusionEntry(kimchi.getId(), false))));

        assertThat(menuRepository.findById(mara.getId()).orElseThrow().isExcluded()).isTrue();
        assertThat(menuRepository.findById(kimchi.getId()).orElseThrow().isExcluded()).isFalse();
        // 목록에 없던 메뉴의 제외는 그대로 남는다 — 전체 교체가 아니다.
        assertThat(menuRepository.findById(untouched.getId()).orElseThrow().isExcluded()).isTrue();
    }

    /** 하나라도 남의 메뉴면 아무것도 바꾸지 않는다. 일부만 반영되는 것이 가장 나쁘다. */
    @org.junit.jupiter.api.Test
    @DisplayName("일괄 제외 - 남의 메뉴가 섞이면 아무것도 바꾸지 않는다")
    void batchUpdateExclusion_rejectsForeignMenu() {
        User user = userRepository.save(
                User.builder().email("bulk-mine@test.com").nickname("내유저").build());
        User other = userRepository.save(
                User.builder().email("bulk-other@test.com").nickname("남유저").build());
        Menu mine = menuRepository.save(Menu.builder().user(user).name("김치찌개").weight(1).build());
        Menu theirs = menuRepository.save(Menu.builder().user(other).name("파스타").weight(1).build());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                menuService.batchUpdateExclusion(user.getId(), new MenuRequest.BatchUpdateExclusion(
                        java.util.List.of(
                                new MenuRequest.ExclusionEntry(mine.getId(), true),
                                new MenuRequest.ExclusionEntry(theirs.getId(), true)))))
                .isInstanceOf(com.nameless0422.MenuPick.common.exception.BusinessException.class);

        assertThat(menuRepository.findById(mine.getId()).orElseThrow().isExcluded()).isFalse();
        assertThat(menuRepository.findById(theirs.getId()).orElseThrow().isExcluded()).isFalse();
    }
}
