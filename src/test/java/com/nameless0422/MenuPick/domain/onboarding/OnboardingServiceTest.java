package com.nameless0422.MenuPick.domain.onboarding;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 첫 사용자 안내를 <b>언제 띄울지</b>.
 *
 * <p>"온보딩 완료" 컬럼을 두지 않고 이미 있는 데이터로 판정하기로 했으므로(D-049), 그 판정이
 * 이 파일의 전부다. 틀리면 두 방향 다 나쁘다 — 쓰던 사람에게 다시 뜨거나, 새 사용자가
 * 아무 안내 없이 빈손으로 시작한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class OnboardingServiceTest extends AbstractIntegrationTest {

    @Autowired private MenuRepository menuRepository;
    @Autowired private HistoryRepository historyRepository;
    @Autowired private UserRepository userRepository;

    private OnboardingService service;
    private User user;
    private int seq;

    @BeforeEach
    void setUp() {
        service = new OnboardingService(menuRepository, historyRepository);
        user = newUser();
    }

    private User newUser() {
        seq++;
        return userRepository.save(User.builder()
                .email("onboard" + seq + "@example.com").nickname("onboard" + seq).build());
    }

    private Menu menu(User owner, String name) {
        Menu menu = Menu.builder().user(owner).name(name).build();
        menu.addCategory("한식");
        return menuRepository.save(menu);
    }

    @Test
    @DisplayName("막 가입한 사용자에게는 안내가 필요하다")
    void neededForNewUser() {
        menu(user, "김치찌개");
        menu(user, "된장찌개");

        OnboardingService.OnboardingResponse status = service.status(user.getId());

        assertThat(status.needed()).isTrue();
        assertThat(status.menus()).extracting(OnboardingService.OnboardingResponse.Item::name)
                .containsExactly("김치찌개", "된장찌개");
        assertThat(status.menus().get(0).categories()).containsExactly("한식");
    }

    /** 한 번이라도 뽑았으면 이미 쓰기 시작한 사용자다. 다시 붙잡지 않는다. */
    @Test
    @DisplayName("픽한 적이 있으면 더 묻지 않는다")
    void notNeededAfterFirstPick() {
        Menu menu = menu(user, "김치찌개");
        historyRepository.save(History.builder().user(user).menu(menu)
                .recommendedAt(LocalDateTime.of(2026, 9, 19, 12, 0)).build());

        assertThat(service.status(user.getId()).needed()).isFalse();
    }

    /** 이미 안내를 마쳤거나 메뉴 화면에서 직접 뺀 사람이다. */
    @Test
    @DisplayName("제외해 둔 메뉴가 있으면 더 묻지 않는다")
    void notNeededWhenAlreadyExcluded() {
        Menu excluded = menu(user, "마라탕");
        excluded.exclude();
        menuRepository.save(excluded);

        assertThat(service.status(user.getId()).needed()).isFalse();
    }

    /** 남의 활동으로 내 안내가 사라지면, 새 사용자가 빈손으로 시작한다. */
    @Test
    @DisplayName("남의 픽·제외는 내 판정에 영향을 주지 않는다")
    void otherUsersDoNotAffectMe() {
        User other = newUser();
        Menu theirs = menu(other, "김치찌개");
        theirs.exclude();
        menuRepository.save(theirs);
        historyRepository.save(History.builder().user(other).menu(theirs)
                .recommendedAt(LocalDateTime.of(2026, 9, 19, 12, 0)).build());
        menu(user, "된장찌개");

        OnboardingService.OnboardingResponse status = service.status(user.getId());

        assertThat(status.needed()).isTrue();
        assertThat(status.menus()).extracting(OnboardingService.OnboardingResponse.Item::name)
                .containsExactly("된장찌개");
    }

    @Test
    @DisplayName("지운 메뉴는 목록에 넣지 않는다")
    void skipsDeletedMenus() {
        Menu deleted = menu(user, "삭제메뉴");
        deleted.softDelete(LocalDateTime.of(2026, 9, 19, 12, 0));
        menuRepository.save(deleted);
        menu(user, "김치찌개");

        assertThat(service.status(user.getId()).menus())
                .extracting(OnboardingService.OnboardingResponse.Item::name)
                .containsExactly("김치찌개");
    }
}
