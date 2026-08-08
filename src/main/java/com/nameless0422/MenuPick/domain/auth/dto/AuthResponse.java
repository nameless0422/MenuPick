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

    /** emailVerified: 제공자가 이메일 소유를 검증했는지 여부 — 미검증 이메일은 계정 통합에 쓰면 안 된다. */
    public record OAuthUserProfile(
            String socialId,
            String email,
            String nickname,
            boolean emailVerified
    ) {}
}
