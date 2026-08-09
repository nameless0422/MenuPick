package com.nameless0422.MenuPick.domain.kakao;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.kakao.dto.KakaoLocalResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/kakao/search")
@RequiredArgsConstructor
public class KakaoLocalController {

    private final KakaoLocalClient kakaoLocalClient;

    @GetMapping("/keyword")
    public ResponseEntity<ApiResponse<KakaoLocalResponse.PlaceSearchResult>> searchByKeyword(
            @AuthenticationPrincipal Long userId,
            @RequestParam String query,
            @RequestParam(required = false) String categoryGroupCode,
            @RequestParam(required = false) String x,
            @RequestParam(required = false) String y,
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
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
            @RequestParam(required = false) Integer radius,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(ApiResponse.ok(
                kakaoLocalClient.searchByCategory(categoryGroupCode, x, y, radius, page, size, sort)));
    }
}
