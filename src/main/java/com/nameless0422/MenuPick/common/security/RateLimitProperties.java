package com.nameless0422.MenuPick.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 레이트 리밋 설정.
 *
 * <p>trustProxy: 리버스 프록시/LB 뒤에 배포된 경우에만 true로 설정.
 * true면 X-Forwarded-For에서 신뢰 홉 수를 근거로 클라이언트 IP를 뽑아내고,
 * false면 헤더를 무시하고 직접 연결된 IP를 사용한다 (헤더 위조로 인한 rate limit 우회 방지).
 *
 * <p>trustedProxyHops: 애플리케이션 앞단에 있는 <b>신뢰할 수 있는</b> 프록시/LB 개수.
 * X-Forwarded-For는 각 프록시가 "자신이 본 원격 주소"를 오른쪽에 덧붙이므로,
 * 왼쪽(첫 항목)은 클라이언트가 마음대로 위조할 수 있다.
 * 따라서 오른쪽에서 trustedProxyHops번째 항목만 신뢰한다.
 * 예) 프록시 1대(기본값)면 XFF의 마지막 항목이 실제 클라이언트 IP다.
 * 잘못 크게 잡으면 프록시 IP로 묶여 전체 사용자가 한 버킷을 공유하게 되므로
 * 실제 배포 토폴로지와 반드시 일치시켜야 한다.
 *
 * <p>authLimitPerMinute: 로그인/토큰 재발급 등 인증 경로의 IP당 분당 허용 횟수.
 * <p>proxyLimitPerMinute: 외부 API 프록시 경로(kakao/naver)의 사용자당 분당 허용 횟수.
 * <p>windowSeconds: 카운터 윈도우 길이(초). 429 응답의 Retry-After 값으로도 쓰인다.
 */
@ConfigurationProperties(prefix = "rate-limit")
public record RateLimitProperties(
        @DefaultValue("false") boolean trustProxy,
        @DefaultValue("1") int trustedProxyHops,
        @DefaultValue("10") int authLimitPerMinute,
        @DefaultValue("30") int proxyLimitPerMinute,
        @DefaultValue("60") int windowSeconds
) {}
