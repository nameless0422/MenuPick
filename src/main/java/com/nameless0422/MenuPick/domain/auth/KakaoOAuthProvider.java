package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class KakaoOAuthProvider implements OAuthProvider {

    private static final String PROVIDER_NAME = "KAKAO";

    private final OAuthProperties oAuthProperties;
    private final WebClient webClient;

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    /**
     * 카카오는 동의 항목을 콘솔에서 정하므로 인가 URL에 scope를 싣지 않는다.
     */
    @Override
    public String buildAuthorizeUrl(String state) {
        return OAuthHttpSupport.buildAuthorizeUrl(
                oAuthProperties.kakao(), PROVIDER_NAME, state, null);
    }

    @Override
    public OAuthUserProfile getUserProfile(String code) {
        OAuthProperties.ProviderConfig config = oAuthProperties.kakao();

        // 1. 인가 코드로 액세스 토큰 교환
        String accessToken = OAuthHttpSupport.exchangeAccessToken(webClient, config, PROVIDER_NAME, code);

        // 2. 액세스 토큰으로 사용자 프로필 조회
        Map<String, Object> userInfo = OAuthHttpSupport.fetchUserInfo(webClient, config, PROVIDER_NAME, accessToken);

        // kakao_account·profile은 사용자가 해당 동의 항목을 거부하면 통째로 빠진다 — 빈 맵으로 처리한다.
        Map<String, Object> kakaoAccount = OAuthHttpSupport.nestedMap(userInfo, "kakao_account");
        Map<String, Object> profile = OAuthHttpSupport.nestedMap(kakaoAccount, "profile");

        // 카카오 이메일은 미검증 상태일 수 있다 — is_email_valid와 is_email_verified 모두 true일 때만 신뢰
        boolean emailVerified = Boolean.TRUE.equals(kakaoAccount.get("is_email_valid"))
                && Boolean.TRUE.equals(kakaoAccount.get("is_email_verified"));

        return new OAuthUserProfile(
                OAuthHttpSupport.requireSocialId(userInfo, PROVIDER_NAME),
                OAuthHttpSupport.stringOrNull(kakaoAccount, "email"),
                OAuthHttpSupport.stringOrNull(profile, "nickname"),
                emailVerified
        );
    }
}
