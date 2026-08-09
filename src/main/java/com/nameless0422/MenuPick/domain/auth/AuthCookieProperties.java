package com.nameless0422.MenuPick.domain.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Refresh Token 쿠키 설정. secure는 HTTPS 전송 강제 여부로,
 * 운영은 true 고정이고 로컬(http) 개발에서만 false로 낮춘다.
 */
@ConfigurationProperties(prefix = "auth.cookie")
public record AuthCookieProperties(
        @DefaultValue("true") boolean secure
) {}
