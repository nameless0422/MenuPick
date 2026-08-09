package com.nameless0422.MenuPick.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * trustProxy: 리버스 프록시/LB 뒤에 배포된 경우에만 true로 설정.
 * true면 X-Forwarded-For의 첫 IP를 클라이언트 IP로 사용하고,
 * false면 헤더를 무시하고 직접 연결된 IP를 사용한다 (헤더 위조로 인한 rate limit 우회 방지).
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("false") boolean trustProxy
) {}
