package com.nameless0422.MenuPick.domain.naver;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.naver.dto.NaverMapsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/naver")
@RequiredArgsConstructor
public class NaverMapsController {

    private final NaverMapsClient naverMapsClient;

    @GetMapping("/geocode")
    public ResponseEntity<ApiResponse<NaverMapsResponse.GeocodeResult>> geocode(
            @AuthenticationPrincipal Long userId,
            @RequestParam String query,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer count) {
        return ResponseEntity.ok(ApiResponse.ok(naverMapsClient.geocode(query, page, count)));
    }

    @GetMapping("/reverse-geocode")
    public ResponseEntity<ApiResponse<NaverMapsResponse.ReverseGeocodeResult>> reverseGeocode(
            @AuthenticationPrincipal Long userId,
            @RequestParam String coords,
            @RequestParam(required = false) String orders) {
        return ResponseEntity.ok(ApiResponse.ok(naverMapsClient.reverseGeocode(coords, orders)));
    }
}
