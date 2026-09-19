package com.nameless0422.MenuPick.domain.onboarding;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 첫 사용자 안내 상태.
 *
 * <p>읽기 전용이다. 사용자가 고른 결과는 기존 일괄 제외({@code PATCH /menus/exclusions})로
 * 저장된다 — 온보딩 전용 쓰기 경로를 따로 두면 같은 일을 하는 길이 둘이 된다.
 */
@RestController
@RequestMapping("/api/v1/onboarding")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping
    public ResponseEntity<ApiResponse<OnboardingService.OnboardingResponse>> status(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(onboardingService.status(userId)));
    }
}
