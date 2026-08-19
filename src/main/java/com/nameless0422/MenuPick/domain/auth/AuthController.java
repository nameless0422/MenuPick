package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.EmailRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.LoginRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.OAuthLoginRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.PasswordChangeRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.PasswordResetRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.SignupRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.TokenRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.AccessTokenResponse;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.MeResponse;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
    private final LocalAuthService localAuthService;
    private final JwtProperties jwtProperties;
    private final AuthCookieProperties authCookieProperties;

    // ---- 자체 계정 ----

    /**
     * 가입. 계정은 만들어지지만 메일 인증을 마치기 전에는 로그인할 수 없으므로 토큰을 주지 않는다.
     */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<Void>> signup(@RequestBody @Valid SignupRequest request) {
        localAuthService.signup(request.email(), request.password(), request.nickname());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok());
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> login(
            @RequestBody @Valid LoginRequest request) {
        return tokenResponse(localAuthService.login(request.email(), request.password()));
    }

    /** 메일 링크의 착지점. 인증과 동시에 로그인시킨다. */
    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> verifyEmail(
            @RequestBody @Valid TokenRequest request) {
        return tokenResponse(localAuthService.verifyEmail(request.token()));
    }

    /**
     * 인증 메일 재발송.
     *
     * <p>해당 주소로 가입한 계정이 없어도 200을 준다 — 응답이 갈리면 이 엔드포인트가
     * 가입 여부 조회기로 쓰인다.
     */
    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponse<Void>> resendVerification(
            @RequestBody @Valid EmailRequest request) {
        localAuthService.resendVerification(request.email());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    /** 비밀번호 재설정 메일 요청. 위와 같은 이유로 대상이 없어도 200이다. */
    @PostMapping("/password-reset")
    public ResponseEntity<ApiResponse<Void>> requestPasswordReset(
            @RequestBody @Valid EmailRequest request) {
        localAuthService.requestPasswordReset(request.email());
        return ResponseEntity.ok(ApiResponse.ok());
    }

    @PostMapping("/password-reset/confirm")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> confirmPasswordReset(
            @RequestBody @Valid PasswordResetRequest request) {
        return tokenResponse(
                localAuthService.confirmPasswordReset(request.token(), request.newPassword()));
    }

    /** 로그인 상태에서의 비밀번호 변경. 성공하면 세션이 새로 발급돼 다른 기기는 로그아웃된다. */
    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<AccessTokenResponse>> changePassword(
            @AuthenticationPrincipal Long userId,
            @RequestBody @Valid PasswordChangeRequest request) {
        return tokenResponse(localAuthService.changePassword(
                userId, request.currentPassword(), request.newPassword()));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MeResponse>> me(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.ok(localAuthService.me(userId)));
    }

    // ---- 소셜 로그인 ----

    /**
     * 동의 화면으로 302 리다이렉트한다. 프론트는 이 주소로 이동만 하면 되고,
     * client_id·redirect_uri는 서버 설정에만 존재한다(근거는 {@link OAuthProvider#buildAuthorizeUrl}).
     *
     * <p>목적지는 서버 설정에서만 오고 요청 값이 섞이지 않으므로 오픈 리다이렉트가 되지 않는다.
     * 요청에서 오는 값은 state뿐이고, 그것도 형식 검증을 거쳐 쿼리 파라미터로만 들어간다.
     */
    @GetMapping("/kakao/authorize")
    public ResponseEntity<Void> kakaoAuthorize(@RequestParam String state) {
        return redirectTo(authService.authorizeUrl("KAKAO", state));
    }

    @GetMapping("/google/authorize")
    public ResponseEntity<Void> googleAuthorize(@RequestParam String state) {
        return redirectTo(authService.authorizeUrl("GOOGLE", state));
    }

    private ResponseEntity<Void> redirectTo(String url) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, url)
                // 사용자마다 state가 다르므로 중간 캐시가 남기면 남의 인가 요청을 재사용하게 된다.
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

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
