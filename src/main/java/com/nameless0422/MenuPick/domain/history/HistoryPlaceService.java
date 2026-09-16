package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryResponse;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantService;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 뽑은 메뉴를 먹으러 갈 식당을 주변 검색 결과에서 고른다.
 *
 * <h2>왜 한 번에 처리하는가</h2>
 *
 * <p>2026-09-16 운영 기준 등록된 식당이 1곳뿐이었다. 식당 화면에서 따로 검색해 저장하고,
 * 메뉴 화면에서 다시 연결하는 두 단계를 아무도 밟지 않는다. 그런데 거리로 뽑기와 결과 카드의
 * 지도는 전부 그 연결을 전제로 한다. 그래서 사용자가 실제로 식당을 고르는 순간 —
 * 뽑은 직후 — 에 필요한 일을 모두 끝낸다.
 *
 * <ol>
 *   <li>식당 저장. 같은 카카오 장소를 이미 저장했으면 그것을 쓰고, 지웠던 것이면 되살린다
 *       ({@link RestaurantService#createRestaurant}의 계약 그대로).</li>
 *   <li>메뉴-식당 연결. 이미 연결돼 있으면 건너뛴다 — 여기서는 중복이 오류가 아니다.
 *       평점·메모는 비워 둔다. 먹어 보기도 전에 매길 수는 없다.</li>
 *   <li>이 픽 기록에 식당을 적고 수락으로 표시한다. <b>방문 처리는 하지 않는다</b> — 아직
 *       가기 전이고, 방문 날짜의 의미는 "방문했어요를 누른 날짜"다(D-043). 먹었는지는 다음에
 *       픽 화면에서 묻는다(D-044).</li>
 * </ol>
 *
 * <p>화면에서 API 세 개를 이어 부르면 중간에 실패했을 때 식당만 저장되고 연결은 안 된 반쪽
 * 상태가 남는다. 한 트랜잭션으로 묶어 전부 되거나 전부 안 되게 한다.
 */
@Service
@RequiredArgsConstructor
public class HistoryPlaceService {

    private final HistoryRepository historyRepository;
    private final RestaurantService restaurantService;
    private final RestaurantRepository restaurantRepository;
    private final MenuRestaurantRepository menuRestaurantRepository;

    @Transactional
    public HistoryResponse.PlaceChoiceResponse choosePlace(
            Long userId, Long historyId, RestaurantRequest.Create request) {
        // 이 흐름은 장소 검색 결과에서만 들어온다. 장소 id가 없으면 같은 곳을 이미 저장했는지
        // 판정할 수 없어, 누를 때마다 같은 식당이 새 행으로 쌓인다.
        if (request.kakaoPlaceId() == null || request.kakaoPlaceId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        History history = historyRepository.findByIdAndUserId(historyId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        // 메뉴를 지운 뒤라면 연결할 대상이 없다. 식당만 저장하고 끝내면 사용자가 기대한
        // "이 메뉴의 식당"이 되지 않으므로 아무것도 하지 않는다.
        Menu menu = history.getMenu();
        if (menu == null || menu.isDeleted()) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
        }

        RestaurantService.CreateResult saved = restaurantService.createRestaurant(userId, request);
        Restaurant restaurant = restaurantRepository
                .findByIdAndUserIdAndDeletedAtIsNull(saved.restaurant().id(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESTAURANT_NOT_FOUND));

        boolean linkCreated = false;
        if (!menuRestaurantRepository.existsByMenuIdAndRestaurantId(menu.getId(), restaurant.getId())) {
            menuRestaurantRepository.save(MenuRestaurant.builder()
                    .menu(menu)
                    .restaurant(restaurant)
                    .build());
            linkCreated = true;
        }

        history.choosePlace(restaurant);

        return new HistoryResponse.PlaceChoiceResponse(
                restaurant.getId(), restaurant.getName(), saved.created(), linkCreated);
    }
}
