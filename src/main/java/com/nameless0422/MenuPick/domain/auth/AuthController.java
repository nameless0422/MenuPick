package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.OAuthLoginRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.AccessTokenResponse;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";
    // 쿠키를 auth 엔드포인트에만 전송해 다른 API로의 노출을 차단한다
    private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

    private final AuthService authService;
    private final JwtProperties jwtProperties;
    private final AuthCookieProperties authCookieProperties;

    @PostMapping("/kakao")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> kakaoLogin(
            @RequestBody @Valid OAuthLoginRequest request) {
        return tokenResponse(authService.socialLogin("KAKAO", request.code()));
    }

    @PostMapping("/google")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> googleLogin(
            @RequestBody @Valid OAuthLoginRequest request) {
        return tokenResponse(authService.socialLogin("GOOGLE", request.code()));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Refresh Token 쿠키가 없습니다.");
        }
        return tokenResponse(authService.refresh(refreshToken));
    }

    @DeleteMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @AuthenticationPrincipal Long userId) {
        authService.logout(userId);
        return responseWithExpiredCookie();
    }

    @DeleteMapping("/withdraw")
    public ResponseEntity<ApiResponse<Void>> withdraw(
            @AuthenticationPrincipal Long userId) {
        authService.withdraw(userId);
        return responseWithExpiredCookie();
    }

    private ResponseEntity<ApiResponse<AccessTokenResponse>> tokenResponse(TokenResponse tokens) {
        ResponseCookie cookie = refreshCookieBuilder(tokens.refreshToken())
                .maxAge(Duration.ofMillis(jwtProperties.refreshTokenExpiry()))
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.ok(new AccessTokenResponse(tokens.accessToken())));
    }

    private ResponseEntity<ApiResponse<Void>> responseWithExpiredCookie() {
        ResponseCookie expired = refreshCookieBuilder("")
                .maxAge(0)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .body(ApiResponse.ok());
    }

    private ResponseCookie.ResponseCookieBuilder refreshCookieBuilder(String value) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(authCookieProperties.secure())
                .sameSite("Strict")
                .path(REFRESH_COOKIE_PATH);
    }
}
