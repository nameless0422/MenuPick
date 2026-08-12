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

    /**
     * 로그인한 사용자의 계정 정보.
     *
     * @param hasPassword 자체 계정 자격증명이 있는지. 설정 화면이 비밀번호 변경 UI를
     *                    보여줄지 결정하는 데 쓴다 — 소셜 전용 계정에 폼을 띄우면
     *                    누르는 순간 400이 날 뿐이다
     */
    public record MeResponse(
            String email,
            String nickname,
            boolean hasPassword
    ) {}

    /** emailVerified: 제공자가 이메일 소유를 검증했는지 여부 — 미검증 이메일은 계정 통합에 쓰면 안 된다. */
    public record OAuthUserProfile(
            String socialId,
            String email,
            String nickname,
            boolean emailVerified
    ) {}
}
