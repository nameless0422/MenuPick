package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.OAuthLoginRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.RefreshRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/kakao")
    public ResponseEntity<ApiResponse<TokenResponse>> kakaoLogin(
            @RequestBody @Valid OAuthLoginRequest request) {
        TokenResponse tokens = authService.socialLogin("KAKAO", request.code());
        return ResponseEntity.ok(ApiResponse.ok(tokens));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<TokenResponse>> googleLogin(
            @RequestBody @Valid OAuthLoginRequest request) {
        TokenResponse tokens = authService.socialLogin("GOOGLE", request.code());
        return ResponseEntity.ok(ApiResponse.ok(tokens));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenResponse>> refresh(
            @RequestBody @Valid RefreshRequest request) {
        TokenResponse tokens = authService.refresh(request.refreshToken());
        return ResponseEntity.ok(ApiResponse.ok(tokens));
    }

    @DeleteMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @DeleteMapping("/withdraw")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @AuthenticationPrincipal Long userId) {
        authService.withdraw(userId);
        return ResponseEntity.ok(ApiResponse.ok());
    }
}
