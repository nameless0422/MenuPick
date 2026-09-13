package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickAlternativesResponse;
import com.nameless0422.MenuPick.domain.pick.dto.PickRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pick/alternatives")
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pick-alternatives", name = "enabled", havingValue = "true")
public class PickAlternativesController {
    private final PickAlternativesService service;

    @PostMapping
    public ResponseEntity<ApiResponse<PickAlternativesResponse>> alternatives(
            @AuthenticationPrincipal Long userId,
            @RequestBody(required = false) @Valid PickRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(service.find(userId, request)));
    }
}
