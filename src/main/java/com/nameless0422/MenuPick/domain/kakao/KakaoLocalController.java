package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 카카오 로컬 API 프록시.
 *
 * <p>파라미터 범위는 카카오 로컬 API 스펙과 동일하게 선검증한다 —
 * 범위를 벗어난 값을 그대로 업스트림에 넘기면 쿼터만 소모하고 502로 돌아온다.
 */
@RestController
@RequestMapping("/api/v1/kakao/search")
@RequiredArgsConstructor
@Validated
public class KakaoLocalController {

    private final KakaoLocalClient kakaoLocalClient;

    @GetMapping("/keyword")
    public ResponseEntity<ApiResponse<KakaoLocalResponse.PlaceSearchResult>> searchByKeyword(
            @AuthenticationPrincipal Long userId,
            @RequestParam @Size(max = 100) String query,
            @RequestParam(required = false) String categoryGroupCode,
            @RequestParam(required = false) String x,
            @RequestParam(required = false) String y,
            @RequestParam(required = false) @Min(0) @Max(20000) Integer radius,
            @RequestParam(required = false) @Min(1) @Max(45) Integer page,
            @RequestParam(required = false) @Min(1) @Max(15) Integer size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(ApiResponse.ok(
                kakaoLocalClient.searchByKeyword(query, categoryGroupCode, x, y, radius, page, size, sort)));
    }

    @GetMapping("/category")
    public ResponseEntity<ApiResponse<KakaoLocalResponse.PlaceSearchResult>> searchByCategory(
            @AuthenticationPrincipal Long userId,
            @RequestParam String categoryGroupCode,
            @RequestParam String x,
            @RequestParam String y,
            @RequestParam(required = false) @Min(0) @Max(20000) Integer radius,
            @RequestParam(required = false) @Min(1) @Max(45) Integer page,
            @RequestParam(required = false) @Min(1) @Max(15) Integer size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(ApiResponse.ok(
                kakaoLocalClient.searchByCategory(categoryGroupCode, x, y, radius, page, size, sort)));
    }
}
