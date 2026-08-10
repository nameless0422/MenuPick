package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class GoogleOAuthProvider implements OAuthProvider {

    private static final String PROVIDER_NAME = "GOOGLE";

    private final OAuthProperties oAuthProperties;
    private final WebClient webClient;

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public OAuthUserProfile getUserProfile(String code) {
        OAuthProperties.ProviderConfig config = oAuthProperties.google();

        // 1. 인가 코드로 액세스 토큰 교환
        String accessToken = OAuthHttpSupport.exchangeAccessToken(webClient, config, PROVIDER_NAME, code);

        // 2. 액세스 토큰으로 사용자 프로필 조회
        Map<String, Object> userInfo = OAuthHttpSupport.fetchUserInfo(webClient, config, PROVIDER_NAME, accessToken);

        // v2 userinfo는 verified_email, v3(OpenID)는 email_verified — 플래그 부재 시 미검증으로 간주
        Object verified = userInfo.containsKey("verified_email")
                ? userInfo.get("verified_email")
                : userInfo.get("email_verified");

        // email/profile 스코프가 없으면 email·name이 통째로 빠진다 — null로 흘려보내고
        // AuthService가 이메일 없는 계정 생성·기본 닉네임으로 처리한다.
        return new OAuthUserProfile(
                OAuthHttpSupport.requireSocialId(userInfo, PROVIDER_NAME),
                OAuthHttpSupport.stringOrNull(userInfo, "email"),
                OAuthHttpSupport.stringOrNull(userInfo, "name"),
                Boolean.TRUE.equals(verified)
        );
    }
}
