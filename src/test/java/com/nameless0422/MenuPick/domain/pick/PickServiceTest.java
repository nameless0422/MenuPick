package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    @InjectMocks private PickService pickService;

    private User user;
    private Menu koreanMenu;
    private Menu japaneseMenu;
    private Menu chineseMenu;
    private Tag tag1;
    private Tag tag2;

    @BeforeEach
    void setUp() {
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
        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu, chineseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickResponse.PickResult result = pickService.pick(1L, null);

        assertThat(result).isNotNull();
        assertThat(result.menu()).isNotNull();
        assertThat(result.menu().name()).isIn("김치찌개", "초밥", "짜장면");
        verify(historyRepository).save(any(History.class));
    }

    @Test
    @DisplayName("카테고리 필터 적용 — KOREAN만 선택하면 한식 메뉴만 픽")
    void pick_categoryFilter() {
        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu, chineseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickRequest request = new PickRequest(Set.of("KOREAN"), null, null, null, null, null);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isEqualTo("김치찌개");
    }

    @Test
    @DisplayName("태그 포함 필터 적용")
    void pick_tagIncludeFilter() {
        koreanMenu.addTag(tag1);
        koreanMenu.addTag(tag2);
        japaneseMenu.addTag(tag1);

        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu, chineseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        // tag1 AND tag2 → 김치찌개만 둘 다 가지고 있음
        PickRequest request = new PickRequest(null, Set.of(1L, 2L), null, null, null, null);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isEqualTo("김치찌개");
    }

    @Test
    @DisplayName("태그 제외 필터 적용")
    void pick_tagExcludeFilter() {
        koreanMenu.addTag(tag2); // 매운 태그
        japaneseMenu.addTag(tag1);
        chineseMenu.addTag(tag1);

        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu, chineseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        // 매운(tag2) 제외 → 초밥 or 짜장면
        PickRequest request = new PickRequest(null, null, Set.of(2L), null, null, null);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isIn("초밥", "짜장면");
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

        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        // 서울시청 기준 1km 이내
        PickRequest request = new PickRequest(null, null, null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), 1000);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isEqualTo("김치찌개");
        assertThat(result.restaurants()).isNotEmpty();
        assertThat(result.restaurants().get(0).distance()).isLessThan(1000.0);
    }

    @Test
    @DisplayName("후보가 없으면 NO_PICK_CANDIDATES 예외 발생")
    void pick_noCandidates_throwsException() {
        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of());

        assertThatThrownBy(() -> pickService.pick(1L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_PICK_CANDIDATES);
    }

    @Test
    @DisplayName("weight 가중 랜덤 — weight 높은 메뉴가 더 자주 선택됨")
    void pick_weightedRandom() {
        // koreanMenu weight=3, japaneseMenu weight=1
        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu));
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

        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu));
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

    // --- 엣지 케이스 ---

    @Test
    @DisplayName("식당이 없는 메뉴 — 거리 필터 시 제외됨")
    void pick_menuWithoutRestaurants_filteredOutByDistance() {
        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu));

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

    @Test
    @DisplayName("모든 필터를 복합 적용 — 카테고리 + 태그 포함 + 거리")
    void pick_combinedFilters() {
        koreanMenu.addTag(tag1);
        Restaurant nearRestaurant = Restaurant.builder()
                .user(user).name("근처식당").address("서울")
                .latitude(new BigDecimal("37.5670")).longitude(new BigDecimal("126.9790"))
                .build();
        setId(nearRestaurant, 1L);
        setMenuRestaurants(koreanMenu, List.of(
                MenuRestaurant.builder().menu(koreanMenu).restaurant(nearRestaurant).build()));

        japaneseMenu.addTag(tag1);
        Restaurant farRestaurant = Restaurant.builder()
                .user(user).name("먼식당").address("부산")
                .latitude(new BigDecimal("35.1796")).longitude(new BigDecimal("129.0756"))
                .build();
        setId(farRestaurant, 2L);
        setMenuRestaurants(japaneseMenu, List.of(
                MenuRestaurant.builder().menu(japaneseMenu).restaurant(farRestaurant).build()));

        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickRequest request = new PickRequest(Set.of("KOREAN"), Set.of(1L), null,
                new BigDecimal("37.5666"), new BigDecimal("126.9784"), 1000);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isEqualTo("김치찌개");
    }

    @Test
    @DisplayName("필터 후 모든 후보 제거 시 NO_PICK_CANDIDATES")
    void pick_allFilteredOut_throwsException() {
        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu));

        PickRequest request = new PickRequest(Set.of("ITALIAN"), null, null, null, null, null);

        assertThatThrownBy(() -> pickService.pick(1L, request))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_PICK_CANDIDATES);
    }

    @Test
    @DisplayName("메뉴가 여러 카테고리를 가진 경우 — OR 로직")
    void pick_menuWithMultipleCategories_matchesAny() {
        koreanMenu.addCategory("FUSION");

        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        PickRequest request = new PickRequest(Set.of("FUSION"), null, null, null, null, null);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isEqualTo("김치찌개");
    }

    @Test
    @DisplayName("태그 포함 + 제외 동시 적용")
    void pick_tagIncludeAndExclude_combined() {
        koreanMenu.addTag(tag1);
        koreanMenu.addTag(tag2);
        japaneseMenu.addTag(tag1);

        given(menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(1L))
                .willReturn(List.of(koreanMenu, japaneseMenu, chineseMenu));
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(historyRepository.save(any(History.class))).willAnswer(inv -> inv.getArgument(0));

        // tag1 포함 + tag2 제외 → japaneseMenu만
        PickRequest request = new PickRequest(null, Set.of(1L), Set.of(2L), null, null, null);
        PickResponse.PickResult result = pickService.pick(1L, request);

        assertThat(result.menu().name()).isEqualTo("초밥");
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
}
