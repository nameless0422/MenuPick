package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.domain.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DefaultMenuProvisionerTest {

    @Mock private MenuRepository menuRepository;

    @InjectMocks private DefaultMenuProvisioner provisioner;

    /** id는 JPA가 채우는 값이라 리플렉션으로 넣는다(LocalAuthServiceTest와 같은 방식). */
    private User user(long id) {
        User user = User.builder().nickname("테스터").emailVerified(true).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @SuppressWarnings("unchecked")
    private List<Menu> savedMenus() {
        ArgumentCaptor<List<Menu>> captor = ArgumentCaptor.forClass(List.class);
        verify(menuRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("메뉴가 없는 계정에는 기본 목록 전체를 넣는다")
    void seedsAllPresets() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(false);

        int created = provisioner.provision(user);

        assertThat(created).isEqualTo(DefaultMenus.PRESETS.size());
        assertThat(savedMenus())
                .extracting(Menu::getName)
                .containsExactlyElementsOf(DefaultMenus.PRESETS.stream().map(DefaultMenus.Preset::name).toList());
    }

    @Test
    @DisplayName("기본 메뉴는 주인이 있고, 가중치 1 · 제외 아님 · 카테고리 하나로 들어간다")
    void seededMenusAreOrdinaryUserMenus() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(false);

        provisioner.provision(user);

        // 가중치 0으로 들어가면 그 메뉴는 영영 뽑히지 않는다 — Menu 빌더가 weight를 Integer로
        // 받는 이유(primitive 기본값 0이 필드 초기값 1을 덮어쓰는 함정)가 정확히 이 자리다.
        assertThat(savedMenus()).allSatisfy(menu -> {
            assertThat(menu.getUser()).isSameAs(user);
            assertThat(menu.getWeight()).isEqualTo(1);
            assertThat(menu.isExcluded()).isFalse();
            assertThat(menu.isDeleted()).isFalse();
            assertThat(menu.getCategories()).hasSize(1);
        });
    }

    @Test
    @DisplayName("카테고리는 화면 프리셋에 있는 값만 쓴다")
    void categoriesStayWithinFrontendPresets() {
        // frontend/src/constants.ts의 CATEGORY_PRESETS. 여기에 없는 값을 넣으면 사용자는
        // 필터 화면에서 고를 수 없는 카테고리를 자기 데이터에만 갖게 된다.
        Set<String> presets = Set.of(
                "한식", "중식", "일식", "양식", "분식", "아시안", "패스트푸드", "카페·디저트");

        assertThat(DefaultMenus.PRESETS)
                .extracting(DefaultMenus.Preset::category)
                .allSatisfy(category -> assertThat(presets).contains(category));
    }

    @Test
    @DisplayName("메뉴 이름은 서로 겹치지 않는다")
    void presetNamesAreUnique() {
        // V10 백필이 (대상 계정 × 시드 이름)으로 방금 넣은 행을 되찾아 카테고리를 붙인다.
        // 이름이 겹치면 그 조인이 한 메뉴에 카테고리를 두 개 붙인다.
        assertThat(DefaultMenus.PRESETS).extracting(DefaultMenus.Preset::name).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("이미 메뉴를 가진 계정에는 아무것도 넣지 않는다")
    void skipsAccountThatAlreadyHasMenus() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(true);

        assertThat(provisioner.provision(user)).isZero();
        verify(menuRepository, never()).saveAll(anyList());
    }
}
