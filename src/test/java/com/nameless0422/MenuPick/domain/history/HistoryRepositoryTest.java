package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
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
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class HistoryRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private HistoryRepository historyRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MenuRepository menuRepository;

    private User user;
    private Menu menu;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .email("history@example.com")
                .nickname("히스토리유저")
                .build());
        menu = menuRepository.save(Menu.builder()
                .user(user).name("김치찌개").weight(1).build());
    }

    @Test
    @DisplayName("히스토리를 저장하고 필터 조건과 함께 조회한다")
    void save_with_filterConditions() {
        History history = History.builder()
                .user(user)
                .menu(menu)
                .recommendedAt(LocalDateTime.now())
                .build();
        history.addFilterCondition("CATEGORY", "KOREAN");
        history.addFilterCondition("TAG_INCLUDE", "혼밥가능");

        History saved = historyRepository.save(history);

        History found = historyRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getFilterConditions()).hasSize(2);
        assertThat(found.getFilterConditions())
                .extracting(HistoryFilterCondition::getFilterType)
                .containsExactlyInAnyOrder("CATEGORY", "TAG_INCLUDE");
    }

    @Test
    @DisplayName("방문 처리를 하면 isVisited와 visitedAt이 설정된다")
    void markVisited() {
        History history = historyRepository.save(History.builder()
                .user(user).menu(menu)
                .recommendedAt(LocalDateTime.now())
                .build());

        history.markVisited();
        historyRepository.flush();

        History found = historyRepository.findById(history.getId()).orElseThrow();
        assertThat(found.isVisited()).isTrue();
        assertThat(found.getVisitedAt()).isNotNull();
    }

    @Test
    @DisplayName("사용자의 최근 히스토리를 시간 역순으로 조회한다")
    void findByUserIdOrderByRecommendedAtDesc() {
        LocalDateTime now = LocalDateTime.now();
        historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now.minusDays(2)).build());
        historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now).build());
        historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now.minusDays(1)).build());

        List<History> histories = historyRepository
                .findByUserIdOrderByRecommendedAtDesc(user.getId());

        assertThat(histories).hasSize(3);
        assertThat(histories.get(0).getRecommendedAt()).isAfter(histories.get(1).getRecommendedAt());
        assertThat(histories.get(1).getRecommendedAt()).isAfter(histories.get(2).getRecommendedAt());
    }
}
