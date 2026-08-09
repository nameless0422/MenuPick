package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRestaurantResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/menus/{menuId}/restaurants")
@RequiredArgsConstructor
public class MenuRestaurantController {

    private final MenuRestaurantService menuRestaurantService;

    @GetMapping
    public ResponseEntity<ApiResponse<MenuRestaurantResponse.MenuRestaurantListResponse>> getMenuRestaurants(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.ok(menuRestaurantService.getMenuRestaurants(userId, menuId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MenuRestaurantResponse.MenuRestaurantDetail>> createMenuRestaurant(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId,
            @RequestBody @Valid MenuRestaurantRequest.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(menuRestaurantService.createMenuRestaurant(userId, menuId, request)));
    }

    @PutMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<MenuRestaurantResponse.MenuRestaurantDetail>> updateMenuRestaurant(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId,
            @PathVariable Long restaurantId,
            @RequestBody @Valid MenuRestaurantRequest.Update request) {
        return ResponseEntity.ok(ApiResponse.ok(
                menuRestaurantService.updateMenuRestaurant(userId, menuId, restaurantId, request)));
    }

    @DeleteMapping("/{restaurantId}")
    public ResponseEntity<ApiResponse<Void>> deleteMenuRestaurant(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId,
            @PathVariable Long restaurantId) {
        menuRestaurantService.deleteMenuRestaurant(userId, menuId, restaurantId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
