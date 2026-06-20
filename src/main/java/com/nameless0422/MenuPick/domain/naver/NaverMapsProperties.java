package com.nameless0422.MenuPick.domain.naver;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "naver.maps")
public record NaverMapsProperties(
        String clientId,
        String clientSecret,
        String geocodeBaseUrl,
        String reverseGeocodeBaseUrl
) {}
