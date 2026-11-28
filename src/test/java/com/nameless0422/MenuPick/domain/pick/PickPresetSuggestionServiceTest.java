package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.pick.dto.PickPresetResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickPresetSuggestionServiceTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 2026-01-15(목) 12:30 — 점심. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 15, 12, 30);

    @Autowired private HistoryRepository historyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private PickPresetRepository presetRepository;
    @Autowired private EntityManager entityManager;

    private PickPresetSuggestionService service;
    private User me;
    private Menu menu;

    @BeforeEach
    void setUp() {
        service = new PickPresetSuggestionService(historyRepository,
                Clock.fixed(ZonedDateTime.of(NOW, KST).toInstant(), KST));
        me = userRepository.save(User.builder().email("me@example.com").nickname("나").build());
        menu = menuRepository.save(Menu.builder().user(me).name("김치찌개").weight(1).build());
    }

    private PickPreset preset(String name) {
        PickPreset preset = PickPreset.builder().user(me).name(name).build();
        return presetRepository.save(preset);
    }

    private void used(PickPreset preset, LocalDateTime at) {
        History history = History.builder().user(me).menu(menu).recommendedAt(at).build();
        history.markFromPreset(preset.getId());
        historyRepository.save(history);
    }

    private List<PickPresetResponse.Detail> details(PickPreset... presets) {
        entityManager.flush();
        List<PickPresetResponse.Detail> result = new ArrayList<>();
        for (PickPreset p : presets) {
            result.add(PickPresetResponse.Detail.from(p));
        }
        return result;
    }

    @Test
    @DisplayName("지금 끼니(점심)에 가장 많이 쓴 빠른 픽을 제안한다 — 다른 끼니의 사용은 세지 않는다")
    void suggestsMostUsedInCurrentMealSlot() {
        PickPreset lunch = preset("회사 점심");
        PickPreset dinner = preset("혼밥 저녁");
        used(lunch, NOW.minusDays(1).withHour(12));
        used(lunch, NOW.minusDays(3).withHour(13));
        used(dinner, NOW.minusDays(1).withHour(19));
        used(dinner, NOW.minusDays(2).withHour(19));
        used(dinner, NOW.minusDays(4).withHour(20));

        PickPresetResponse.Suggestion suggestion = service.suggest(me.getId(), details(lunch, dinner));

        assertThat(suggestion).isNotNull();
        assertThat(suggestion.presetId()).isEqualTo(lunch.getId());
        assertThat(suggestion.mealSlot()).isEqualTo("LUNCH");
        assertThat(suggestion.uses()).isEqualTo(2);
    }

    @Test
    @DisplayName("한 번만 쓴 것은 '자주'가 아니라 제안하지 않는다")
    void needsAtLeastTwoUses() {
        PickPreset lunch = preset("회사 점심");
        used(lunch, NOW.minusDays(1).withHour(12));

        assertThat(service.suggest(me.getId(), details(lunch))).isNull();
    }

    @Test
    @DisplayName("60일보다 오래된 사용은 세지 않는다")
    void ignoresOldUses() {
        PickPreset lunch = preset("회사 점심");
        used(lunch, NOW.minusDays(61).withHour(12));
        used(lunch, NOW.minusDays(70).withHour(12));
        used(lunch, NOW.minusDays(2).withHour(12));

        assertThat(service.suggest(me.getId(), details(lunch))).isNull();
    }

    @Test
    @DisplayName("횟수가 같으면 최근에 쓴 쪽을 고른다")
    void tieBreaksByRecency() {
        PickPreset older = preset("한식");
        PickPreset newer = preset("분식");
        used(older, NOW.minusDays(5).withHour(12));
        used(older, NOW.minusDays(6).withHour(12));
        used(newer, NOW.minusDays(1).withHour(12));
        used(newer, NOW.minusDays(7).withHour(12));

        assertThat(service.suggest(me.getId(), details(older, newer)).presetId()).isEqualTo(newer.getId());
    }

    @Test
    @DisplayName("검토가 필요한(실행이 막힌) 빠른 픽은 제안하지 않는다")
    void skipsPresetsNeedingReview() {
        PickPreset lunch = preset("회사 점심");
        used(lunch, NOW.minusDays(1).withHour(12));
        used(lunch, NOW.minusDays(2).withHour(12));
        lunch.markNeedsReview();

        assertThat(service.suggest(me.getId(), details(lunch))).isNull();
    }

    @Test
    @DisplayName("목록에 없는(지워진) 빠른 픽의 기록은 무시한다")
    void ignoresDeletedPresets() {
        PickPreset gone = preset("지운 것");
        PickPreset kept = preset("남은 것");
        used(gone, NOW.minusDays(1).withHour(12));
        used(gone, NOW.minusDays(2).withHour(12));

        assertThat(service.suggest(me.getId(), details(kept))).isNull();
    }
}
