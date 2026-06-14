package com.nameless0422.MenuPick.domain.auth.dto;

public class AuthResponse {

    public record TokenResponse(
            String accessToken,
            String refreshToken
    ) {}

    public record OAuthUserProfile(
            String socialId,
            String email,
            String nickname
    ) {}
}
