package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import com.nameless0422.MenuPick.domain.pick.dto.PickResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pick")
@RequiredArgsConstructor
public class PickController {

    private final PickService pickService;

    @PostMapping
    public ResponseEntity<ApiResponse<PickResponse.PickResult>> pick(
            @AuthenticationPrincipal Long userId,
            // 본문은 선택 — 없으면(null) 검증도 건너뛰고 필터 없는 전체 픽으로 동작한다.
            @RequestBody(required = false) @Valid PickRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(pickService.pick(userId, request)));
    }
}
