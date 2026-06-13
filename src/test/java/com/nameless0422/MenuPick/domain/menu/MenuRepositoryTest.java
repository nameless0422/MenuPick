package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.nameless0422.MenuPick.common.config.JpaConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class MenuRepositoryTest {

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
        menu3.softDelete();
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
}
