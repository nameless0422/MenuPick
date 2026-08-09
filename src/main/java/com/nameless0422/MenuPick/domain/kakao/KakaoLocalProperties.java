package com.nameless0422.MenuPick.domain.kakao;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao.local")
public record KakaoLocalProperties(
        String restApiKey,
        String keywordSearchBaseUrl,
        String categorySearchBaseUrl
) {}
