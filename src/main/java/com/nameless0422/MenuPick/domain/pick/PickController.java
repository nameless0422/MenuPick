package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pick")
@RequiredArgsConstructor
public class PickController {

    private final PickService pickService;
    private final DemoPickService demoPickService;

    @PostMapping
    public ResponseEntity<ApiResponse<PickResponse.PickResult>> pick(
            @AuthenticationPrincipal Long userId,
            // 본문은 선택 — 없으면(null) 검증도 건너뛰고 필터 없는 전체 픽으로 동작한다.
            @RequestBody(required = false) @Valid PickRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(pickService.pick(userId, request)));
    }

    /**
     * 미로그인 사용자용 시연 픽 (docs/Planning.md 4.3). SecurityConfig에서 permitAll이며,
     * 남용 방지를 위해 RateLimitFilter가 IP 기준으로 제한한다.
     */
    @GetMapping("/demo")
    public ResponseEntity<ApiResponse<PickResponse.DemoPickResult>> pickDemo() {
        return ResponseEntity.ok(ApiResponse.ok(demoPickService.pickDemo()));
    }
}
