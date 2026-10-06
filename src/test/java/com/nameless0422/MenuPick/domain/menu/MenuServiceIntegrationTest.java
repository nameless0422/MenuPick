package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantService;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.tag.TagService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 서비스 트랜잭션이 닫힌 뒤(open-in-view=false 직렬화 시점과 동일 조건) DTO의
 * LAZY 컬렉션에 접근해도 안전한지 검증한다. 테스트 자체를 트랜잭션으로 감싸면
 * 세션이 살아 있어 재현되지 않으므로 NOT_SUPPORTED로 비활성화한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, MenuService.class, RestaurantService.class, TagService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MenuServiceIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MenuService menuService;

    @Autowired
    private MenuRepository menuRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired private TagRepository tagRepository;
    @Autowired private TagService tagService;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private RestaurantService restaurantService;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;

    @Test
    void restoreMenu_preservesIdentitySettingsAndLinksAndAllowsRetry() {
        User user = trashUser("preserve");
        Tag tag = tagRepository.save(Tag.builder().user(user).name("혼밥").build());
        var created = menuService.createMenu(user.getId(), new MenuRequest.Create(
                "김치찌개", "메모", 4, java.util.Set.of("한식"), java.util.Set.of(tag.getId())));
        var configured = menuService.updateMenu(user.getId(), created.id(), new MenuRequest.Update(
                created.name(), created.memo(), 4, true, created.categories(), java.util.Set.of(tag.getId()), created.version()));
        var paused = menuService.pauseMenu(user.getId(), created.id(), 7);
        var persistedPause = menuService.getMenu(user.getId(), created.id()).pausedUntil();
        Restaurant restaurant = trashRestaurant(user);
        MenuRestaurant link = menuRestaurantRepository.save(MenuRestaurant.builder()
                .menu(menuRepository.findById(created.id()).orElseThrow()).restaurant(restaurant)
                .rating(5).memo("연결 메모").build());
        menuService.deleteMenu(user.getId(), created.id());
        assertThatThrownBy(() -> menuService.getMenu(user.getId(), created.id()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.MENU_NOT_FOUND);
        var deleted = menuService.getDeletedMenus(user.getId(), null, 20).menus().get(0);
        assertThat(deleted.version()).isGreaterThan(paused.version());

        menuService.restoreMenu(user.getId(), deleted.id(), new MenuRequest.Restore(deleted.version()));
        var restored = menuService.getMenu(user.getId(), created.id());
        assertThat(restored.id()).isEqualTo(created.id());
        assertThat(restored.memo()).isEqualTo("메모");
        assertThat(restored.weight()).isEqualTo(configured.weight());
        assertThat(restored.isExcluded()).isTrue();
        assertThat(restored.pausedUntil()).isEqualTo(persistedPause);
        assertThat(restored.categories()).containsExactly("한식");
        assertThat(restored.tags()).extracting(MenuResponse.TagSummary::id).containsExactly(tag.getId());
        assertThat(menuRestaurantRepository.findById(link.getId()).orElseThrow().getMemo()).isEqualTo("연결 메모");
        assertThat(menuService.getDeletedMenus(user.getId(), null, 20).menus()).isEmpty();
        assertThat(menuService.getMenus(user.getId(), null, 20).menus()).extracting(MenuResponse.MenuSummary::id)
                .containsExactly(created.id());
        assertThat(restored.version()).isGreaterThan(deleted.version());

        menuService.restoreMenu(user.getId(), deleted.id(), new MenuRequest.Restore(deleted.version()));
        assertThat(menuService.getMenu(user.getId(), created.id()).version()).isEqualTo(restored.version());
    }

    @Test
    void restoreMenu_oldTrashVersionCannotRestoreAfterAnotherDeletion() {
        User user = trashUser("stale");
        Menu menu = menuRepository.save(Menu.builder().user(user).name("재삭제 메뉴").build());
        menuService.deleteMenu(user.getId(), menu.getId());
        var deleted = menuService.getDeletedMenus(user.getId(), null, 20).menus().get(0);
        menuService.restoreMenu(user.getId(), menu.getId(), new MenuRequest.Restore(deleted.version()));
        menuService.deleteMenu(user.getId(), menu.getId());
        assertThatThrownBy(() -> menuService.restoreMenu(user.getId(), menu.getId(), new MenuRequest.Restore(deleted.version())))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(menuRepository.findById(menu.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    void trash_paginatesOnlyOwnedDeletedMenus() {
        User user = trashUser("pages");
        User other = trashUser("foreign");
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Menu menu = menuRepository.save(Menu.builder().user(user).name("삭제 " + i).build());
            menuService.deleteMenu(user.getId(), menu.getId());
            ids.add(menu.getId());
        }
        menuRepository.save(Menu.builder().user(user).name("활성 메뉴").build());
        Menu foreign = menuRepository.save(Menu.builder().user(other).name("남의 삭제 메뉴").build());
        menuService.deleteMenu(other.getId(), foreign.getId());
        var first = menuService.getDeletedMenus(user.getId(), null, 2);
        assertThat(first.menus()).extracting(MenuResponse.DeletedMenuSummary::id).containsExactly(ids.get(2), ids.get(1));
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(first.hasNext()).isTrue();
        var next = menuService.getDeletedMenus(user.getId(), first.nextCursor(), 2);
        assertThat(next.menus()).extracting(MenuResponse.DeletedMenuSummary::id).containsExactly(ids.get(0));
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextCursor()).isNull();
        assertThatThrownBy(() -> menuService.restoreMenu(user.getId(), foreign.getId(), new MenuRequest.Restore(1L)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.MENU_NOT_FOUND);
        assertThat(menuRepository.findById(foreign.getId()).orElseThrow().isDeleted()).isTrue();
        assertThatThrownBy(() -> menuService.restoreMenu(user.getId(), Long.MAX_VALUE, new MenuRequest.Restore(0L)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.MENU_NOT_FOUND);
    }

    @Test
    void trash_ordersByDeletionTimeAndContinuesAfterCursorMenuIsRestored() {
        User user = trashUser("chronology");
        java.time.LocalDateTime older = java.time.LocalDateTime.of(2026, 10, 7, 0, 15, 0, 123456000);
        Menu firstRegistered = Menu.builder().user(user).name("방금 삭제한 예전 메뉴").build();
        firstRegistered.softDelete(older.plusHours(1));
        firstRegistered = menuRepository.save(firstRegistered);
        Menu secondRegistered = Menu.builder().user(user).name("같은 시각 첫 메뉴").build();
        secondRegistered.softDelete(older);
        secondRegistered = menuRepository.save(secondRegistered);
        Menu thirdRegistered = Menu.builder().user(user).name("같은 시각 다음 메뉴").build();
        thirdRegistered.softDelete(older);
        thirdRegistered = menuRepository.save(thirdRegistered);
        var first = menuService.getDeletedMenus(user.getId(), null, 2);
        assertThat(first.menus()).extracting(MenuResponse.DeletedMenuSummary::id)
                .containsExactly(firstRegistered.getId(), thirdRegistered.getId());
        assertThat(first.hasNext()).isTrue();
        menuService.restoreMenu(user.getId(), thirdRegistered.getId(), new MenuRequest.Restore(thirdRegistered.getVersion()));
        var next = menuService.getDeletedMenus(user.getId(), first.nextCursor(), 2);
        assertThat(next.menus()).extracting(MenuResponse.DeletedMenuSummary::id).containsExactly(secondRegistered.getId());
        assertThat(next.hasNext()).isFalse();
        assertThat(next.nextCursor()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", "not-a-cursor", "2026-10-07|1",
            "bad-date|1", "2026-10-07T00:15:00|0", "2026-10-07T00:15:00|-1",
            "0999-10-07T00:15:00|1", "2026-10-07T00:15:00|9223372036854775808"})
    void trash_rejectsMalformedCursor(String value) {
        String cursor = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> menuService.getDeletedMenus(1L, cursor, 20))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
    }

    @Test
    void restoreMenu_doesNotRestoreSeparatelyDeletedTagsOrRestaurants() {
        User user = trashUser("references");
        Tag tag = tagRepository.save(Tag.builder().user(user).name("삭제할 태그").build());
        var menu = menuService.createMenu(user.getId(), new MenuRequest.Create(
                "참조 메뉴", null, 1, java.util.Set.of(), java.util.Set.of(tag.getId())));
        Restaurant restaurant = trashRestaurant(user);
        menuRestaurantRepository.save(MenuRestaurant.builder().menu(menuRepository.findById(menu.id()).orElseThrow())
                .restaurant(restaurant).rating(3).build());
        menuService.deleteMenu(user.getId(), menu.id());
        long version = menuService.getDeletedMenus(user.getId(), null, 20).menus().get(0).version();
        tagService.deleteTag(user.getId(), tag.getId());
        restaurantService.deleteRestaurant(user.getId(), restaurant.getId());

        menuService.restoreMenu(user.getId(), menu.id(), new MenuRequest.Restore(version));
        assertThat(menuService.getMenu(user.getId(), menu.id()).tags()).isEmpty();
        assertThat(menuRestaurantRepository.findActiveByMenuIdWithRestaurant(menu.id())).isEmpty();
        assertThat(tagRepository.findById(tag.getId())).isEmpty();
        assertThat(restaurantRepository.findById(restaurant.getId()).orElseThrow().isDeleted()).isTrue();
    }

    private User trashUser(String suffix) {
        return userRepository.save(User.builder().email("trash-" + suffix + "@test.com")
                .nickname("휴지통" + suffix).build());
    }

    private Restaurant trashRestaurant(User user) {
        return restaurantRepository.save(Restaurant.builder().user(user).name("연결식당")
                .latitude(new java.math.BigDecimal("37.5665")).longitude(new java.math.BigDecimal("126.978")).build());
    }

    @Test
    void bulkCreate_persistsOnlyNewNamesAndRejectsInvalidBatch() {
        User user = userRepository.save(
                User.builder().email("bulk-create@test.com").nickname("여러메뉴유저").build());
        menuRepository.save(Menu.builder().user(user).name("김치찌개").build());

        var result = menuService.bulkCreate(user.getId(), new MenuRequest.BulkCreate(
                java.util.List.of(" 순대국 ", "김치찌개", "순대국", "양꼬치")));

        assertThat(result.createdCount()).isEqualTo(2);
        assertThat(menuRepository.findAllByUserIdAndDeletedAtIsNull(user.getId()))
                .extracting(Menu::getName).containsExactlyInAnyOrder("김치찌개", "순대국", "양꼬치");

        assertThatThrownBy(() -> menuService.bulkCreate(user.getId(),
                new MenuRequest.BulkCreate(java.util.List.of("콩국수", "가".repeat(101)))))
                .isInstanceOf(com.nameless0422.MenuPick.common.exception.BusinessException.class);
        assertThat(menuRepository.findAllByUserIdAndDeletedAtIsNull(user.getId()))
                .extracting(Menu::getName).doesNotContain("콩국수");
    }

    @Test
    void bulkPreview_comparesExactNamesWithinActiveUserMenus() {
        User user = userRepository.save(
                User.builder().email("bulk-preview@test.com").nickname("미리보기유저").build());
        User other = userRepository.save(
                User.builder().email("bulk-preview-other@test.com").nickname("다른미리보기유저").build());
        menuRepository.save(Menu.builder().user(user).name("Soup").build());
        Menu deleted = menuRepository.save(Menu.builder().user(user).name("파스타").build());
        deleted.softDelete(java.time.LocalDateTime.now());
        menuRepository.save(deleted);
        menuRepository.save(Menu.builder().user(other).name("라멘").build());

        var preview = menuService.previewBulkCreate(user.getId(), new MenuRequest.BulkCreate(
                java.util.List.of("Soup", "soup", "파스타", "라멘")));

        assertThat(preview.entries()).extracting(MenuResponse.BulkEntry::status)
                .containsExactly("EXISTING", "ADD", "ADD", "ADD");
    }

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
    void menuSearchKeepsCursorAndTreatsLikeCharactersAsText() {
        User user = userRepository.save(
                User.builder().email("menu-search@test.com").nickname("메뉴검색실험유저").build());
        User other = userRepository.save(
                User.builder().email("menu-search-other@test.com").nickname("다른검색유저").build());
        menuRepository.save(Menu.builder().user(user).name("국수").build());
        menuRepository.save(Menu.builder().user(user).name("비빔국수").build());
        menuRepository.save(Menu.builder().user(user).name("100% 국수").build());
        menuRepository.save(Menu.builder().user(user).name("국_수").build());
        Menu deleted = menuRepository.save(Menu.builder().user(user).name("잔치국수").build());
        deleted.softDelete(java.time.LocalDateTime.now());
        menuRepository.save(deleted);
        menuRepository.save(Menu.builder().user(other).name("냉국수").build());

        var first = menuService.getMenus(user.getId(), null, 2, " 국수 ");
        var second = menuService.getMenus(user.getId(), first.nextCursor(), 2, "국수");

        assertThat(first.hasNext()).isTrue();
        assertThat(first.menus()).extracting(MenuResponse.MenuSummary::name)
                .containsExactly("100% 국수", "비빔국수");
        assertThat(second.menus()).extracting(MenuResponse.MenuSummary::name)
                .containsExactly("국수");
        assertThat(menuService.getMenus(user.getId(), null, 20, "%").menus())
                .extracting(MenuResponse.MenuSummary::name).containsExactly("100% 국수");
        assertThat(menuService.getMenus(user.getId(), null, 20, "_").menus())
                .extracting(MenuResponse.MenuSummary::name).containsExactly("국_수");
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
