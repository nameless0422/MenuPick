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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        history.markVisited(LocalDateTime.now());
        historyRepository.flush();

        History found = historyRepository.findById(history.getId()).orElseThrow();
        assertThat(found.isVisited()).isTrue();
        assertThat(found.getVisitedAt()).isNotNull();
    }

    /**
     * 히스토리 목록은 recommendedAt 필터 + id 역순 커서로만 조회한다(HistoryService).
     * 정렬 기준이 id DESC이므로 "최근 = id가 큰 것"이 되는지 확인한다.
     */
    @Test
    @DisplayName("기간 내 히스토리를 id 역순으로 조회한다")
    void findByUserIdAndRecommendedAtAfterOrderByIdDesc() {
        LocalDateTime now = LocalDateTime.now();
        History old = historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now.minusDays(2)).build());
        History mid = historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now.minusDays(1)).build());
        History recent = historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now).build());
        // 조회 기간(3일) 밖 — 필터로 제외되어야 한다
        historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now.minusDays(10)).build());

        List<History> histories = historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                user.getId(), now.minusDays(3), PageRequest.of(0, 10));

        assertThat(histories).extracting(History::getId)
                .containsExactly(recent.getId(), mid.getId(), old.getId());
    }

    @Test
    @DisplayName("커서(id)보다 작은 히스토리만 id 역순으로 조회한다")
    void findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc() {
        LocalDateTime now = LocalDateTime.now();
        History first = historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now.minusDays(2)).build());
        History second = historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now.minusDays(1)).build());
        History third = historyRepository.save(History.builder()
                .user(user).menu(menu).recommendedAt(now).build());

        List<History> histories = historyRepository
                .findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc(
                        user.getId(), now.minusDays(3), third.getId(), PageRequest.of(0, 10));

        assertThat(histories).extracting(History::getId)
                .containsExactly(second.getId(), first.getId());
    }

    @Test
    @DisplayName("개인화 신호는 사용자·30일 경계를 지키고 메뉴별 최신 시각과 상쇄된 피드백을 집계한다")
    void findMenuRecommendationSignalsSince_aggregatesWithBoundariesAndIsolation() {
        LocalDateTime since = LocalDateTime.of(2026, 1, 1, 0, 0);
        Menu secondMenu = menuRepository.save(Menu.builder()
                .user(user).name("초밥").weight(1).build());
        User other = userRepository.save(User.builder()
                .email("other-history@example.com").nickname("다른유저").build());
        Menu otherMenu = menuRepository.save(Menu.builder()
                .user(other).name("남의메뉴").weight(1).build());

        saveHistory(user, menu, since, RecommendationFeedback.ACCEPTED); // 경계 포함
        saveHistory(user, menu, since.plusDays(1), RecommendationFeedback.ACCEPTED);
        saveHistory(user, menu, since.plusDays(1).plusHours(1), RecommendationFeedback.ACCEPTED);
        saveHistory(user, menu, since.plusDays(1).plusHours(2), RecommendationFeedback.ACCEPTED);
        saveHistory(user, menu, since.plusDays(2), RecommendationFeedback.REJECTED);
        saveHistory(user, menu, since.plusDays(2).plusHours(1), RecommendationFeedback.REJECTED);
        saveHistory(user, menu, since.plusDays(2).plusHours(2), RecommendationFeedback.REJECTED);
        saveHistory(user, menu, since.plusDays(3), null); // NONE은 합계 0 기여
        saveHistory(user, secondMenu, since.plusDays(4), RecommendationFeedback.REJECTED);
        saveHistory(user, secondMenu, since.plusDays(5), RecommendationFeedback.ACCEPTED);
        saveHistory(user, secondMenu, since.minusSeconds(1), RecommendationFeedback.ACCEPTED);
        saveHistory(other, otherMenu, since.plusDays(6), RecommendationFeedback.ACCEPTED);
        historyRepository.save(History.builder() // null menu는 결과에서 제외
                .user(user).recommendedAt(since.plusDays(7)).build());
        historyRepository.flush();

        Map<Long, HistoryRepository.MenuRecommendationSignals> signals = historyRepository
                .findMenuRecommendationSignalsSince(user.getId(), since,
                        RecommendationFeedback.ACCEPTED, RecommendationFeedback.REJECTED)
                .stream().collect(Collectors.toMap(
                        HistoryRepository.MenuRecommendationSignals::getMenuId, Function.identity()));

        assertThat(signals).containsOnlyKeys(menu.getId(), secondMenu.getId());
        assertThat(signals.get(menu.getId()).getLatestRecommendedAt()).isEqualTo(since.plusDays(3));
        // ACCEPTED 4건과 REJECTED 3건을 먼저 상쇄한 뒤 서비스가 ±2 clamp를 적용해야 한다.
        assertThat(signals.get(menu.getId()).getFeedbackScore()).isEqualTo(1L);
        assertThat(signals.get(secondMenu.getId()).getLatestRecommendedAt()).isEqualTo(since.plusDays(5));
        assertThat(signals.get(secondMenu.getId()).getFeedbackScore()).isZero();
    }

    private History saveHistory(User owner, Menu pickedMenu, LocalDateTime recommendedAt,
                                RecommendationFeedback feedback) {
        History history = History.builder()
                .user(owner).menu(pickedMenu).recommendedAt(recommendedAt).build();
        if (feedback != null) {
            history.recordFeedback(feedback);
        }
        return historyRepository.save(history);
    }
}
