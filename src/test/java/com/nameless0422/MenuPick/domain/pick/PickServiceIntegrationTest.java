package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
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

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 픽 한 번을 실 MySQL 위에서 처음부터 끝까지 돌린다.
 *
 * <p>조건이 SQL로 내려가면서 검증이 두 곳으로 갈렸다 — 조건이 무엇을 남기는지는
 * {@code PickCandidateQueryTest}가, 그 뒤의 처리는 {@code PickServiceTest}(순수 Mockito)가
 * 본다. 이 파일은 <b>둘 사이의 이음매</b>를 본다: 서비스가 조회에 넘기는 값이 실제로
 * 그 조건을 만드는지, 그리고 SQL이 넘긴 결과를 자바가 이어받아 마무리하는지.
 *
 * <p>둘 다 어느 한쪽 파일만으로는 초록불이 유지되는 종류의 실수다 — 예를 들어 서비스가
 * 정규화 전의 카테고리를 넘겨도 두 파일은 각자 자기 몫을 통과한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickServiceIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 12, 0, 0, 0, KST).toInstant(), KST);

    /** 서울시청. */
    private static final BigDecimal BASE_LAT = new BigDecimal("37.5665350");
    private static final BigDecimal BASE_LNG = new BigDecimal("126.9779692");

    @Autowired private MenuRepository menuRepository;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;
    @Autowired private HistoryRepository historyRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private PickService pickService;
    private User me;

    @BeforeEach
    void setUp() {
        pickService = new PickService(menuRepository, historyRepository, userRepository,
                tagRepository, menuRestaurantRepository, FIXED_CLOCK);
        me = userRepository.save(User.builder().email("me@example.com").nickname("나").build());
    }

    /**
     * 저장 경로({@code MenuService.normalizeCategories})는 저장 직전에 trim한다. 요청 쪽을
     * 맞추지 않으면 {@code [" 한식"]}이 {@code @NotBlank}를 통과하고도 저장된 "한식"과
     * 매칭되지 않아, 사용자는 분명히 있는 메뉴를 두고 "조건에 맞는 메뉴가 없다"는 답을 받는다.
     *
     * <p>정규화는 서비스에, 비교는 SQL에 있어서 어느 한쪽 테스트로도 잡히지 않는다.
     */
    @Test
    @DisplayName("카테고리 앞뒤 공백은 무시된다 — 정규화한 값이 조회 조건까지 그대로 간다")
    void trimsRequestedCategories() {
        Menu korean = menu("김치찌개", "한식");
        flushAndClear();

        PickResponse.PickResult result = pickService.pick(me.getId(),
                new PickRequest(Set.of("  한식  "), null, null, null, null, null));

        assertThat(result.menu().name()).isEqualTo(korean.getName());
    }

    /**
     * 바운딩 박스는 반경을 감싸는 <b>사각형</b>이라 모서리에 반경 밖 식당이 남는다.
     * SQL만 믿고 자바의 정밀 판정을 걷어내면 "500m 이내"로 요청한 결과에 700m짜리가 섞인다.
     */
    @Test
    @DisplayName("거리 - 박스 안이지만 반경 밖인 식당은 자바 정밀 판정에서 떨어진다")
    void boxCornerIsStillFilteredInJava() {
        // 대각선으로 약 620m — 박스(약 505m x 505m의 반폭) 안이지만 반경 500m 밖이다.
        menuAt("모서리메뉴", BASE_LAT.add(new BigDecimal("0.0040")),
                BASE_LNG.add(new BigDecimal("0.0050")));
        flushAndClear();

        assertThatThrownBy(() -> pickService.pick(me.getId(),
                new PickRequest(null, null, null, BASE_LAT, BASE_LNG, 500)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_PICK_CANDIDATES);
    }

    @Test
    @DisplayName("거리 - 반경 안의 식당은 결과 목록에 거리와 함께 실린다")
    void keepsRestaurantsInsideRadius() {
        menuAt("가까운메뉴", BASE_LAT.add(new BigDecimal("0.0010")), BASE_LNG);
        flushAndClear();

        PickResponse.PickResult result = pickService.pick(me.getId(),
                new PickRequest(null, null, null, BASE_LAT, BASE_LNG, 500));

        assertThat(result.restaurants()).hasSize(1);
        assertThat(result.restaurants().get(0).distance()).isBetween(100.0, 130.0);
    }

    @Test
    @DisplayName("필터가 하나도 없는 픽은 Specification 없이 파생 쿼리로 후보를 가져온다")
    void noFilterPathStillWorks() {
        menu("김치찌개");
        flushAndClear();

        PickResponse.PickResult result = pickService.pick(me.getId(), null);

        assertThat(result.menu().name()).isEqualTo("김치찌개");
        assertThat(result.historyId()).isNotNull();
    }

    @Test
    @DisplayName("픽 결과는 히스토리에 남고 필터 조건도 함께 기록된다")
    void recordsHistory() {
        menu("김치찌개", "한식");
        flushAndClear();

        PickResponse.PickResult result = pickService.pick(me.getId(),
                new PickRequest(Set.of("  한식  "), null, null, null, null, null));

        var history = historyRepository.findByIdAndUserId(result.historyId(), me.getId()).orElseThrow();
        assertThat(history.getMenu().getName()).isEqualTo("김치찌개");
        // 원본(" 한식")이 아니라 정규화된 값이 남아야 나중에 같은 조건을 재현할 수 있다.
        assertThat(history.getFilterConditions())
                .extracting("filterValue").containsExactly("한식");
    }

    // ------------------------------------------------------------------ 헬퍼

    /**
     * 세션을 비우고 시작한다.
     *
     * <p>{@code MenuRestaurant}는 연관의 주인이라 그 행만 persist하면 반대편
     * {@code Menu.menuRestaurants}는 갱신되지 않는다. 비우지 않으면 서비스가 1차 캐시에 있는
     * "식당이 하나도 없는" 메뉴를 받아, 실제로는 통과해야 할 거리 판정이 조용히 실패한다.
     * 운영에서는 픽 요청마다 세션이 새로 열리므로 이쪽이 실제 모습이다.
     */
    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Menu menu(String name, String... categories) {
        Menu menu = Menu.builder().user(me).name(name).weight(1).build();
        for (String category : categories) {
            menu.addCategory(category);
        }
        return menuRepository.save(menu);
    }

    private void menuAt(String name, BigDecimal lat, BigDecimal lng) {
        Menu menu = menu(name);
        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .user(me).name(name + "식당").latitude(lat).longitude(lng).build());
        entityManager.persist(MenuRestaurant.builder().menu(menu).restaurant(restaurant).build());
    }
}
