package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MenuServiceTest {

    @Mock private MenuRepository menuRepository;
    @Mock private TagRepository tagRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private MenuService menuService;

    private User user;
    private User otherUser;
    private Menu menu;

    @BeforeEach
    void setUp() throws Exception {
        user = User.builder().email("test@test.com").nickname("tester").build();
        setId(user, 1L);

        otherUser = User.builder().email("other@test.com").nickname("other").build();
        setId(otherUser, 2L);

        menu = Menu.builder().user(user).name("김치찌개").memo("맛있음").weight(3).build();
        setId(menu, 1L);
    }

    // --- 목록 조회 ---

    @Test
    @DisplayName("메뉴 목록 조회 - 첫 페이지")
    void getMenus_firstPage_success() throws Exception {
        List<Menu> menus = createMenuList(3);
        given(menuRepository.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(
                eq(1L), any(Pageable.class))).willReturn(menus);

        MenuResponse.MenuListResponse result = menuService.getMenus(1L, null, 20);

        assertThat(result.menus()).hasSize(3);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.nextCursor()).isNull();
    }

    @Test
    @DisplayName("메뉴 목록 조회 - cursor로 다음 페이지")
    void getMenus_withCursor_success() throws Exception {
        List<Menu> menus = createMenuList(3);
        given(menuRepository.findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
                eq(1L), eq(10L), any(Pageable.class))).willReturn(menus);

        MenuResponse.MenuListResponse result = menuService.getMenus(1L, 10L, 20);

        assertThat(result.menus()).hasSize(3);
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("메뉴 목록 조회 - 다음 페이지 존재 (hasNext=true)")
    void getMenus_hasNext_true() throws Exception {
        // size=2인데 3개 반환 → hasNext=true
        List<Menu> menus = createMenuList(3);
        given(menuRepository.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(
                eq(1L), any(Pageable.class))).willReturn(menus);

        MenuResponse.MenuListResponse result = menuService.getMenus(1L, null, 2);

        assertThat(result.menus()).hasSize(2);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isNotNull();
    }

    // --- 상세 조회 ---

    @Test
    @DisplayName("메뉴 상세 조회 성공")
    void getMenu_success() {
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        MenuResponse.MenuDetail result = menuService.getMenu(1L, 1L);

        assertThat(result.name()).isEqualTo("김치찌개");
        assertThat(result.memo()).isEqualTo("맛있음");
    }

    @Test
    @DisplayName("메뉴 상세 조회 - 미존재 시 MENU_NOT_FOUND")
    void getMenu_notFound() {
        given(menuRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.getMenu(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
    }

    @Test
    @DisplayName("메뉴 상세 조회 - 타 사용자 접근 시 MENU_ACCESS_DENIED")
    void getMenu_otherUser_forbidden() {
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        assertThatThrownBy(() -> menuService.getMenu(2L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_ACCESS_DENIED);
    }

    @Test
    @DisplayName("메뉴 상세 조회 - 삭제된 메뉴는 MENU_NOT_FOUND")
    void getMenu_deleted_notFound() {
        menu.softDelete();
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        assertThatThrownBy(() -> menuService.getMenu(1L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
    }

    // --- 생성 ---

    @Test
    @DisplayName("메뉴 생성 성공")
    void createMenu_success() throws Exception {
        given(userRepository.getReferenceById(1L)).willReturn(user);
        Tag tag = Tag.builder().user(user).name("혼밥").build();
        setId(tag, 10L);
        given(tagRepository.findAllByIdInAndUserId(Set.of(10L), 1L)).willReturn(List.of(tag));
        given(menuRepository.save(any(Menu.class))).willAnswer(inv -> {
            Menu saved = inv.getArgument(0);
            setId(saved, 100L);
            return saved;
        });

        MenuResponse.MenuDetail result = menuService.createMenu(1L,
                new MenuRequest.Create("된장찌개", "집밥", 2, Set.of("한식"), Set.of(10L)));

        assertThat(result.name()).isEqualTo("된장찌개");
        assertThat(result.categories()).contains("한식");
        assertThat(result.tags()).hasSize(1);
    }

    @Test
    @DisplayName("메뉴 생성 - 존재하지 않는 태그 시 TAG_NOT_FOUND")
    void createMenu_invalidTag_throwsException() {
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(tagRepository.findAllByIdInAndUserId(Set.of(999L), 1L)).willReturn(List.of());

        assertThatThrownBy(() -> menuService.createMenu(1L,
                new MenuRequest.Create("된장찌개", "", 1, Set.of(), Set.of(999L))))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TAG_NOT_FOUND);
    }

    // --- 수정 ---

    @Test
    @DisplayName("메뉴 수정 성공")
    void updateMenu_success() {
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        MenuResponse.MenuDetail result = menuService.updateMenu(1L, 1L,
                new MenuRequest.Update("수정된 메뉴", "수정 메모", 5, true, Set.of("양식"), null));

        assertThat(result.name()).isEqualTo("수정된 메뉴");
        assertThat(result.weight()).isEqualTo(5);
        assertThat(result.isExcluded()).isTrue();
        assertThat(result.categories()).contains("양식");
    }

    @Test
    @DisplayName("메뉴 수정 - 타 사용자 접근 시 MENU_ACCESS_DENIED")
    void updateMenu_otherUser_forbidden() {
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        assertThatThrownBy(() -> menuService.updateMenu(2L, 1L,
                new MenuRequest.Update("수정", "", 1, false, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_ACCESS_DENIED);
    }

    // --- 삭제 ---

    @Test
    @DisplayName("메뉴 삭제 성공")
    void deleteMenu_success() {
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        menuService.deleteMenu(1L, 1L);

        assertThat(menu.isDeleted()).isTrue();
    }

    @Test
    @DisplayName("메뉴 삭제 - 미존재 시 MENU_NOT_FOUND")
    void deleteMenu_notFound() {
        given(menuRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> menuService.deleteMenu(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
    }

    // --- 일괄 가중치 수정 ---

    @Test
    @DisplayName("일괄 가중치 수정 성공")
    void batchUpdateWeight_success() throws Exception {
        Menu menu2 = Menu.builder().user(user).name("된장찌개").memo("").weight(1).build();
        setId(menu2, 2L);

        given(menuRepository.findAllByIdInAndUserIdAndDeletedAtIsNull(List.of(1L, 2L), 1L))
                .willReturn(List.of(menu, menu2));

        var entries = List.of(
                new MenuRequest.WeightEntry(1L, 5),
                new MenuRequest.WeightEntry(2L, 3));

        menuService.batchUpdateWeight(1L, new MenuRequest.BatchUpdateWeight(entries));

        assertThat(menu.getWeight()).isEqualTo(5);
        assertThat(menu2.getWeight()).isEqualTo(3);
    }

    @Test
    @DisplayName("일괄 가중치 수정 - 일부 메뉴 미존재 시 MENU_NOT_FOUND")
    void batchUpdateWeight_partialNotFound() {
        given(menuRepository.findAllByIdInAndUserIdAndDeletedAtIsNull(List.of(1L, 999L), 1L))
                .willReturn(List.of(menu));

        var entries = List.of(
                new MenuRequest.WeightEntry(1L, 5),
                new MenuRequest.WeightEntry(999L, 3));

        assertThatThrownBy(() -> menuService.batchUpdateWeight(1L, new MenuRequest.BatchUpdateWeight(entries)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
    }

    // --- 제외 목록 ---

    @Test
    @DisplayName("제외 목록 조회 성공")
    void getExcludedMenus_success() throws Exception {
        Menu excluded = Menu.builder().user(user).name("제외메뉴").memo("").weight(1).build();
        setId(excluded, 10L);
        excluded.exclude();

        given(menuRepository.findAllByUserIdAndIsExcludedTrueAndDeletedAtIsNullOrderByIdDesc(1L))
                .willReturn(List.of(excluded));

        List<MenuResponse.MenuSummary> result = menuService.getExcludedMenus(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).isExcluded()).isTrue();
    }

    // --- 제외 토글 ---

    @Test
    @DisplayName("메뉴 제외 토글 - exclude")
    void toggleExclude_exclude() {
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        menuService.toggleExclude(1L, 1L, true);

        assertThat(menu.isExcluded()).isTrue();
    }

    @Test
    @DisplayName("메뉴 제외 토글 - include")
    void toggleExclude_include() {
        menu.exclude();
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        menuService.toggleExclude(1L, 1L, false);

        assertThat(menu.isExcluded()).isFalse();
    }

    @Test
    @DisplayName("메뉴 제외 토글 - 타 사용자 접근 시 MENU_ACCESS_DENIED")
    void toggleExclude_otherUser_forbidden() {
        given(menuRepository.findById(1L)).willReturn(Optional.of(menu));

        assertThatThrownBy(() -> menuService.toggleExclude(2L, 1L, true))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_ACCESS_DENIED);
    }

    // --- Helper ---

    private List<Menu> createMenuList(int count) throws Exception {
        List<Menu> menus = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Menu m = Menu.builder().user(user).name("메뉴" + i).memo("").weight(1).build();
            setId(m, (long) i);
            menus.add(m);
        }
        return menus;
    }

    private void setId(Object entity, Long id) throws Exception {
        Field idField = entity.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }
}
