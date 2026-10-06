package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/menus")
@RequiredArgsConstructor
public class MenuController {

    private final MenuService menuService;

    @GetMapping("/trash")
    public ResponseEntity<ApiResponse<MenuResponse.DeletedMenuListResponse>> getDeletedMenus(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) @Size(max = 100) String cursor,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.getDeletedMenus(userId, cursor, size)));
    }

    @PostMapping("/{menuId}/restore")
    public ResponseEntity<ApiResponse<Void>> restoreMenu(
            @AuthenticationPrincipal Long userId, @PathVariable Long menuId,
            @RequestBody @Valid MenuRequest.Restore request) {
        menuService.restoreMenu(userId, menuId, request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<MenuResponse.MenuListResponse>> getMenus(
            @AuthenticationPrincipal Long userId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) @Size(max = 100) String keyword,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.getMenus(userId, cursor, size, keyword)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MenuResponse.MenuDetail>> createMenu(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid MenuRequest.Create request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(menuService.createMenu(userId, request)));
    }

    @PostMapping("/bulk/preview")
    public ResponseEntity<ApiResponse<MenuResponse.BulkPreview>> previewBulkCreate(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid MenuRequest.BulkCreate request) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.previewBulkCreate(userId, request)));
    }

    @PostMapping("/bulk")
    public ResponseEntity<ApiResponse<MenuResponse.BulkCreateResult>> bulkCreate(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid MenuRequest.BulkCreate request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(menuService.bulkCreate(userId, request)));
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

    /** 온보딩이 쓰는 일괄 제외. 계약은 {@code MenuRequest.BatchUpdateExclusion}. */
    @PatchMapping("/exclusions")
    public ResponseEntity<ApiResponse<Void>> batchUpdateExclusion(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid MenuRequest.BatchUpdateExclusion request) {
        menuService.batchUpdateExclusion(userId, request);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @GetMapping("/excluded")
    public ResponseEntity<ApiResponse<List<MenuResponse.MenuSummary>>> getExcludedMenus(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.getExcludedMenus(userId)));
    }

    /**
     * 메뉴를 잠시 쉬게 한다.
     *
     * <p>영구 제외({@code /exclude})와 다른 엔드포인트인 이유는 <b>되돌리는 방식이 다르기
     * 때문</b>이다. 제외는 사람이 풀어야 하고, 쉬기는 시각이 지나면 저절로 풀린다.
     * 응답으로 바뀐 메뉴를 돌려줘, 화면이 언제까지 쉬는지를 다시 묻지 않아도 되게 한다.
     */
    @PatchMapping("/{menuId}/pause")
    public ResponseEntity<ApiResponse<MenuResponse.MenuDetail>> pause(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId,
            @RequestBody @Valid MenuRequest.Pause request) {
        return ResponseEntity.ok(ApiResponse.ok(
                menuService.pauseMenu(userId, menuId, request.days())));
    }

    /** 쉬는 중인 메뉴를 지금 깨운다. 쉬지 않던 메뉴여도 200이다(멱등). */
    @DeleteMapping("/{menuId}/pause")
    public ResponseEntity<ApiResponse<MenuResponse.MenuDetail>> resume(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.ok(menuService.resumeMenu(userId, menuId)));
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
