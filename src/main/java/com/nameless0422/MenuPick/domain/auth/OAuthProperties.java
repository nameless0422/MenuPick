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
            String tokenUri,
            String userInfoUri,
            String redirectUri
    ) {}
}
