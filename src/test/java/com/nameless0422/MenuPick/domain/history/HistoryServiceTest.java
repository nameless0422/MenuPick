package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class HistoryServiceTest {

    @Mock private HistoryRepository historyRepository;
    @Mock private RestaurantRepository restaurantRepository;
    @InjectMocks private HistoryService historyService;

    private User user;
    private Menu menu;
    private Restaurant restaurant;

    @BeforeEach
    void setUp() {
        user = User.builder().email("test@test.com").nickname("tester").build();
        ReflectionTestUtils.setField(user, "id", 1L);

        menu = Menu.builder().user(user).name("김치찌개").weight(3).build();
        ReflectionTestUtils.setField(menu, "id", 1L);

        restaurant = Restaurant.builder().user(user).name("맛집A").address("서울시 강남구").build();
        ReflectionTestUtils.setField(restaurant, "id", 1L);
    }

    @Test
    @DisplayName("히스토리 목록 조회 — 기본 7일")
    void getHistories_default7Days() {
        var history = createHistory(1L, menu, restaurant, false, LocalDateTime.now());

        given(historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), any(PageRequest.class)))
                .willReturn(List.of(history));

        HistoryResponse.HistoryListResponse result = historyService.getHistories(1L, null, null, 20);

        assertThat(result.histories()).hasSize(1);
        assertThat(result.histories().get(0).menuName()).isEqualTo("김치찌개");
        assertThat(result.histories().get(0).restaurantName()).isEqualTo("맛집A");
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("히스토리 목록 조회 — 커서 기반 페이지네이션")
    void getHistories_withCursor() {
        var history = createHistory(5L, menu, null, false, LocalDateTime.now());

        given(historyRepository.findByUserIdAndRecommendedAtAfterAndIdLessThanOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), eq(10L), any(PageRequest.class)))
                .willReturn(List.of(history));

        HistoryResponse.HistoryListResponse result = historyService.getHistories(1L, 10L, null, 20);

        assertThat(result.histories()).hasSize(1);
        assertThat(result.histories().get(0).restaurantName()).isNull();
    }

    @Test
    @DisplayName("히스토리 목록 조회 — days 파라미터")
    void getHistories_withDays() {
        given(historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), any(PageRequest.class)))
                .willReturn(List.of());

        HistoryResponse.HistoryListResponse result = historyService.getHistories(1L, null, 30, 20);

        assertThat(result.histories()).isEmpty();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    @DisplayName("히스토리 목록 조회 — hasNext 판별")
    void getHistories_hasNext() {
        var h1 = createHistory(2L, menu, null, false, LocalDateTime.now());
        var h2 = createHistory(1L, menu, null, false, LocalDateTime.now().minusHours(1));

        given(historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), any(PageRequest.class)))
                .willReturn(List.of(h1, h2));

        // size=1 이므로 2개 반환 시 hasNext=true, 실제 반환은 1개
        HistoryResponse.HistoryListResponse result = historyService.getHistories(1L, null, null, 1);

        assertThat(result.histories()).hasSize(1);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.nextCursor()).isEqualTo(2L);
    }

    @Test
    @DisplayName("방문 여부 업데이트 — 성공")
    void markVisited_success() {
        var history = createHistory(1L, menu, restaurant, false, LocalDateTime.now());

        given(historyRepository.findByIdAndUserId(1L, 1L)).willReturn(Optional.of(history));

        historyService.markVisited(1L, 1L, null);

        assertThat(history.isVisited()).isTrue();
        assertThat(history.getVisitedAt()).isNotNull();
    }

    @Test
    @DisplayName("방문 여부 업데이트 — 히스토리 미존재 시 404")
    void markVisited_notFound() {
        given(historyRepository.findByIdAndUserId(99L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> historyService.markVisited(1L, 99L, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.HISTORY_NOT_FOUND);
    }

    @Test
    @DisplayName("방문 처리 시 restaurantId 전달 — 히스토리의 식당이 덮어써진다")
    void markVisited_withRestaurantId_overwritesRestaurant() {
        var history = createHistory(1L, menu, restaurant, false, LocalDateTime.now());
        var visited = Restaurant.builder().user(user).name("실제방문식당").address("서울시 서초구").build();
        ReflectionTestUtils.setField(visited, "id", 2L);

        given(historyRepository.findByIdAndUserId(1L, 1L)).willReturn(Optional.of(history));
        given(restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(2L, 1L))
                .willReturn(Optional.of(visited));

        historyService.markVisited(1L, 1L, 2L);

        assertThat(history.isVisited()).isTrue();
        assertThat(history.getRestaurant().getName()).isEqualTo("실제방문식당");
    }

    @Test
    @DisplayName("방문 처리 시 restaurantId 미전달 — 기존 식당이 유지된다")
    void markVisited_withoutRestaurantId_keepsExistingRestaurant() {
        var history = createHistory(1L, menu, restaurant, false, LocalDateTime.now());

        given(historyRepository.findByIdAndUserId(1L, 1L)).willReturn(Optional.of(history));

        historyService.markVisited(1L, 1L, null);

        assertThat(history.getRestaurant().getName()).isEqualTo("맛집A");
    }

    @Test
    @DisplayName("방문 처리 시 존재하지 않는 restaurantId — 404")
    void markVisited_restaurantNotFound() {
        var history = createHistory(1L, menu, restaurant, false, LocalDateTime.now());

        given(historyRepository.findByIdAndUserId(1L, 1L)).willReturn(Optional.of(history));
        given(restaurantRepository.findByIdAndUserIdAndDeletedAtIsNull(99L, 1L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> historyService.markVisited(1L, 1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESTAURANT_NOT_FOUND);
        assertThat(history.isVisited()).isFalse();
    }

    @Test
    @DisplayName("히스토리 삭제 — 성공")
    void deleteHistory_success() {
        var history = createHistory(1L, menu, null, false, LocalDateTime.now());

        given(historyRepository.findByIdAndUserId(1L, 1L)).willReturn(Optional.of(history));

        historyService.deleteHistory(1L, 1L);

        verify(historyRepository).delete(history);
    }

    @Test
    @DisplayName("히스토리 삭제 — 미존재 시 404")
    void deleteHistory_notFound() {
        given(historyRepository.findByIdAndUserId(99L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> historyService.deleteHistory(1L, 99L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.HISTORY_NOT_FOUND);
    }

    // --- 엣지 케이스 ---

    @Test
    @DisplayName("days 미지정(null) — 기본 7일 기준 시각으로 조회한다")
    void getHistories_nullDays_usesDefault7Days() {
        var afterCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        given(historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), any(PageRequest.class)))
                .willReturn(List.of());

        historyService.getHistories(1L, null, null, 20);

        verify(historyRepository).findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), afterCaptor.capture(), any(PageRequest.class));
        assertThat(afterCaptor.getValue())
                .isCloseTo(LocalDateTime.now().minusDays(7), within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("days 지정 — 해당 일수만큼 거슬러 올라간 시각으로 조회한다 (0 이하는 컨트롤러 @Min(1)이 차단)")
    void getHistories_explicitDays_usedAsIs() {
        var afterCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        given(historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), any(PageRequest.class)))
                .willReturn(List.of());

        historyService.getHistories(1L, null, 30, 20);

        verify(historyRepository).findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), afterCaptor.capture(), any(PageRequest.class));
        assertThat(afterCaptor.getValue())
                .isCloseTo(LocalDateTime.now().minusDays(30), within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("이미 방문 처리된 히스토리를 다시 방문 처리")
    void markVisited_alreadyVisited() {
        var history = createHistory(1L, menu, restaurant, true, LocalDateTime.now());
        LocalDateTime firstVisitedAt = history.getVisitedAt();

        given(historyRepository.findByIdAndUserId(1L, 1L)).willReturn(Optional.of(history));

        historyService.markVisited(1L, 1L, null);

        assertThat(history.isVisited()).isTrue();
        // visitedAt이 갱신됨
        assertThat(history.getVisitedAt()).isAfterOrEqualTo(firstVisitedAt);
    }

    @Test
    @DisplayName("메뉴와 식당이 모두 null인 히스토리 조회")
    void getHistories_nullMenuAndRestaurant() {
        var history = createHistory(1L, null, null, false, LocalDateTime.now());

        given(historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), any(PageRequest.class)))
                .willReturn(List.of(history));

        HistoryResponse.HistoryListResponse result = historyService.getHistories(1L, null, null, 20);

        assertThat(result.histories().get(0).menuName()).isNull();
        assertThat(result.histories().get(0).restaurantName()).isNull();
    }

    @Test
    @DisplayName("필터 조건이 없는 히스토리 조회")
    void getHistories_noFilterConditions() {
        var history = History.builder()
                .user(user).menu(menu).restaurant(restaurant)
                .recommendedAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(history, "id", 1L);

        given(historyRepository.findByUserIdAndRecommendedAtAfterOrderByIdDesc(
                eq(1L), any(LocalDateTime.class), any(PageRequest.class)))
                .willReturn(List.of(history));

        HistoryResponse.HistoryListResponse result = historyService.getHistories(1L, null, null, 20);

        assertThat(result.histories().get(0).filterConditions()).isEmpty();
    }

    private History createHistory(Long id, Menu menu, Restaurant restaurant,
                                   boolean visited, LocalDateTime recommendedAt) {
        var history = History.builder()
                .user(user)
                .menu(menu)
                .restaurant(restaurant)
                .recommendedAt(recommendedAt)
                .build();
        ReflectionTestUtils.setField(history, "id", id);
        if (visited) {
            history.markVisited();
        }
        history.addFilterCondition("CATEGORY", "한식");
        return history;
    }
}
