package com.nameless0422.MenuPick.domain.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "oauth")
public record OAuthProperties(
        ProviderConfig kakao,
        ProviderConfig google
) {
    public record ProviderConfig(
            String clientId,
            String clientSecret,
            /** 사용자를 보낼 제공자의 동의 화면 주소. 서버가 인가 URL을 조립할 때 쓴다. */
            String authorizeUri,
            String tokenUri,
            String userInfoUri,
            String redirectUri
    ) {}
}
