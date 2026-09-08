package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
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
import java.time.ZonedDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KPI 집계. 셋 다 <b>분모를 잘못 잡으면 조용히 그럴듯한 숫자가 나오는</b> 종류라,
 * 경계에 있는 사용자를 일부러 넣어 두고 센다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class KpiServiceTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 모든 시각을 이 순간 기준으로 계산한다 — "7일 전"이 실행 시각마다 흔들리면 안 된다. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);
    private static final Clock FIXED = Clock.fixed(
            ZonedDateTime.of(NOW, KST).toInstant(), KST);

    @Autowired private HistoryRepository historyRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private UserRepository userRepository;

    private KpiService kpiService;
    private int seq;

    @BeforeEach
    void setUp() {
        kpiService = new KpiService(historyRepository, FIXED);
        seq = 0;
    }

    private User newUser() {
        seq++;
        return userRepository.save(User.builder()
                .email("kpi" + seq + "@example.com").nickname("kpi" + seq).build());
    }

    /**
     * 방문 여부를 빌더로 넣지 않는다 — {@code History}는 방문을 생성 시점의 값이 아니라
     * {@code markVisited()} 상태 전이로만 다룬다. 실제 서비스가 지나는 경로와 같게 둔다.
     */
    private void pick(User user, LocalDateTime at, boolean visited) {
        Menu menu = menuRepository.save(Menu.builder().user(user).name("메뉴" + (++seq)).build());
        History history = History.builder().user(user).menu(menu).recommendedAt(at).build();
        if (visited) {
            history.markVisited(at.plusHours(2));
        }
        historyRepository.save(history);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> metric(Map<String, Object> all, String name) {
        return (Map<String, Object>) all.get(name);
    }

    // --- 픽 후 방문율 ---

    @Test
    @DisplayName("방문율 - 방문 처리된 픽의 비율을 분자·분모와 함께 준다")
    void visitRate() {
        User u = newUser();
        pick(u, NOW.minusDays(1), true);
        pick(u, NOW.minusDays(2), false);
        pick(u, NOW.minusDays(3), false);
        pick(u, NOW.minusDays(4), true);

        Map<String, Object> m = metric(kpiService.collect(30), "visitRate");

        assertThat(m.get("picks")).isEqualTo(4L);
        assertThat(m.get("visited")).isEqualTo(2L);
        assertThat(m.get("rate")).isEqualTo(0.5);
        assertThat(m.get("meetsTarget")).isEqualTo(true);
    }

    @Test
    @DisplayName("방문율 - 집계 기간 밖의 픽은 분모에도 들어가지 않는다")
    void visitRate_excludesOutsideWindow() {
        User u = newUser();
        pick(u, NOW.minusDays(2), true);
        pick(u, NOW.minusDays(40), false);   // 30일 창 밖

        Map<String, Object> m = metric(kpiService.collect(30), "visitRate");

        assertThat(m.get("picks")).isEqualTo(1L);
        assertThat(m.get("rate")).isEqualTo(1.0);
    }

    /**
     * 표본이 없을 때 0.0을 주면 화면에서 "방문율 0% — 목표 미달"로 읽힌다.
     * 아직 아무도 픽하지 않은 것과 픽했는데 아무도 안 간 것은 완전히 다른 상태다.
     */
    @Test
    @DisplayName("표본이 없으면 비율은 null이다 - 0.0이 아니다")
    void emptySample_ratesAreNull() {
        Map<String, Object> all = kpiService.collect(30);

        assertThat(metric(all, "visitRate").get("picks")).isEqualTo(0L);
        assertThat(metric(all, "visitRate").get("rate")).isNull();
        assertThat(metric(all, "visitRate").get("meetsTarget")).isNull();
        assertThat(metric(all, "sameDayRepickRate").get("rate")).isNull();
        assertThat(metric(all, "sevenDayRetention").get("rate")).isNull();
    }

    // --- 재픽률 ---

    @Test
    @DisplayName("재픽률 - 같은 날 두 번 이상 픽한 사용자만 분자에 넣는다")
    void sameDayRepick() {
        User repicker = newUser();
        pick(repicker, NOW.minusDays(1).withHour(9), false);
        pick(repicker, NOW.minusDays(1).withHour(18), false);   // 같은 날 두 번

        User spreader = newUser();
        pick(spreader, NOW.minusDays(2).withHour(9), false);
        pick(spreader, NOW.minusDays(3).withHour(9), false);    // 다른 날 → 재픽 아님

        Map<String, Object> m = metric(kpiService.collect(30), "sameDayRepickRate");

        assertThat(m.get("users")).isEqualTo(2L);
        assertThat(m.get("repickedUsers")).isEqualTo(1L);
        assertThat(m.get("rate")).isEqualTo(0.5);
    }

    /** 낮을수록 좋은 유일한 지표다. 방향을 잃으면 50%를 "목표 달성"으로 읽는다. */
    @Test
    @DisplayName("재픽률은 낮을수록 좋다 - 목표 판정 방향이 반대다")
    void sameDayRepick_lowerIsBetter() {
        User u = newUser();
        pick(u, NOW.minusDays(1).withHour(9), false);
        pick(u, NOW.minusDays(1).withHour(18), false);

        Map<String, Object> m = metric(kpiService.collect(30), "sameDayRepickRate");

        assertThat(m.get("higherIsBetter")).isEqualTo(false);
        // 재픽률 100%는 목표(≤20%) 미달이다
        assertThat(m.get("rate")).isEqualTo(1.0);
        assertThat(m.get("meetsTarget")).isEqualTo(false);
    }

    // --- 7일 리텐션 ---

    @Test
    @DisplayName("리텐션 - 첫 픽 후 7일 안에 다시 오면 유지로 센다")
    void retention_countsReturnWithinWindow() {
        User returned = newUser();
        pick(returned, NOW.minusDays(20), false);
        pick(returned, NOW.minusDays(15), false);   // 첫 픽 +5일

        User gone = newUser();
        pick(gone, NOW.minusDays(20), false);
        pick(gone, NOW.minusDays(9), false);        // 첫 픽 +11일 → 창 밖

        Map<String, Object> m = metric(kpiService.collect(30), "sevenDayRetention");

        assertThat(m.get("cohort")).isEqualTo(2L);
        assertThat(m.get("retained")).isEqualTo(1L);
        assertThat(m.get("rate")).isEqualTo(0.5);
    }

    /**
     * 이 테스트가 이 지표의 핵심이다. 첫 픽이 아직 7일도 안 지난 사용자를 분모에 넣으면,
     * 돌아올 시간이 남아 있는 사람을 "안 돌아온 사람"으로 세게 된다 —
     * <b>신규 가입이 늘수록 리텐션이 떨어지는</b> 착시가 생긴다.
     */
    @Test
    @DisplayName("리텐션 - 7일이 지나지 않은 사용자는 분모에서 뺀다")
    void retention_excludesUsersWhoseWindowHasNotElapsed() {
        User settled = newUser();
        pick(settled, NOW.minusDays(20), false);
        pick(settled, NOW.minusDays(18), false);

        User tooRecent = newUser();
        pick(tooRecent, NOW.minusDays(2), false);   // 아직 5일 더 남았다

        Map<String, Object> m = metric(kpiService.collect(30), "sevenDayRetention");

        assertThat(m.get("cohort")).isEqualTo(1L);
        assertThat(m.get("retained")).isEqualTo(1L);
        assertThat(m.get("rate")).isEqualTo(1.0);
    }

    /** 첫 픽 자체를 재방문으로 세면 픽을 한 번만 한 사용자도 전원 유지로 잡혀 항상 100%가 된다. */
    @Test
    @DisplayName("리텐션 - 첫 픽 한 번뿐인 사용자는 유지가 아니다")
    void retention_firstPickAloneIsNotRetention() {
        User once = newUser();
        pick(once, NOW.minusDays(20), false);

        Map<String, Object> m = metric(kpiService.collect(30), "sevenDayRetention");

        assertThat(m.get("cohort")).isEqualTo(1L);
        assertThat(m.get("retained")).isEqualTo(0L);
        assertThat(m.get("rate")).isEqualTo(0.0);
    }

    /**
     * 리텐션은 {@code lookbackDays}를 따르지 않는다 — 코호트 경계가 두 겹이 되어
     * 기간 끝자락 사용자를 두 번 걸러야 하기 때문이다(KpiService 주석 참고).
     */
    @Test
    @DisplayName("리텐션은 집계 기간에 영향받지 않는다")
    void retention_ignoresLookbackWindow() {
        User old = newUser();
        pick(old, NOW.minusDays(100), false);
        pick(old, NOW.minusDays(97), false);

        Map<String, Object> shortWindow = metric(kpiService.collect(7), "sevenDayRetention");
        Map<String, Object> longWindow = metric(kpiService.collect(365), "sevenDayRetention");

        assertThat(shortWindow.get("cohort")).isEqualTo(1L);
        assertThat(shortWindow.get("retained")).isEqualTo(1L);
        assertThat(shortWindow).isEqualTo(longWindow);
    }

    @Test
    @DisplayName("아무도 7일을 넘기지 않았으면 표본 없음을 이유와 함께 알린다")
    void retention_noCohortYet() {
        User tooRecent = newUser();
        pick(tooRecent, NOW.minusDays(1), false);

        Map<String, Object> m = metric(kpiService.collect(30), "sevenDayRetention");

        assertThat(m.get("cohort")).isEqualTo(0L);
        assertThat(m.get("rate")).isNull();
        assertThat(m.get("note")).asString().contains("7일");
    }
}
