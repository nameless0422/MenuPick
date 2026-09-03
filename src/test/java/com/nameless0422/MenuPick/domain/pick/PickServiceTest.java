package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PickServiceTest {

    @Mock private MenuRepository menuRepository;
    @Mock private HistoryRepository historyRepository;
    @Mock private UserRepository userRepository;
    @Mock private TagRepository tagRepository;
    @Mock private MenuRestaurantRepository menuRestaurantRepository;

    /** 추천 시각 검증을 위해 KST 고정 시계를 쓴다. */
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 0, 30, 0, 0, KST).toInstant(), KST);

    private PickService pickService;

    private User user;
    private Menu koreanMenu;
    private Menu japaneseMenu;
    private Menu chineseMenu;
    private Tag tag1;
    private Tag tag2;

    @BeforeEach
    void setUp() {
        pickService = new PickService(menuRepository, historyRepository, userRepository,
                tagRepository, menuRestaurantRepository, FIXED_CLOCK);

        user = User.builder().email("test@test.com").nickname("테스터").build();
        setId(user, 1L);

        koreanMenu = Menu.builder().user(user).name("김치찌개").memo("맛있는 김치찌개").weight(3).build();
        setId(koreanMenu, 1L);
        koreanMenu.addCategory("KOREAN");

        japaneseMenu = Menu.builder().user(user).name("초밥").memo("신선한 초밥").weight(1).build();
        setId(japaneseMenu, 2L);
        japaneseMenu.addCategory("JAPANESE");

        chineseMenu = Menu.builder().user(user).name("짜장면").memo("짜장면").weight(1).build();
        setId(chineseMenu, 3L);
        chineseMenu.addCategory("CHINESE");

        tag1 = Tag.builder().user(user).name("혼밥가능").build();
        setId(tag1, 1L);

        tag2 = Tag.builder().user(user).name("매운").build();
        setId(tag2, 2L);
    }

    @Test
    @DisplayName("필터 없이 랜덤 픽 성공")
    void pick_noFilter_success() {
        givenCandidates(List.of(koreanMenu, japaneseMenu, chineseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickResponse.PickResult result = pickService.pick(1L, null);

        assertThat(result).isNotNull();
        assertThat(result.menu()).isNotNull();
        assertThat(result.menu().name()).isIn("김치찌개", "초밥", "짜장면");
        verify(historyRepository).save(any(History.class));
    }




    @Test
    @DisplayName("거리 필터 적용 — Haversine으로 가까운 식당 연결된 메뉴만")
    void pick_distanceFilter() {
        // 서울시청 근처 식당 (약 100m)
        Restaurant nearRestaurant = Restaurant.builder()
                .user(user).name("가까운식당").address("서울")
                .latitude(new BigDecimal("37.5670")).longitude(new BigDecimal("126.9790"))
                .build();
        setId(nearRestaurant, 1L);

        // 부산 식당 (약 325km)
        Restaurant farRestaurant = Restaurant.builder()
                .user(user).name("먼식당").address("부산")
                .latitude(new BigDecimal("35.1796")).longitude(new BigDecimal("129.0756"))
                .build();
        setId(farRestaurant, 2L);

        setMenuRestaurants(koreanMenu, List.of(
                MenuRestaurant.builder().menu(koreanMenu).restaurant(nearRestaurant).build()));
        setMenuRestaurants(japaneseMenu, List.of(
                MenuRestaurant.builder().menu(japaneseMenu).restaurant(farRestaurant).build()));

        givenCandidates(List.of(koreanMenu, japaneseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        // 서울시청 기준 1km 이내
        PickRequest request = new PickRequest(null, null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), 1000);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isEqualTo("김치찌개");
        assertThat(result.restaurants()).isNotEmpty();
        assertThat(result.restaurants().get(0).distance()).isLessThan(1000.0);
        // 픽 결과 화면이 이 좌표로 지도 마커를 찍는다
        assertThat(result.restaurants().get(0).latitude()).isEqualByComparingTo(new BigDecimal("37.5670"));
        assertThat(result.restaurants().get(0).longitude()).isEqualByComparingTo(new BigDecimal("126.9790"));
    }

    @Test
    @DisplayName("거리 필터를 걸면 반경 밖 식당은 결과 목록에서도 빠진다")
    void pick_distanceFilter_excludesFarRestaurantsFromResult() {
        // 한 메뉴에 가까운 식당과 먼 식당이 함께 달린 경우. 후보 판정은 anyMatch라
        // 가까운 식당 하나로 이 메뉴가 살아남는데, 결과 목록까지 그대로 담으면
        // "1km 이내"를 요청한 사용자에게 325km 떨어진 식당이 보인다.
        Restaurant near = Restaurant.builder()
                .user(user).name("가까운식당").address("서울")
                .latitude(new BigDecimal("37.5670")).longitude(new BigDecimal("126.9790"))
                .build();
        setId(near, 1L);

        Restaurant far = Restaurant.builder()
                .user(user).name("먼식당").address("부산")
                .latitude(new BigDecimal("35.1796")).longitude(new BigDecimal("129.0756"))
                .build();
        setId(far, 2L);

        setMenuRestaurants(koreanMenu, List.of(
                MenuRestaurant.builder().menu(koreanMenu).restaurant(near).build(),
                MenuRestaurant.builder().menu(koreanMenu).restaurant(far).build()));

        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickRequest request = new PickRequest(null, null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), 1000);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.restaurants())
                .extracting(PickResponse.RestaurantWithDistance::name)
                .containsExactly("가까운식당");
    }

    @Test
    @DisplayName("거리 필터가 없으면 먼 식당도 거리와 함께 그대로 내려간다")
    void pick_withoutMaxDistance_keepsAllRestaurants() {
        // 위 테스트가 과잉 필터링으로 번지지 않도록 반대 방향을 함께 고정한다.
        // maxDistance가 없으면 거리 계산만 하고 목록은 거르지 않는다.
        Restaurant near = Restaurant.builder()
                .user(user).name("가까운식당").address("서울")
                .latitude(new BigDecimal("37.5670")).longitude(new BigDecimal("126.9790"))
                .build();
        setId(near, 1L);

        Restaurant far = Restaurant.builder()
                .user(user).name("먼식당").address("부산")
                .latitude(new BigDecimal("35.1796")).longitude(new BigDecimal("129.0756"))
                .build();
        setId(far, 2L);

        setMenuRestaurants(koreanMenu, List.of(
                MenuRestaurant.builder().menu(koreanMenu).restaurant(near).build(),
                MenuRestaurant.builder().menu(koreanMenu).restaurant(far).build()));

        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickRequest request = new PickRequest(null, null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), null);
        PickResponse.PickResult result = pickService.pick(1L, request);

        // 가까운 순으로 정렬되므로 순서까지 고정한다
        assertThat(result.restaurants())
                .extracting(PickResponse.RestaurantWithDistance::name)
                .containsExactly("가까운식당", "먼식당");
    }

    @Test
    @DisplayName("뽑을 메뉴가 아예 없으면 NO_PICKABLE_MENUS — 필터를 풀라고 하면 안 된다")
    void pick_noMenusAtAll_throwsNoPickableMenus() {
        givenCandidates(List.of());
        // 필터를 다 풀어도 뽑을 것이 없는 상태
        given(menuRepository.existsByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L)).willReturn(false);

        assertThatThrownBy(() -> pickService.pick(1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_PICKABLE_MENUS);
    }

    @Test
    @DisplayName("weight 가중 랜덤 — weight 높은 메뉴가 더 자주 선택됨")
    void pick_weightedRandom() {
        // koreanMenu weight=3, japaneseMenu weight=1
        givenCandidates(List.of(koreanMenu, japaneseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            PickResponse.PickResult result = pickService.pick(1L, null);
            counts.merge(result.menu().name(), 1, Integer::sum);
        }

        // weight=3 vs weight=1 → 김치찌개가 약 75% 선택되어야 함 (±10% 여유)
        int koreanCount = counts.getOrDefault("김치찌개", 0);
        assertThat(koreanCount).isBetween(600, 900);
    }

    @Test
    @DisplayName("History 저장 시 필터 조건이 기록된다")
    void pick_savesHistoryWithFilterConditions() {
        koreanMenu.addTag(tag1);

        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickRequest request = new PickRequest(Set.of("KOREAN"), Set.of(1L), null, null, null, null);
        pickService.pick(1L, request);

        verify(historyRepository).save(argThat(history -> {
            assertThat(history.getMenu()).isEqualTo(koreanMenu);
            assertThat(history.getFilterConditions()).isNotEmpty();
            return true;
        }));
    }

    @Test
    @DisplayName("History 저장 시 최근접 식당이 기록된다 (위치 있음)")
    void pick_savesHistoryWithNearestRestaurant() {
        Restaurant nearRestaurant = Restaurant.builder()
                .user(user).name("가까운식당").address("서울")
                .latitude(new BigDecimal("37.5670")).longitude(new BigDecimal("126.9790"))
                .build();
        setId(nearRestaurant, 1L);
        Restaurant farRestaurant = Restaurant.builder()
                .user(user).name("먼식당").address("부산")
                .latitude(new BigDecimal("35.1796")).longitude(new BigDecimal("129.0756"))
                .build();
        setId(farRestaurant, 2L);
        setMenuRestaurants(koreanMenu, List.of(
                MenuRestaurant.builder().menu(koreanMenu).restaurant(farRestaurant).build(),
                MenuRestaurant.builder().menu(koreanMenu).restaurant(nearRestaurant).build()));

        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickRequest request = new PickRequest(null, null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), null);
        pickService.pick(1L, request);

        verify(historyRepository).save(argThat(history -> {
            assertThat(history.getRestaurant()).isEqualTo(nearRestaurant);
            return true;
        }));
    }

    @Test
    @DisplayName("History 저장 시 위치가 없고 연결 식당이 하나면 그 식당이 기록된다")
    void pick_savesHistoryWithSingleRestaurant_whenNoLocation() {
        Restaurant only = Restaurant.builder()
                .user(user).name("유일한식당").address("서울")
                .latitude(new BigDecimal("37.5670")).longitude(new BigDecimal("126.9790"))
                .build();
        setId(only, 1L);
        setMenuRestaurants(koreanMenu, List.of(
                MenuRestaurant.builder().menu(koreanMenu).restaurant(only).build()));

        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        pickService.pick(1L, null);

        verify(historyRepository).save(argThat(history -> {
            assertThat(history.getRestaurant()).isEqualTo(only);
            return true;
        }));
    }

    @Test
    @DisplayName("History 저장 시 위치가 없고 연결 식당이 여러 개면 식당을 기록하지 않는다")
    void pick_savesHistoryWithoutRestaurant_whenNoLocationAndMultiple() {
        Restaurant r1 = Restaurant.builder()
                .user(user).name("식당1").address("서울")
                .latitude(new BigDecimal("37.5670")).longitude(new BigDecimal("126.9790"))
                .build();
        setId(r1, 1L);
        Restaurant r2 = Restaurant.builder()
                .user(user).name("식당2").address("부산")
                .latitude(new BigDecimal("35.1796")).longitude(new BigDecimal("129.0756"))
                .build();
        setId(r2, 2L);
        setMenuRestaurants(koreanMenu, List.of(
                MenuRestaurant.builder().menu(koreanMenu).restaurant(r1).build(),
                MenuRestaurant.builder().menu(koreanMenu).restaurant(r2).build()));

        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        pickService.pick(1L, null);

        verify(historyRepository).save(argThat(history -> {
            assertThat(history.getRestaurant()).isNull();
            return true;
        }));
    }

    // --- 엣지 케이스 ---

    /**
     * 기본 메뉴 22개를 받고 시작한 신규 사용자가 거리 필터를 켰을 때의 상태다 —
     * 메뉴는 넘치는데 식당 연결이 0건이다. 여기서 NO_PICK_CANDIDATES를 주면 화면이
     * "필터를 풀거나 메뉴를 추가하라"고 안내하는데, 둘 다 이 사용자에게는 소용이 없다.
     * 반경을 넓히라는 조언도 마찬가지다 — 연결이 없으면 아무리 넓혀도 후보는 비어 있다.
     */
    @Test
    @DisplayName("식당이 연결된 메뉴가 하나도 없는데 거리로 뽑으면 NO_LINKED_RESTAURANTS")
    void pick_noLinkedRestaurants_throwsNoLinkedRestaurants() {
        givenCandidates(List.of(koreanMenu));
        given(menuRepository.existsByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L)).willReturn(true);
        given(menuRestaurantRepository.existsLinkedRestaurantForUser(1L)).willReturn(false);

        PickRequest request = new PickRequest(null, null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), 1000);

        assertThatThrownBy(() -> pickService.pick(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_LINKED_RESTAURANTS);
    }

    /**
     * 연결은 있는데 전부 반경 밖인 경우. 이때는 반경을 넓히면 결과가 나오므로
     * NO_LINKED_RESTAURANTS가 아니라 "조건이 좁다"여야 한다.
     */
    @Test
    @DisplayName("연결은 있는데 전부 반경 밖이면 NO_PICK_CANDIDATES — 반경을 넓히면 된다")
    void pick_linksExistButOutOfRange_throwsNoPickCandidates() {
        givenCandidates(List.of(koreanMenu));
        given(menuRepository.existsByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L)).willReturn(true);
        given(menuRestaurantRepository.existsLinkedRestaurantForUser(1L)).willReturn(true);

        PickRequest request = new PickRequest(null, null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), 1000);

        assertThatThrownBy(() -> pickService.pick(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_PICK_CANDIDATES);
    }


    @Test
    @DisplayName("같은 좌표 — Haversine 거리 0m")
    void haversine_sameLocation_distanceZero() {
        double dist = PickService.calculateHaversineDistance(37.5666, 126.9784, 37.5666, 126.9784);
        assertThat(dist).isEqualTo(0.0);
    }


    /** 조건에 맞는 메뉴가 없으면 "없다"는 답이 나가야 한다 — 조회가 비는 것은 오류가 아니다. */
    @Test
    @DisplayName("조건에 맞는 후보가 하나도 없으면 NO_PICK_CANDIDATES")
    void pick_allFilteredOut_throwsException() {
        // 조건은 SQL이 건다. 아무것도 안 맞는 요청은 조회가 빈 목록으로 돌아오는 모습이 된다.
        givenCandidates(List.of());
        // 뽑을 메뉴는 있다 — 좁은 것은 필터 쪽이다.
        given(menuRepository.existsByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L)).willReturn(true);

        PickRequest request = new PickRequest(Set.of("ITALIAN"), null, null, null, null, null);

        assertThatThrownBy(() -> pickService.pick(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_PICK_CANDIDATES);
        // 거리를 켜지 않았으면 연결 여부는 물어볼 일이 아니다. 물어보면 카테고리·태그가
        // 좁아서 빈 것을 "식당을 연결하라"고 잘못 안내하게 된다.
        verify(menuRestaurantRepository, never()).existsLinkedRestaurantForUser(any());
    }



    @Test
    @DisplayName("추천 시각은 주입된 Clock(KST) 기준으로 기록한다")
    void pick_recordsRecommendedAtFromClock() {
        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        pickService.pick(1L, null);

        ArgumentCaptor<History> captor = ArgumentCaptor.forClass(History.class);
        verify(historyRepository).save(captor.capture());
        assertThat(captor.getValue().getRecommendedAt())
                .isEqualTo(LocalDateTime.of(2026, 1, 15, 0, 30));
    }

    /**
     * 후보 조회 스텁.
     *
     * <p>필터가 붙은 픽은 Specification 조회를, 붙지 않은 픽은 파생 쿼리를 탄다 — 어느 쪽으로
     * 들어와도 같은 목록을 돌려준다. 이 파일이 보는 것은 "무엇이 걸러지는가"가 아니라 그 뒤의
     * 처리(가중 랜덤·결과 목록·히스토리)이므로 여기서 조건을 흉내 낼 이유가 없고, 흉내 내면
     * 오히려 "테스트가 정한 필터"를 검증하게 된다. 조건 자체는 실 MySQL 위에서 본다 —
     * {@code PickCandidateQueryTest}.
     */
    private void givenCandidates(List<Menu> candidates) {
        lenient().when(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .thenReturn(candidates);
        lenient().when(menuRepository.findAll(ArgumentMatchers.<Specification<Menu>>any()))
                .thenReturn(candidates);
    }

    // --- 리플렉션 헬퍼 ---

    private void setId(Object entity, Long id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setMenuRestaurants(Menu menu, List<MenuRestaurant> restaurants) {
        try {
            var field = Menu.class.getDeclaredField("menuRestaurants");
            field.setAccessible(true);
            field.set(menu, restaurants);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // --- 카테고리 정규화 (#87) ---


    /**
     * 히스토리에 원본을 남기면 화면에는 " KOREAN"이 보이는데 실제로 걸린 필터는 "KOREAN"이라,
     * 나중에 그 기록을 보고 같은 조건을 재현할 수 없다.
     */
    @Test
    @DisplayName("히스토리에도 정규화된 카테고리가 기록된다")
    void pick_recordsNormalizedCategoryInHistory() {
        givenCandidates(List.of(koreanMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        ArgumentCaptor<History> saved = ArgumentCaptor.forClass(History.class);
        given(historyRepository.save(saved.capture())).willAnswer(inv -> inv.getArgument(0));

        pickService.pick(1L, new PickRequest(Set.of("  KOREAN  "), null, null, null, null, null));

        assertThat(saved.getValue().getFilterConditions())
                .extracting("filterValue")
                .containsExactly("KOREAN");
    }

}
