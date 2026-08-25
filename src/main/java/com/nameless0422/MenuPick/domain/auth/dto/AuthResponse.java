package com.nameless0422.MenuPick.domain.auth.dto;

import java.util.List;

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
     * @param hasPassword     자체 계정 자격증명이 있는지. 설정 화면이 비밀번호 변경 UI를
     *                        보여줄지 결정하는 데 쓴다 — 소셜 전용 계정에 폼을 띄우면
     *                        누르는 순간 400이 날 뿐이다
     * @param linkedProviders 연동된 소셜 제공자 이름(저장값 그대로, 예: KAKAO). LOCAL은 담지 않는다 —
     *                        그건 연동한 소셜 계정이 아니라 자체 자격증명이고 hasPassword가 이미 알려준다.
     *                        설정 화면이 "연동/해제" 중 무엇을 보여줄지 이 목록으로 정한다
     */
    public record MeResponse(
            String email,
            String nickname,
            boolean hasPassword,
            List<String> linkedProviders
    ) {}

    /**
     * 연동·해제 후의 연동 목록과 새 Access Token.
     *
     * <p>변경 결과를 그대로 돌려줘 클라이언트가 /me를 한 번 더 부르지 않아도 화면을 맞출 수 있게 한다.
     *
     * <p>토큰이 함께 오는 이유: 로그인 수단이 바뀌면 기존 세션을 전부 끊는다(비밀번호 변경과
     * 같은 처리). 그러면 방금 변경한 당사자의 세션도 함께 끊기므로, 새 토큰을 돌려주지 않으면
     * 사용자는 설정 화면에서 연동 버튼 한 번 눌렀다가 로그아웃당한다.
     */
    public record LinkedProvidersResponse(
            List<String> linkedProviders,
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
