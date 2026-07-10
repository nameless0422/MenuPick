package com.nameless0422.MenuPick.domain.auth.dto;

public class AuthResponse {

    /** 서비스 내부용 — Refresh Token은 응답 바디가 아닌 HttpOnly 쿠키로 전달한다. */
    public record TokenResponse(
            String accessToken,
            String refreshToken
    ) {}

    /** 클라이언트 응답 바디용 — Access Token만 노출한다. */
    public record AccessTokenResponse(
            String accessToken
    ) {}

    public record OAuthUserProfile(
            String socialId,
            String email,
            String nickname
    ) {}
}
