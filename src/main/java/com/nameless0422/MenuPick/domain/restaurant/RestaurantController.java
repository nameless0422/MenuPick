package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/restaurants")
@RequiredArgsConstructor
public class RestaurantController {

    private final RestaurantService restaurantService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<RestaurantResponse.RestaurantSummary>>> getRestaurants(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(restaurantService.getRestaurants(userId)));
    }

    /**
     * 같은 장소를 이미 저장해 뒀으면 새로 만들지 않고 그것을 200으로 돌려준다.
     *
     * <p>중복을 409로 거절하지 않는 이유: 사용자는 "이 가게를 내 목록에 두고 싶다"고 말한 것이고
     * 그 상태는 이미 참이다. 실패로 돌려주면 아무 문제도 없는데 오류 화면을 보게 된다.
     * 그렇다고 201로 뭉뚱그리면 "새로 저장됐다"와 "이미 있었다"를 구분할 수 없어 프론트가
     * 안내를 달리 할 수 없다.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<RestaurantResponse.RestaurantDetail>> createRestaurant(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid RestaurantRequest.Create request) {
        RestaurantService.CreateResult result = restaurantService.createRestaurant(userId, request);

        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(ApiResponse.ok(result.restaurant()));
    }

    @GetMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<RestaurantResponse.RestaurantDetail>> getRestaurant(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long restaurantId) {
        return ResponseEntity.ok(ApiResponse.ok(restaurantService.getRestaurant(userId, restaurantId)));
    }

    @PutMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<RestaurantResponse.RestaurantDetail>> updateRestaurant(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long restaurantId,
            @RequestBody @Valid RestaurantRequest.Update request) {
        return ResponseEntity.ok(ApiResponse.ok(
                restaurantService.updateRestaurant(userId, restaurantId, request)));
    }

    @DeleteMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<Void>> deleteRestaurant(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long restaurantId) {
        restaurantService.deleteRestaurant(userId, restaurantId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
