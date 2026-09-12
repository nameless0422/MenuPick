package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 픽 한 번이 DB에 몇 번 다녀오는지를 고정한다.
 *
 * <h2>왜 이 테스트가 있는가</h2>
 *
 * <p>2026-09-04 부하 시험에서 450 rps를 P95 267ms로 넘겼는데, 2026-09-10 같은 조건에서는
 * 2.05s로 느려져 있었다. 그 사이 코드가 느려진 것이 아니다 — 100 rps에서는 오히려 빨라졌다
 * (61ms → 27ms). <b>픽 한 번이 던지는 조회가 늘어난 것</b>이었다. 최근 추천 중복 완화,
 * 피드백 가중치, 기본 제외 태그가 각각 조회를 하나씩 얹었고, 커넥션 풀(10)에 여유가 있는
 * 저부하에서는 공짜였지만 풀이 포화되는 구간에서는 그대로 처리량을 깎았다.
 *
 * <p><b>아무도 이걸 알아채지 못했다.</b> 기능마다 테스트가 있었고 전부 초록불이었다.
 * 조회가 늘어난 것은 부하를 실제로 걸어 본 뒤에야 드러났고, 그때는 이미 세 기능이 모두
 * 들어간 뒤였다. 이 테스트는 그 간극을 메운다 — 조회가 늘면 <b>부하 시험이 아니라 CI에서</b>
 * 걸린다.
 *
 * <h2>이 숫자를 올려도 되는가</h2>
 *
 * <p>올릴 수는 있다. 다만 <b>의도적으로</b> 올려야 한다. 이 테스트가 실패하면 "숫자를 고쳐
 * 초록불로 만들기" 전에, 늘어난 조회가 정말 필요한지와 기존 조회에 합칠 수 있는지를 먼저
 * 본다. 실제로 2026-09-11에 조회 3건을 집계 1건으로 합친 전례가 있다.
 *
 * <h2>무엇을 세는가</h2>
 *
 * <p>Hibernate의 {@code prepareStatementCount} — JDBC 구문 준비 횟수다. 왕복 수의 대리
 * 지표로 쓰기에 가장 가깝고, 지연 로딩이 뒤늦게 던지는 조회까지 포함한다. 후보 수에 비례해
 * 늘어나는 배치 조회가 있으면 메뉴 수를 늘렸을 때 숫자가 따라 올라가므로, 아래 두 번째
 * 테스트가 그 기울기를 함께 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickQueryBudgetTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, KST).toInstant(), KST);

    @Autowired private MenuRepository menuRepository;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;
    @Autowired private HistoryRepository historyRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PickPresetRepository pickPresetRepository;

    private PickService pickService;
    private User me;
    private Statistics statistics;

    @BeforeEach
    void setUp() {
        pickService = new PickService(menuRepository, historyRepository, userRepository,
                tagRepository, new DefaultPickPreferenceService(tagRepository),
                menuRestaurantRepository, FIXED_CLOCK);
        me = userRepository.save(User.builder().email("budget@example.com").nickname("예산").build());

        statistics = entityManager.getEntityManagerFactory()
                .unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /**
     * 준비가 남긴 조회는 세지 않는다. 세션을 비워 <b>영속성 컨텍스트 캐시가 픽을 도와주지
     * 못하게</b> 만든 뒤부터 센다 — 운영에서는 요청마다 세션이 새로 열리므로 그쪽이 실제 모습이다.
     */
    private long countStatements(Supplier<?> action) {
        entityManager.flush();
        entityManager.clear();
        statistics.clear();
        action.get();
        return statistics.getPrepareStatementCount();
    }

    private Menu menu(String name, String category) {
        Menu menu = Menu.builder().user(me).name(name).weight(1).build();
        menu.addCategory(category);
        return menuRepository.save(menu);
    }

    private void seedMenus(int count) {
        String[] categories = {"한식", "중식", "일식", "양식"};
        for (int i = 0; i < count; i++) {
            menu("메뉴" + i, categories[i % categories.length]);
        }
    }

    @Test
    @DisplayName("필터 없는 픽이 쓰는 조회 수")
    void plainPick() {
        seedMenus(30);

        long statements = countStatements(() -> pickService.pick(me.getId(), null));

        // 늘었다면 먼저 "합칠 수 있는가"를 보고, 그 뒤에 이 숫자를 고친다(클래스 주석 참고).
        assertThat(statements)
                .as("필터 없는 픽 한 번의 JDBC 구문 수")
                .isEqualTo(PLAIN_PICK_BUDGET);
    }

    @Test
    @DisplayName("카테고리·거리 필터를 건 픽이 쓰는 조회 수")
    void filteredPick() {
        seedMenus(30);
        Restaurant near = restaurantRepository.save(Restaurant.builder()
                .user(me).name("가까운식당")
                .latitude(new BigDecimal("37.5665350")).longitude(new BigDecimal("126.9779692"))
                .build());
        Menu linked = menu("연결메뉴", "한식");
        entityManager.persist(MenuRestaurant.builder().menu(linked).restaurant(near).build());
        // 태그를 만들기만 하고 메뉴에 붙이지 않으면 포함 태그 필터가 후보를 0으로 만든다.
        Tag tag = tagRepository.save(Tag.builder().user(me).name("혼밥").build());
        linked.addTag(tag);

        long statements = countStatements(() -> pickService.pick(me.getId(),
                new PickRequest(Set.of("한식"), Set.of(tag.getId()), null,
                        new BigDecimal("37.5665350"), new BigDecimal("126.9779692"), 5000)));

        assertThat(statements)
                .as("필터를 건 픽 한 번의 JDBC 구문 수")
                .isEqualTo(FILTERED_PICK_BUDGET);
    }

    /**
     * <b>이 테스트가 이 파일의 핵심이다.</b> 조회 수가 메뉴 수에 비례해 늘어나면(N+1) 총량은
     * 사용자가 데이터를 쌓을수록 커진다 — 그런 종류의 회귀는 위 두 테스트처럼 고정된 숫자로는
     * 잡히지 않는다. 후보를 3배로 늘려도 조회 수가 그대로인지 본다.
     *
     * <p>정확히 같아야 한다고 요구하지는 않는다. 하이버네이트의 배치 로딩
     * ({@code default_batch_fetch_size})은 후보가 배치 크기를 넘을 때 조회를 한 번 더 던질 수
     * 있고, 그건 N+1이 아니다. 늘어나더라도 <b>후보 수에 비례하지 않는</b> 선을 지키면 된다.
     */
    @Test
    @DisplayName("메뉴가 3배로 늘어도 조회 수는 후보 수에 비례하지 않는다")
    void doesNotScaleWithCandidateCount() {
        seedMenus(30);
        long small = countStatements(() -> pickService.pick(me.getId(), null));

        seedMenus(60);   // 총 90개
        long large = countStatements(() -> pickService.pick(me.getId(), null));

        assertThat(large)
                .as("후보 30개 → %d건, 90개 → %d건. 비례해 늘면 N+1이다", small, large)
                .isLessThanOrEqualTo(small + 2);
    }

    /**
     * 빠른 픽 실행이 쓰는 조회 수({@code docs/PickPresetDesign.md} 10절).
     *
     * <p>일반 픽보다 많은 것이 정상이다 — 프리셋을 잠금 조회하고, 조건 컬렉션을 읽고,
     * 태그 소유권을 재확인하고, 최신 기본 제외를 읽은 뒤에야 기존 픽으로 들어간다.
     * <b>중요한 것은 이 경로가 일반 픽의 예산(위 7/12)을 건드리지 않는 것이다</b> —
     * 프리셋 기능 때문에 가장 많이 도는 경로가 느려지면 안 된다.
     */
    @Test
    @DisplayName("빠른 픽 실행이 쓰는 조회 수")
    void presetExecution() {
        seedMenus(30);
        Tag tag = tagRepository.save(Tag.builder().user(me).name("혼밥").build());
        entityManager.flush();

        DefaultPickPreferenceService defaults = new DefaultPickPreferenceService(tagRepository);
        PickPresetService presetService = new PickPresetService(
                pickPresetRepository, tagRepository, userRepository, defaults, pickService);
        var preset = presetService.create(me.getId(),
                new com.nameless0422.MenuPick.domain.pick.dto.PickPresetRequest.Create(
                        "예산", java.util.Set.of("한식"), java.util.Set.of(),
                        java.util.Set.of(tag.getId()), null));

        long statements = countStatements(() -> presetService.execute(me.getId(), preset.id(),
                new com.nameless0422.MenuPick.domain.pick.dto.PickPresetRequest.Execute(
                        preset.version(), null, null)));

        assertThat(statements)
                .as("빠른 픽 실행 한 번의 JDBC 구문 수")
                .isEqualTo(PRESET_EXECUTION_BUDGET);
    }

    // ── 실측값 (2026-09-12). 바꾸려면 클래스 주석의 "이 숫자를 올려도 되는가"를 먼저 읽을 것.
    //
    // 필터를 건 픽 12건의 내역은 이렇다:
    //   1  후보 조회 (PickCandidates 명세)
    //   2  지연 로딩 — menu_restaurants (거리 필터가 후보의 연결을 본다)
    //   3  지연 로딩 — restaurants
    //   4  개인화 신호 집계 (2026-09-11에 3건을 여기 하나로 합쳤다)
    //   5  필터 조건에 적을 태그 이름 조회
    //   6  INSERT histories
    //   7~9 INSERT history_filter_conditions × 3 (카테고리·포함 태그·거리)
    //   10 지연 로딩 — 뽑힌 메뉴의 태그 (응답 DTO)
    //   11 지연 로딩 — 뽑힌 메뉴의 카테고리 (응답 DTO)
    //   12 (필터 조합에 따라 달라지는 한 건)
    //
    // 7~9가 한 번에 묶이지 않는 것은 배치 설정이 꺼져서가 아니다. 이 엔티티들이
    // IDENTITY 키 생성을 쓰기 때문에 하이버네이트가 생성된 키를 받으려고 INSERT를 즉시
    // 실행해야 하고, 그러면 JDBC 배치가 성립하지 않는다. 줄이려면 키 생성 전략을 바꿔야
    // 하는데 그건 마이그레이션이고 다른 테이블까지 함께 걸리는 결정이라 여기서 다루지 않는다.
    private static final long PLAIN_PICK_BUDGET = 7;
    private static final long FILTERED_PICK_BUDGET = 12;
    /**
     * 빠른 픽 실행. 일반 픽과 <b>별도 예산</b>이며 위 두 값을 바꾸지 않는다.
     *
     * <p>필터 픽(12)보다 2건 많다 — 프리셋 잠금 조회와 조건 컬렉션 로딩이다. 태그 소유권
     * 재확인과 기본 제외 조회는 이미 세어진 조회와 합쳐지거나 빈 집합이면 생략된다.
     * 늘었다면 합칠 수 있는지부터 본다(클래스 주석의 "이 숫자를 올려도 되는가").
     */
    private static final long PRESET_EXECUTION_BUDGET = 14;
}
