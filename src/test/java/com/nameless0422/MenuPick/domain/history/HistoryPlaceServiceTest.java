package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantService;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
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
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주변 검색 결과에서 식당 고르기.
 *
 * <p>이 기능의 약속은 "한 번 누르면 식당 저장·메뉴 연결·기록이 전부 된다"이고, 그 반대편
 * 약속은 "여러 번 눌러도 같은 식당·같은 연결이 늘어나지 않는다"이다. 실 MySQL에서 유니크
 * 제약과 함께 확인한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class HistoryPlaceServiceTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 17, 12, 0);

    @Autowired private HistoryRepository historyRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private MenuRestaurantRepository menuRestaurantRepository;
    @Autowired private EntityManager em;

    private HistoryPlaceService service;
    private User user;
    private Menu menu;
    private History history;
    private int seq;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        RestaurantService restaurantService = new RestaurantService(
                restaurantRepository, userRepository, menuRestaurantRepository, clock);
        service = new HistoryPlaceService(
                historyRepository, restaurantService, restaurantRepository, menuRestaurantRepository);

        user = newUser();
        menu = menuRepository.save(Menu.builder().user(user).name("김치찌개").build());
        history = historyRepository.save(
                History.builder().user(user).menu(menu).recommendedAt(NOW.minusMinutes(5)).build());
    }

    private User newUser() {
        seq++;
        return userRepository.save(User.builder()
                .email("place" + seq + "@example.com").nickname("place" + seq).build());
    }

    private static RestaurantRequest.Create place(String kakaoPlaceId) {
        return new RestaurantRequest.Create("할매김치찌개", "서울 중구 세종대로 110", "02-123-4567",
                new BigDecimal("37.5665000"), new BigDecimal("126.9780000"),
                "https://place.map.kakao.com/" + kakaoPlaceId, kakaoPlaceId);
    }

    private History reload(History h) {
        em.flush();
        em.clear();
        return historyRepository.findById(h.getId()).orElseThrow();
    }

    @Test
    @DisplayName("한 번에 식당을 저장하고, 메뉴에 연결하고, 픽 기록에 적는다")
    void savesLinksAndRecords() {
        HistoryResponse.PlaceChoiceResponse result = service.choosePlace(user.getId(), history.getId(), place("p-1"));

        assertThat(result.restaurantCreated()).isTrue();
        assertThat(result.linkCreated()).isTrue();
        assertThat(result.restaurantName()).isEqualTo("할매김치찌개");
        assertThat(menuRestaurantRepository.existsByMenuIdAndRestaurantId(menu.getId(), result.restaurantId()))
                .isTrue();

        History saved = reload(history);
        assertThat(saved.getRestaurant().getId()).isEqualTo(result.restaurantId());
        assertThat(saved.getRecommendationFeedback()).isEqualTo(RecommendationFeedback.ACCEPTED);
    }

    /**
     * 아직 가기 전이다. 여기서 방문 처리하면 달력에는 "고른 날"이 찍히고, 다음에 "드셨어요?"를
     * 물을 대상에서도 빠져 실제로 먹었는지를 영영 모르게 된다.
     */
    @Test
    @DisplayName("식당을 골라도 방문 처리는 하지 않는다")
    void doesNotMarkVisited() {
        service.choosePlace(user.getId(), history.getId(), place("p-1"));

        History saved = reload(history);
        assertThat(saved.isVisited()).isFalse();
        assertThat(saved.getVisitedAt()).isNull();
    }

    @Test
    @DisplayName("나중에 방문 처리해도 고른 식당이 그대로 남는다")
    void visitKeepsChosenPlace() {
        HistoryResponse.PlaceChoiceResponse result = service.choosePlace(user.getId(), history.getId(), place("p-1"));
        History chosen = reload(history);

        chosen.markVisited(NOW.plusHours(1));
        History visited = reload(chosen);

        assertThat(visited.isVisited()).isTrue();
        assertThat(visited.getRestaurant().getId()).isEqualTo(result.restaurantId());
    }

    /** 같은 버튼을 두 번 누르거나, 다음 날 같은 가게를 또 고르는 것이 정상 경로다. */
    @Test
    @DisplayName("같은 장소를 다시 고르면 식당도 연결도 새로 만들지 않는다")
    void choosingSamePlaceIsIdempotent() {
        HistoryResponse.PlaceChoiceResponse first = service.choosePlace(user.getId(), history.getId(), place("p-1"));
        History next = historyRepository.save(
                History.builder().user(user).menu(menu).recommendedAt(NOW).build());

        HistoryResponse.PlaceChoiceResponse second = service.choosePlace(user.getId(), next.getId(), place("p-1"));

        assertThat(second.restaurantId()).isEqualTo(first.restaurantId());
        assertThat(second.restaurantCreated()).isFalse();
        assertThat(second.linkCreated()).isFalse();
        assertThat(restaurantRepository.findAllByUserIdAndDeletedAtIsNull(user.getId())).hasSize(1);
    }

    @Test
    @DisplayName("식당 화면에서 이미 저장해 둔 장소면 그 식당에 연결만 한다")
    void reusesAlreadySavedRestaurant() {
        Restaurant existing = restaurantRepository.save(Restaurant.builder()
                .user(user).name("할매김치찌개").latitude(new BigDecimal("37.5665000"))
                .longitude(new BigDecimal("126.9780000")).kakaoPlaceId("p-1").build());

        HistoryResponse.PlaceChoiceResponse result = service.choosePlace(user.getId(), history.getId(), place("p-1"));

        assertThat(result.restaurantId()).isEqualTo(existing.getId());
        assertThat(result.restaurantCreated()).isFalse();
        assertThat(result.linkCreated()).isTrue();
    }

    /** 남의 기록과 없는 기록은 같은 404다 — 존재 여부를 흘리지 않는다. */
    @Test
    @DisplayName("남의 픽 기록에는 고를 수 없고, 아무것도 저장하지 않는다")
    void rejectsOthersHistory() {
        User other = newUser();

        assertThatThrownBy(() -> service.choosePlace(other.getId(), history.getId(), place("p-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.HISTORY_NOT_FOUND);
        assertThat(restaurantRepository.findAllByUserIdAndDeletedAtIsNull(other.getId())).isEmpty();
    }

    /** 연결할 메뉴가 없는데 식당만 저장되면 사용자가 기대한 "이 메뉴의 식당"이 아니다. */
    @Test
    @DisplayName("메뉴를 지운 뒤면 식당도 저장하지 않는다")
    void rejectsDeletedMenu() {
        menu.softDelete(NOW);
        menuRepository.save(menu);

        assertThatThrownBy(() -> service.choosePlace(user.getId(), history.getId(), place("p-1")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_NOT_FOUND);
        assertThat(restaurantRepository.findAllByUserIdAndDeletedAtIsNull(user.getId())).isEmpty();
    }

    /** 장소 id가 없으면 같은 곳인지 판정할 수 없어, 누를 때마다 같은 식당이 새로 쌓인다. */
    @Test
    @DisplayName("카카오 장소 id가 없으면 거절한다")
    void requiresKakaoPlaceId() {
        assertThatThrownBy(() -> service.choosePlace(user.getId(), history.getId(), place(" ")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
