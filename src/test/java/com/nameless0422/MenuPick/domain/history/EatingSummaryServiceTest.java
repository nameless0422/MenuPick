package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
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

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 내 식사 기록 요약.
 *
 * <p>여기서 틀리면 <b>남의 기록이 내 요약에 섞이거나</b>, 안 먹은 것이 먹은 것으로 세어진다.
 * 둘 다 화면만 봐서는 그럴듯해 보이는 종류라 경계에 있는 데이터를 일부러 넣고 센다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class EatingSummaryServiceTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 19, 12, 0);
    private static final Clock FIXED = Clock.fixed(NOW.atZone(KST).toInstant(), KST);

    @Autowired private HistoryRepository historyRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private UserRepository userRepository;

    private EatingSummaryService service;
    private User user;
    private int seq;

    @BeforeEach
    void setUp() {
        service = new EatingSummaryService(historyRepository, FIXED);
        user = newUser();
    }

    private User newUser() {
        seq++;
        return userRepository.save(User.builder()
                .email("summary" + seq + "@example.com").nickname("summary" + seq).build());
    }

    private Menu menu(User owner, String name, String category) {
        Menu menu = Menu.builder().user(owner).name(name).build();
        if (category != null) menu.addCategory(category);
        return menuRepository.save(menu);
    }

    /** 뽑기만 하고 아무 신호도 남기지 않은 픽. */
    private History pick(User owner, Menu menu, LocalDateTime at) {
        return historyRepository.save(
                History.builder().user(owner).menu(menu).recommendedAt(at).build());
    }

    private void visited(User owner, Menu menu, LocalDateTime at) {
        History history = pick(owner, menu, at);
        history.markVisited(at.plusHours(1));
        historyRepository.save(history);
    }

    private void accepted(User owner, Menu menu, LocalDateTime at) {
        History history = pick(owner, menu, at);
        history.recordFeedback(RecommendationFeedback.ACCEPTED);
        historyRepository.save(history);
    }

    private HistoryResponse.EatingSummaryResponse summary() {
        return service.summarize(user.getId(), null);
    }

    @Test
    @DisplayName("픽 수와 먹은 수를 따로 준다 — 비율 하나로 줄이지 않는다")
    void countsPicksAndEatenSeparately() {
        Menu kimchi = menu(user, "김치찌개", "한식");
        visited(user, kimchi, NOW.minusDays(1));
        accepted(user, kimchi, NOW.minusDays(2));
        pick(user, kimchi, NOW.minusDays(3));   // 뽑기만 함

        HistoryResponse.EatingSummaryResponse result = summary();

        assertThat(result.picks()).isEqualTo(3);
        assertThat(result.eaten()).isEqualTo(2);
        assertThat(result.periodDays()).isEqualTo(30);
    }

    /** 남의 기록이 섞이면 숫자는 그럴듯한데 내 이야기가 아니게 된다. */
    @Test
    @DisplayName("남의 기록은 세지 않는다")
    void ignoresOtherUsers() {
        User other = newUser();
        Menu mine = menu(user, "김치찌개", "한식");
        Menu theirs = menu(other, "파스타", "양식");
        visited(user, mine, NOW.minusDays(1));
        visited(other, theirs, NOW.minusDays(1));
        visited(other, theirs, NOW.minusDays(2));

        HistoryResponse.EatingSummaryResponse result = summary();

        assertThat(result.picks()).isEqualTo(1);
        assertThat(result.categories()).extracting(HistoryResponse.LabelCount::label)
                .containsExactly("한식");
    }

    @Test
    @DisplayName("기간 밖의 기록은 세지 않는다")
    void ignoresOutsideWindow() {
        Menu kimchi = menu(user, "김치찌개", "한식");
        visited(user, kimchi, NOW.minusDays(29));
        visited(user, kimchi, NOW.minusDays(31));

        assertThat(summary().eaten()).isEqualTo(1);
    }

    /**
     * 집단 통계는 사람 수를 세지만 여기는 횟수를 센다. 내가 몇 번 먹었는지가 내가 알고 싶은
     * 값이기 때문이다 — 사람 수로 세면 내 요약의 모든 항목이 1이 된다.
     */
    @Test
    @DisplayName("카테고리와 메뉴는 횟수로 세고 많은 순으로 준다")
    void ranksByCount() {
        Menu kimchi = menu(user, "김치찌개", "한식");
        Menu bibim = menu(user, "비빔밥", "한식");
        Menu pasta = menu(user, "파스타", "양식");
        visited(user, kimchi, NOW.minusDays(1));
        visited(user, kimchi, NOW.minusDays(2));
        visited(user, bibim, NOW.minusDays(3));
        visited(user, pasta, NOW.minusDays(4));

        HistoryResponse.EatingSummaryResponse result = summary();

        assertThat(result.categories())
                .extracting(HistoryResponse.LabelCount::label, HistoryResponse.LabelCount::count)
                .containsExactly(tuple("한식", 3L), tuple("양식", 1L));
        assertThat(result.menus())
                .extracting(HistoryResponse.LabelCount::label, HistoryResponse.LabelCount::count)
                .containsExactly(tuple("김치찌개", 2L), tuple("비빔밥", 1L), tuple("파스타", 1L));
    }

    @Test
    @DisplayName("뽑기만 한 기록은 먹은 것으로 세지 않는다")
    void onlyEatenSignalsCount() {
        Menu kimchi = menu(user, "김치찌개", "한식");
        pick(user, kimchi, NOW.minusDays(1));
        History rejected = pick(user, kimchi, NOW.minusDays(2));
        rejected.recordFeedback(RecommendationFeedback.REJECTED);
        historyRepository.save(rejected);

        HistoryResponse.EatingSummaryResponse result = summary();

        assertThat(result.picks()).isEqualTo(2);
        assertThat(result.eaten()).isZero();
        assertThat(result.categories()).isEmpty();
        assertThat(result.menus()).isEmpty();
    }

    /** 이 화면의 절반은 "아직 기록이 없다"를 말하는 일이다. 0이 예외 없이 나와야 한다. */
    @Test
    @DisplayName("기록이 하나도 없어도 0으로 답한다")
    void emptyIsZeroNotError() {
        HistoryResponse.EatingSummaryResponse result = summary();

        assertThat(result.picks()).isZero();
        assertThat(result.eaten()).isZero();
        assertThat(result.categories()).isEmpty();
        assertThat(result.menus()).isEmpty();
        assertThat(result.forgottenMenus()).isEmpty();
    }

    // --- 오래 안 먹은 메뉴 ---

    @Test
    @DisplayName("한 번도 안 뽑힌 메뉴가 가장 먼저 온다")
    void neverPickedComesFirst() {
        Menu never = menu(user, "제육볶음", "한식");
        Menu old = menu(user, "김치찌개", "한식");
        Menu recent = menu(user, "비빔밥", "한식");
        pick(user, old, NOW.minusDays(60));
        pick(user, recent, NOW.minusDays(1));

        HistoryResponse.EatingSummaryResponse result = summary();

        assertThat(result.forgottenMenus())
                .extracting(HistoryResponse.ForgottenMenu::menuId)
                .containsExactly(never.getId(), old.getId(), recent.getId());
        assertThat(result.forgottenMenus().get(0).lastPickedAt()).isNull();
    }

    /**
     * 기간으로 자르면 6개월 전에 먹은 메뉴와 한 번도 안 먹은 메뉴가 똑같이 "안 먹음"으로
     * 뭉개진다. 이 목록만 전 기간을 본다.
     */
    @Test
    @DisplayName("집계 기간 밖의 픽도 마지막 시각으로 인정한다")
    void forgottenIgnoresWindow() {
        Menu old = menu(user, "김치찌개", "한식");
        pick(user, old, NOW.minusDays(200));

        assertThat(summary().forgottenMenus())
                .singleElement()
                .extracting(HistoryResponse.ForgottenMenu::lastPickedAt)
                .isNotNull();
    }

    /** 추천에서 빼 둔 메뉴와 지운 메뉴는 안 나오는 게 정상이라 "잊혔다"고 하면 틀린 말이다. */
    @Test
    @DisplayName("제외해 둔 메뉴와 지운 메뉴는 잊힌 메뉴에 넣지 않는다")
    void forgottenSkipsExcludedAndDeleted() {
        Menu excluded = menu(user, "제외메뉴", "한식");
        excluded.exclude();
        menuRepository.save(excluded);
        Menu deleted = menu(user, "삭제메뉴", "한식");
        deleted.softDelete(NOW.minusDays(1));
        menuRepository.save(deleted);
        Menu alive = menu(user, "김치찌개", "한식");

        assertThat(summary().forgottenMenus())
                .extracting(HistoryResponse.ForgottenMenu::menuId)
                .containsExactly(alive.getId());
    }

    @Test
    @DisplayName("잘못된 기간은 거절하지 않고 기본 30일로 되돌린다")
    void normalizesDays() {
        assertThat(service.summarize(user.getId(), 0).periodDays()).isEqualTo(30);
        assertThat(service.summarize(user.getId(), 100000).periodDays()).isEqualTo(30);
        assertThat(service.summarize(user.getId(), 7).periodDays()).isEqualTo(7);
    }
}
