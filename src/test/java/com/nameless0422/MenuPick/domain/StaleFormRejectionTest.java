package com.nameless0422.MenuPick.domain;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantService;
import com.nameless0422.MenuPick.domain.menu.MenuService;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantService;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantResponse;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 오래된 화면에서 온 저장이 거절되는지 확인한다 (issue #87).
 *
 * <h2>@Version만으로는 막히지 않던 경우</h2>
 *
 * <p>엔티티의 {@code @Version}은 <b>겹쳐 있는 두 트랜잭션</b>을 막는다. 그런데 이 앱에서
 * 실제로 벌어지는 손실은 그 모양이 아니다 — 탭 하나가 10시에 편집 화면을 열고, 다른 탭이
 * 10시 5분에 저장하고, 처음 탭이 10시 6분에 저장한다. 세 요청은 서로 겹치지 않는다.
 * 마지막 요청이 도착했을 때 서버가 행을 새로 읽으면 버전도 이미 최신이라, DB 차원에서는
 * 아무 충돌 없이 10시 5분의 변경이 조용히 덮인다.
 *
 * <p>그래서 각 테스트는 두 저장을 <b>완전히 순차로</b> 실행한다. 겹치게 만들지 않는 것이
 * 핵심이다 — 겹치면 {@code @Version}만으로도 막히므로, 버전 왕복이 정말 필요한지를
 * 확인하지 못한다. 실제로 이 파일의 테스트들은 요청 DTO에서 {@code version}을 떼면
 * 그대로 초록불이 된다(그때는 뒤 저장이 성공하고 앞 변경이 사라진다).
 *
 * <p>그리고 예외가 났는지만 보지 않고 <b>먼저 저장한 값이 살아남았는지</b>까지 본다.
 * 예외만 확인하면 두 변경이 모두 사라지는 구현도 통과한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, MenuService.class, RestaurantService.class, MenuRestaurantService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StaleFormRejectionTest extends AbstractIntegrationTest {

    private static final BigDecimal LAT = new BigDecimal("37.5665350");
    private static final BigDecimal LNG = new BigDecimal("126.9779692");

    @Autowired private MenuService menuService;
    @Autowired private RestaurantService restaurantService;
    @Autowired private MenuRestaurantService menuRestaurantService;
    @Autowired private MenuRepository menuRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private UserRepository userRepository;

    private User me;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.builder()
                .email("stale-" + System.nanoTime() + "@test.com")
                .nickname("스테일" + System.nanoTime())
                .build());
    }

    @Test
    @DisplayName("메뉴 - 오래된 화면에서 온 저장은 409로 거절되고 먼저 저장한 값이 남는다")
    void menu_staleFormIsRejected() {
        Long id = menuRepository.save(
                Menu.builder().user(me).name("김치찌개").weight(1).build()).getId();

        // 탭 둘이 같은 화면을 열었다 — 둘 다 같은 버전을 받아 갔다.
        long versionBothTabsSaw = menuService.getMenu(me.getId(), id).version();

        // 탭 A가 먼저 저장한다. 여기서 버전이 올라간다.
        MenuResponse.MenuDetail afterA = menuService.updateMenu(me.getId(), id,
                new MenuRequest.Update("A가 고친 이름", "A 메모", 3, false,
                        Set.of("한식"), null, versionBothTabsSaw));
        assertThat(afterA.version()).isGreaterThan(versionBothTabsSaw);

        // 탭 B는 아직 옛 버전을 들고 있다. 트랜잭션은 전혀 겹치지 않는다.
        assertThatThrownBy(() -> menuService.updateMenu(me.getId(), id,
                new MenuRequest.Update("B가 고친 이름", "B 메모", 5, false,
                        null, null, versionBothTabsSaw)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        MenuResponse.MenuDetail saved = menuService.getMenu(me.getId(), id);
        assertThat(saved.name()).isEqualTo("A가 고친 이름");
        assertThat(saved.weight()).isEqualTo(3);
        assertThat(saved.categories()).containsExactly("한식");
    }

    @Test
    @DisplayName("메뉴 - 새로 불러온 버전으로 다시 저장하면 통과한다")
    void menu_reloadedFormSucceeds() {
        // 거절이 막다른 길이면 안 된다. 안내대로 새로고침한 뒤 저장하면 되어야 하고,
        // 그 경로가 막히면 사용자는 자기 변경을 영영 저장하지 못한다.
        Long id = menuRepository.save(
                Menu.builder().user(me).name("김치찌개").weight(1).build()).getId();

        long stale = menuService.getMenu(me.getId(), id).version();
        menuService.updateMenu(me.getId(), id,
                new MenuRequest.Update("A가 고친 이름", null, 3, false, null, null, stale));

        long fresh = menuService.getMenu(me.getId(), id).version();
        MenuResponse.MenuDetail result = menuService.updateMenu(me.getId(), id,
                new MenuRequest.Update("B가 고친 이름", null, 5, false, null, null, fresh));

        assertThat(result.name()).isEqualTo("B가 고친 이름");
        assertThat(result.weight()).isEqualTo(5);
    }

    @Test
    @DisplayName("식당 - 오래된 화면에서 온 저장은 409로 거절되고 먼저 저장한 값이 남는다")
    void restaurant_staleFormIsRejected() {
        Long id = restaurantRepository.save(Restaurant.builder()
                .user(me).name("진주회관").latitude(LAT).longitude(LNG).build()).getId();

        long versionBothTabsSaw = restaurantService.getRestaurant(me.getId(), id).version();

        restaurantService.updateRestaurant(me.getId(), id,
                new RestaurantRequest.Update("A가 고친 상호", "A 주소", null,
                        LAT, LNG, null, versionBothTabsSaw));

        assertThatThrownBy(() -> restaurantService.updateRestaurant(me.getId(), id,
                new RestaurantRequest.Update("B가 고친 상호", "B 주소", null,
                        LAT, LNG, null, versionBothTabsSaw)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        RestaurantResponse.RestaurantDetail saved = restaurantService.getRestaurant(me.getId(), id);
        assertThat(saved.name()).isEqualTo("A가 고친 상호");
        assertThat(saved.address()).isEqualTo("A 주소");
    }

    @Test
    @DisplayName("메뉴-식당 연결 - 별점만 고친 탭과 메모만 고친 탭이 서로를 지우지 않는다")
    void menuRestaurant_staleFormIsRejected() {
        Menu menu = menuRepository.save(
                Menu.builder().user(me).name("김치찌개").weight(1).build());
        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .user(me).name("진주회관").latitude(LAT).longitude(LNG).build());
        menuRestaurantRepository.save(MenuRestaurant.builder()
                .menu(menu).restaurant(restaurant).rating(3).memo("보통").build());

        // 이 연결에는 단건 조회가 없다 — 화면도 목록으로 받아 그리므로 테스트도 같은 경로를 쓴다.
        long versionBothTabsSaw = linkOf(menu.getId(), restaurant.getId()).version();

        // update(rating, memo)는 두 필드를 함께 교체한다 — "별점만 바꿨다"는 탭도 사실은
        // 자기가 들고 있던 옛 메모를 함께 쓴다. 그래서 두 탭은 반드시 충돌해야 한다.
        menuRestaurantService.updateMenuRestaurant(me.getId(), menu.getId(), restaurant.getId(),
                new MenuRestaurantRequest.Update(5, "보통", versionBothTabsSaw));

        assertThatThrownBy(() -> menuRestaurantService.updateMenuRestaurant(
                me.getId(), menu.getId(), restaurant.getId(),
                new MenuRestaurantRequest.Update(3, "B가 고친 메모", versionBothTabsSaw)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);

        MenuRestaurantResponse.MenuRestaurantDetail saved = linkOf(menu.getId(), restaurant.getId());
        assertThat(saved.rating()).isEqualTo(5);
        assertThat(saved.memo()).isEqualTo("보통");
    }

    private MenuRestaurantResponse.MenuRestaurantDetail linkOf(Long menuId, Long restaurantId) {
        return menuRestaurantService.getMenuRestaurants(me.getId(), menuId).menuRestaurants().stream()
                .filter(link -> link.restaurantId().equals(restaurantId))
                .findFirst()
                .orElseThrow();
    }
}
