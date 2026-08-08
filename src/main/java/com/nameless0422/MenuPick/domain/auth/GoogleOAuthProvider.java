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
public class GoogleOAuthProvider implements OAuthProvider {

    private final OAuthProperties oAuthProperties;
    private final WebClient webClient;

    @Override
    public String getProviderName() {
        return "GOOGLE";
    }

    @Override
    public OAuthUserProfile getUserProfile(String code) {
        OAuthProperties.ProviderConfig config = oAuthProperties.google();

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

        // v2 userinfo는 verified_email, v3(OpenID)는 email_verified — 플래그 부재 시 미검증으로 간주
        Object verified = userInfo.containsKey("verified_email")
                ? userInfo.get("verified_email")
                : userInfo.get("email_verified");

        return new OAuthUserProfile(
                (String) userInfo.get("id"),
                (String) userInfo.get("email"),
                (String) userInfo.get("name"),
                Boolean.TRUE.equals(verified)
        );
    }
}
