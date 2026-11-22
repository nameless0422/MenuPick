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
    @Mock private com.nameless0422.MenuPick.domain.tag.TagRepository tagRepository;
    @Mock private com.nameless0422.MenuPick.domain.nutrition.FoodReferenceRepository foodReferenceRepository;

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
    @DisplayName("기본 메뉴에는 1인분 열량과 영양소 추정치가 직접 입력값으로 들어간다")
    void seededMenusCarryNutritionEstimates() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(false);

        provisioner.provision(user);

        List<Menu> menus = savedMenus();
        assertThat(menus).allSatisfy(menu -> {
            assertThat(menu.getKcalPerServing()).isPositive();
            assertThat(menu.getFoodReferenceId()).isNull();
            Macros macros = menu.getMacros();
            assertThat(macros.hasMacros()).isTrue();
            assertThat(macros.fiberG()).isNotNull();
            assertThat(macros.sugarsG()).isNotNull();
            assertThat(macros.sodiumMg()).isNotNull();
        });
        Menu kimchi = menus.stream()
                .filter(menu -> menu.getName().equals("김치찌개")).findFirst().orElseThrow();
        assertThat(kimchi.getKcalPerServing()).isEqualTo(230);
        assertThat(kimchi.getMacros().sodiumMg()).isEqualTo(1900);
    }

    @Test
    @DisplayName("기준 음식 데이터가 있으면 그 행과 연결하고, 없는 메뉴는 추정치를 그대로 쓴다(D-090)")
    void linksPresetsToFoodReferencesWhenPresent() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(false);
        com.nameless0422.MenuPick.domain.nutrition.FoodReference kimchi =
                com.nameless0422.MenuPick.domain.nutrition.FoodReference.of("D306-266000000-0001", "김치찌개", "찌개",
                        new java.math.BigDecimal("400.0"), 244, java.time.LocalDateTime.of(2026, 11, 22, 0, 0))
                        .withMacros(new java.math.BigDecimal("11.9"), new java.math.BigDecimal("15.1"),
                                new java.math.BigDecimal("15.0"), null)
                        .withSugarsAndSodium(null, 1964);
        ReflectionTestUtils.setField(kimchi, "id", 42L);
        given(foodReferenceRepository.findByCodeIn(anyList())).willReturn(List.of(kimchi));

        provisioner.provision(user);

        List<Menu> menus = savedMenus();
        Menu linked = menus.stream().filter(menu -> menu.getName().equals("김치찌개")).findFirst().orElseThrow();
        assertThat(linked.getFoodReferenceId()).isEqualTo(42L);
        assertThat(linked.getKcalPerServing()).isEqualTo(244);
        assertThat(linked.getMacros().sodiumMg()).isEqualTo(1964);
        Menu estimated = menus.stream().filter(menu -> menu.getName().equals("된장찌개")).findFirst().orElseThrow();
        assertThat(estimated.getFoodReferenceId()).as("DB에 행이 없는 코드").isNull();
        assertThat(estimated.getKcalPerServing()).isEqualTo(150);
    }

    @Test
    @DisplayName("V28 연결 SQL의 이름·코드·추정치가 기본 메뉴 목록과 같다")
    void linkMigrationMatchesPresets() throws java.io.IOException {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V28__food_reference_unit_and_default_links.sql"));
        List<DefaultMenus.Preset> linked = DefaultMenus.PRESETS.stream().filter(p -> p.foodCode() != null).toList();
        assertThat(linked).hasSize(17);
        assertThat(linked).allSatisfy(preset -> {
            Macros m = preset.macros();
            String row = String.format(java.util.Locale.ROOT, "('%s', '%s', %d, %.1f, %.1f, %.1f, %.1f, %.1f, %d)",
                    preset.name(), preset.foodCode(), preset.kcalPerServing(), m.carbsG(), m.proteinG(), m.fatG(),
                    m.fiberG(), m.sugarsG(), m.sodiumMg());
            assertThat(sql).contains(row);
        });
    }

    @Test
    @DisplayName("기본 메뉴 영양소는 저장 검증 범위 안이고 열량이 탄단지와 맞는다")
    void presetNutritionIsWithinValidationAndConsistent() {
        assertThat(DefaultMenus.PRESETS).allSatisfy(preset -> {
            Macros m = preset.macros();
            // NutritionDto.MenuNutritionRequest의 범위: 열량 1~5000, 나트륨 0~20000
            assertThat(preset.kcalPerServing()).isBetween(1, 5000);
            assertThat(m.sodiumMg()).isBetween(0, 20000);
            double estimate = m.carbsG().doubleValue() * 4 + m.proteinG().doubleValue() * 4
                    + m.fatG().doubleValue() * 9;
            assertThat(estimate).isCloseTo(preset.kcalPerServing(),
                    org.assertj.core.data.Percentage.withPercentage(3));
        });
    }

    @Test
    @DisplayName("V27 백필 SQL의 값이 기본 메뉴 목록과 같다")
    void backfillMigrationMatchesPresets() throws java.io.IOException {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/resources/db/migration/V27__default_menu_nutrition.sql"));
        assertThat(DefaultMenus.PRESETS).allSatisfy(preset -> {
            Macros m = preset.macros();
            String row = String.format(java.util.Locale.ROOT, "('%s', %d, %.1f, %.1f, %.1f, %.1f, %.1f, %d)",
                    preset.name(), preset.kcalPerServing(), m.carbsG(), m.proteinG(), m.fatG(),
                    m.fiberG(), m.sugarsG(), m.sodiumMg());
            assertThat(sql).contains(row);
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

    /**
     * 태그가 0개인 계정에서는 픽의 포함·제외 필터, 기본 제외 태그, 빠른 픽의 태그 조건이 전부
     * 아무 일도 하지 않는다. 기본 메뉴와 같은 이유로 처음부터 넣어 준다.
     */
    @Test
    @DisplayName("기본 태그도 함께 만들고 해당 메뉴에 붙인다")
    void seedsDefaultTags() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(false);

        provisioner.provision(user);

        ArgumentCaptor<java.util.Collection<com.nameless0422.MenuPick.domain.tag.Tag>> tagCaptor =
                ArgumentCaptor.forClass(java.util.Collection.class);
        verify(tagRepository).saveAll(tagCaptor.capture());
        assertThat(tagCaptor.getValue())
                .extracting(com.nameless0422.MenuPick.domain.tag.Tag::getName)
                .containsExactlyInAnyOrderElementsOf(DefaultTags.NAMES);

        Menu kimchi = savedMenus().stream()
                .filter(menu -> menu.getName().equals("김치찌개")).findFirst().orElseThrow();
        assertThat(kimchi.getTags())
                .extracting(com.nameless0422.MenuPick.domain.tag.Tag::getName)
                .containsExactlyInAnyOrderElementsOf(DefaultTags.MENU_TAGS.get("김치찌개"));
    }

    /** 애매한 메뉴에 억지로 붙이면, 그 태그로 거르는 사람이 "왜 이게 걸렸지"를 겪는다. */
    @Test
    @DisplayName("목록에 없는 메뉴에는 태그를 붙이지 않는다")
    void leavesUnmappedMenusUntagged() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(false);

        provisioner.provision(user);

        Menu pork = savedMenus().stream()
                .filter(menu -> menu.getName().equals("삼겹살")).findFirst().orElseThrow();
        assertThat(pork.getTags()).isEmpty();
    }

    @Test
    @DisplayName("이미 메뉴가 있는 계정에는 태그도 넣지 않는다")
    void skipsTagsWhenMenusExist() {
        User user = user(1L);
        given(menuRepository.existsByUserId(1L)).willReturn(true);

        provisioner.provision(user);

        verify(tagRepository, never()).saveAll(anyList());
    }
}
