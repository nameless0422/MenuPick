package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class KakaoOAuthProvider implements OAuthProvider {

    private final OAuthProperties oAuthProperties;
    private final WebClient webClient;

    @Override
    public String getProviderName() {
        return "KAKAO";
    }

    @Override
    @SuppressWarnings("unchecked")
    public OAuthUserProfile getUserProfile(String code) {
        OAuthProperties.ProviderConfig config = oAuthProperties.kakao();

        // 1. 인가 코드로 액세스 토큰 교환
        Map<String, Object> tokenResponse = webClient.post()
                .uri(config.tokenUri())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue("grant_type=authorization_code"
                        + "&client_id=" + config.clientId()
                        + "&client_secret=" + config.clientSecret()
                        + "&redirect_uri=" + config.redirectUri()
                        + "&code=" + code)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        String accessToken = (String) tokenResponse.get("access_token");

        // 2. 액세스 토큰으로 사용자 프로필 조회
        Map<String, Object> userInfo = webClient.get()
                .uri(config.userInfoUri())
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();

        String socialId = String.valueOf(userInfo.get("id"));
        Map<String, Object> kakaoAccount = (Map<String, Object>) userInfo.get("kakao_account");
        Map<String, Object> profile = (Map<String, Object>) kakaoAccount.get("profile");

        // 카카오 이메일은 미검증 상태일 수 있다 — is_email_valid와 is_email_verified 모두 true일 때만 신뢰
        boolean emailVerified = Boolean.TRUE.equals(kakaoAccount.get("is_email_valid"))
                && Boolean.TRUE.equals(kakaoAccount.get("is_email_verified"));

        return new OAuthUserProfile(
                socialId,
                (String) kakaoAccount.get("email"),
                (String) profile.get("nickname"),
                emailVerified
        );
    }
}
