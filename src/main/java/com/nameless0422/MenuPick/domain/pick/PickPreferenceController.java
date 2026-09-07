package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickPreferenceRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/pick/preferences")
@RequiredArgsConstructor
public class PickPreferenceController {
    private final DefaultPickPreferenceService service;

    @GetMapping
    public ResponseEntity<ApiResponse<Set<Long>>> get(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(service.getDefaultExcludedTagIds(userId)));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<Set<Long>>> update(
            @AuthenticationPrincipal Long userId, @RequestBody @Valid PickPreferenceRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(userId, request)));
    }
}
