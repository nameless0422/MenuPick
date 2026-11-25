package com.nameless0422.MenuPick.domain.nutrition;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 칼로리 추정(D-082). 경로가 세 자원에 걸쳐 있어 매핑을 메서드마다 전부 적는다. */
@Validated
@RestController
@RequiredArgsConstructor
public class NutritionController {

    private final NutritionService nutritionService;

    /** 기준 음식 검색. 공공 데이터라 사용자별 범위는 없지만, 남용을 막으려고 인증은 요구한다. */
    @GetMapping("/api/v1/food-references")
    public ResponseEntity<ApiResponse<List<NutritionDto.FoodReferenceSummary>>> search(
            @RequestParam @Size(min = 1, max = 50, message = "검색어는 1~50자여야 합니다.") String q,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int size) {
        return ResponseEntity.ok(ApiResponse.ok(nutritionService.search(q, size)));
    }

    @GetMapping("/api/v1/menus/{menuId}/nutrition")
    public ResponseEntity<ApiResponse<NutritionDto.MenuNutrition>> getMenuNutrition(
            @AuthenticationPrincipal Long userId, @PathVariable Long menuId) {
        return ResponseEntity.ok(ApiResponse.ok(nutritionService.getMenuNutrition(userId, menuId)));
    }

    @PutMapping("/api/v1/menus/{menuId}/nutrition")
    public ResponseEntity<ApiResponse<NutritionDto.MenuNutrition>> updateMenuNutrition(
            @AuthenticationPrincipal Long userId, @PathVariable Long menuId,
            @Valid @RequestBody NutritionDto.MenuNutritionRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(nutritionService.updateMenuNutrition(userId, menuId, request)));
    }

    @GetMapping("/api/v1/nutrition/reference")
    public ResponseEntity<ApiResponse<NutritionDto.KcalReference>> getKcalReference(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(nutritionService.getKcalReference(userId)));
    }

    @PutMapping("/api/v1/nutrition/reference")
    public ResponseEntity<ApiResponse<NutritionDto.KcalReference>> updateKcalReference(
            @AuthenticationPrincipal Long userId, @Valid @RequestBody NutritionDto.KcalReference request) {
        return ResponseEntity.ok(ApiResponse.ok(nutritionService.updateKcalReference(userId, request)));
    }

    /** {@code days}가 비었거나 1~31 밖이면 7일로 되돌린다 — 식사 요약과 같은 관례. */
    @GetMapping("/api/v1/history/nutrition")
    public ResponseEntity<ApiResponse<NutritionDto.NutritionSummary>> summarize(
            @AuthenticationPrincipal Long userId, @RequestParam(required = false) Integer days) {
        return ResponseEntity.ok(ApiResponse.ok(nutritionService.summarize(userId, days)));
    }
}
