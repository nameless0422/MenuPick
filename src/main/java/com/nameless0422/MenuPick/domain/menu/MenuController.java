package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping
    public ResponseEntity<ApiResponse<MenuResponse.MenuListResponse>> getMenus(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.getMenus(userId, cursor, size)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MenuResponse.MenuDetail>> createMenu(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid MenuRequest.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(menuService.createMenu(userId, request)));
    }

    @GetMapping("/{menuId}")
    public ResponseEntity<ApiResponse<MenuResponse.MenuDetail>> getMenu(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.getMenu(userId, menuId)));
    }

    @PutMapping("/{menuId}")
    public ResponseEntity<ApiResponse<MenuResponse.MenuDetail>> updateMenu(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId,
            @RequestBody @Valid MenuRequest.Update request) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.updateMenu(userId, menuId, request)));
    }

    @DeleteMapping("/{menuId}")
    public ResponseEntity<ApiResponse<Void>> deleteMenu(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId) {
        menuService.deleteMenu(userId, menuId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PatchMapping("/weights")
    public ResponseEntity<ApiResponse<Void>> batchUpdateWeight(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid MenuRequest.BatchUpdateWeight request) {
        menuService.batchUpdateWeight(userId, request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/excluded")
    public ResponseEntity<ApiResponse<List<MenuResponse.MenuSummary>>> getExcludedMenus(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.getExcludedMenus(userId)));
    }

    @PatchMapping("/{menuId}/exclude")
    public ResponseEntity<ApiResponse<Void>> toggleExclude(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId,
            @RequestParam boolean exclude) {
        menuService.toggleExclude(userId, menuId, exclude);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
