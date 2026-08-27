package com.nameless0422.MenuPick.domain;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 낙관적 락이 "먼저 저장한 변경"을 지켜 주는지 확인한다 (issue #87, V9).
 *
 * <p>고치려는 결함은 조용하다. 탭 둘이 같은 메뉴 편집 화면을 열어 두면 둘 다 같은 시점의
 * 사본을 들고 있고, 먼저 저장한 쪽의 변경은 나중 저장에 통째로 덮인다. 그런데 <b>둘 다
 * 성공 응답을 받는다</b> — 덮어쓴 사람도 덮인 사람도 무슨 일이 일어났는지 모른다.
 * 그래서 각 테스트는 "예외가 났다"만 보지 않고 <b>먼저 저장한 값이 살아남았는지</b>까지 본다.
 * 예외만 확인하면 두 변경이 모두 사라지는 구현도 초록불이 된다.
 *
 * <h2>왜 스레드를 쓰지 않나</h2>
 *
 * <p>실제로 겹치는 두 요청을 스레드로 흉내 내면 어느 쪽이 먼저 커밋하느냐가 매번 달라져
 * 간헐적으로 깨지는 테스트가 된다. 그리고 이 앱에서 실제로 벌어지는 일은 "같은 밀리초에
 * 도착하는 두 요청"이 아니라 <b>몇 분 전에 화면을 연 탭이 지금 저장하는 것</b>이다.
 * 트랜잭션 밖에서(@Transactional NOT_SUPPORTED) 같은 행을 두 번 조회하면 서로 다른 준영속
 * 사본 둘이 나오는데, 그게 정확히 그 상황이고 순서도 결정적이다.
 *
 * <p>세 엔티티를 각각 확인한다. V9가 컬럼을 세 테이블에 넣어도 엔티티 한 곳에서
 * {@code @Version}이 빠지면 그 테이블만 조용히 보호 밖으로 나가는데, {@code ddl-auto: validate}는
 * 매핑되지 않은 여분의 컬럼을 문제 삼지 않아 기동도 정상이고 스키마 검증도 통과한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OptimisticLockTest extends AbstractIntegrationTest {

    @Autowired private MenuRepository menuRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;
    @Autowired private UserRepository userRepository;

    private User me;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.builder()
                .email("lock-" + System.nanoTime() + "@test.com")
                .nickname("락유저" + System.nanoTime())
                .build());
    }

    @Test
    @DisplayName("메뉴 - 오래된 사본으로 저장하면 거절되고 먼저 저장한 값이 남는다")
    void menu_staleWriteIsRejected() {
        Long id = menuRepository.save(
                Menu.builder().user(me).name("김치찌개").weight(1).build()).getId();

        // 탭 둘이 같은 시점의 화면을 열었다. 트랜잭션 밖이라 두 조회는 서로 다른 준영속 사본이다.
        Menu tabA = menuRepository.findById(id).orElseThrow();
        Menu tabB = menuRepository.findById(id).orElseThrow();

        tabA.update("A가 고친 이름", "A 메모", 3);
        menuRepository.saveAndFlush(tabA);

        tabB.update("B가 고친 이름", "B 메모", 5);
        assertThatThrownBy(() -> menuRepository.saveAndFlush(tabB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        Menu saved = menuRepository.findById(id).orElseThrow();
        assertThat(saved.getName()).isEqualTo("A가 고친 이름");
        assertThat(saved.getWeight()).isEqualTo(3);
    }

    @Test
    @DisplayName("식당 - 오래된 사본으로 저장하면 거절되고 먼저 저장한 값이 남는다")
    void restaurant_staleWriteIsRejected() {
        Long id = restaurantRepository.save(Restaurant.builder()
                .user(me).name("진주회관")
                .latitude(new BigDecimal("37.5665350"))
                .longitude(new BigDecimal("126.9779692"))
                .build()).getId();

        Restaurant tabA = restaurantRepository.findById(id).orElseThrow();
        Restaurant tabB = restaurantRepository.findById(id).orElseThrow();

        tabA.update("A가 고친 상호", "A 주소", null,
                new BigDecimal("37.5665350"), new BigDecimal("126.9779692"), null);
        restaurantRepository.saveAndFlush(tabA);

        tabB.update("B가 고친 상호", "B 주소", null,
                new BigDecimal("37.5665350"), new BigDecimal("126.9779692"), null);
        assertThatThrownBy(() -> restaurantRepository.saveAndFlush(tabB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        assertThat(restaurantRepository.findById(id).orElseThrow().getName())
                .isEqualTo("A가 고친 상호");
    }

    @Test
    @DisplayName("메뉴-식당 연결 - 별점만 고친 탭과 메모만 고친 탭이 서로를 지우지 않는다")
    void menuRestaurant_staleWriteIsRejected() {
        Menu menu = menuRepository.save(
                Menu.builder().user(me).name("김치찌개").weight(1).build());
        Restaurant restaurant = restaurantRepository.save(Restaurant.builder()
                .user(me).name("진주회관")
                .latitude(new BigDecimal("37.5665350"))
                .longitude(new BigDecimal("126.9779692"))
                .build());
        Long id = menuRestaurantRepository.save(MenuRestaurant.builder()
                .menu(menu).restaurant(restaurant).rating(3).memo("보통").build()).getId();

        MenuRestaurant tabA = menuRestaurantRepository.findById(id).orElseThrow();
        MenuRestaurant tabB = menuRestaurantRepository.findById(id).orElseThrow();

        // update(rating, memo)는 두 필드를 함께 교체한다 — 별점만 바꿨다고 생각한 탭 A도
        // 사실은 자기가 들고 있던 옛 메모를 함께 쓴다. 그래서 두 탭은 반드시 충돌해야 한다.
        tabA.update(5, "보통");
        menuRestaurantRepository.saveAndFlush(tabA);

        tabB.update(3, "B가 고친 메모");
        assertThatThrownBy(() -> menuRestaurantRepository.saveAndFlush(tabB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        MenuRestaurant saved = menuRestaurantRepository.findById(id).orElseThrow();
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getMemo()).isEqualTo("보통");
    }

    @Test
    @DisplayName("겹치지 않은 연속 수정은 그대로 통과한다")
    void sequentialWritesStillSucceed() {
        // 버전을 붙였다는 이유로 평범한 수정까지 막히면 안 된다. 매번 다시 읽어 오는
        // 정상 경로(= 저장 후 화면을 새로 고치는 흐름)는 몇 번을 반복해도 통과해야 한다.
        Long id = menuRepository.save(
                Menu.builder().user(me).name("김치찌개").weight(1).build()).getId();

        for (int weight = 2; weight <= 5; weight++) {
            Menu fresh = menuRepository.findById(id).orElseThrow();
            fresh.updateWeight(weight);
            menuRepository.saveAndFlush(fresh);
        }

        assertThat(menuRepository.findById(id).orElseThrow().getWeight()).isEqualTo(5);
    }
}
