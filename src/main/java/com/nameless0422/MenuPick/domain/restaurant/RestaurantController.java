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

    @PostMapping
    public ResponseEntity<ApiResponse<RestaurantResponse.RestaurantDetail>> createRestaurant(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid RestaurantRequest.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(restaurantService.createRestaurant(userId, request)));
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
