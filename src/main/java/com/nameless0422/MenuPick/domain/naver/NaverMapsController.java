package com.nameless0422.MenuPick.domain.naver;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.naver.dto.NaverMapsResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * 네이버 지도 API 프록시.
 *
 * <p>업스트림 쿼터를 소모하는 경로이므로 길이/범위를 미리 잘라낸다.
 */
@RestController
@RequestMapping("/api/v1/naver")
@RequiredArgsConstructor
@Validated
public class NaverMapsController {

    private final NaverMapsClient naverMapsClient;

    @GetMapping("/geocode")
    public ResponseEntity<ApiResponse<NaverMapsResponse.GeocodeResult>> geocode(
            @AuthenticationPrincipal Long userId,
            @RequestParam @Size(max = 100) String query,
            @RequestParam(required = false) @Min(1) Integer page,
            @RequestParam(required = false) @Min(1) @Max(100) Integer count) {
        return ResponseEntity.ok(ApiResponse.ok(naverMapsClient.geocode(query, page, count)));
    }

    @GetMapping("/reverse-geocode")
    public ResponseEntity<ApiResponse<NaverMapsResponse.ReverseGeocodeResult>> reverseGeocode(
            @AuthenticationPrincipal Long userId,
            @RequestParam @Size(max = 100) String coords,
            @RequestParam(required = false) @Size(max = 100) String orders) {
        return ResponseEntity.ok(ApiResponse.ok(naverMapsClient.reverseGeocode(coords, orders)));
    }
}
