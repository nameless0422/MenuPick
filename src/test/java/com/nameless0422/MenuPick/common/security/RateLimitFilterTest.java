package com.nameless0422.MenuPick.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private FilterChain filterChain;

    private RateLimitFilter rateLimitFilter;

    /** trust-proxy=false, 홉 1, auth 10/분, proxy 30/분, demo 10/분, 윈도우 60초 */
    private static RateLimitProperties props(boolean trustProxy, int hops) {
        return new RateLimitProperties(trustProxy, hops, 10, 30, 10, 60);
    }

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter(redisTemplate, props(false, 1));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("제한 대상이 아닌 요청은 제한 없이 통과")
    void nonLimitedRequest_passThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/menus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("로그인 API 첫 요청 - 카운트 1로 통과")
    @SuppressWarnings("unchecked")
    void loginRequest_firstRequest_passThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("127.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:127.0.0.1")), eq("60")))
                .willReturn(1L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("로그인 API 10회 이내 요청 - 통과")
    @SuppressWarnings("unchecked")
    void loginRequest_withinLimit_passThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/google");
        request.setRemoteAddr("192.168.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:192.168.1.1")), eq("60")))
                .willReturn(10L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("로그인 API 10회 초과 요청 - 429와 Retry-After 반환")
    @SuppressWarnings("unchecked")
    void loginRequest_exceedsLimit_returns429() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:10.0.0.1")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentType()).isEqualTo("application/json;charset=UTF-8");
        assertThat(response.getContentAsString()).contains("TOO_MANY_REQUESTS");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("refresh API도 레이트 리밋 적용")
    @SuppressWarnings("unchecked")
    void refreshRequest_alsoLimited() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:10.0.0.2")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    // --- 경로 매칭: 퍼센트 인코딩 우회 차단 ---
    //
    // request.getRequestURI()는 디코딩되지 않은 원본 경로다. 문자열로 비교하면 아래 요청들이
    // 레이트 리밋만 비껴가고 Security permitAll + MVC 핸들러 매핑(둘 다 디코딩된 경로로 매칭)에는
    // 정상 도달한다 — 비밀번호 재설정 메일을 무제한 발송할 수 있게 된다.

    @Test
    @DisplayName("퍼센트 인코딩된 인증 경로(/auth/%70assword-reset)도 같은 IP 버킷에 집계된다")
    @SuppressWarnings("unchecked")
    void percentEncodedAuthPath_isStillRateLimited() throws ServletException, IOException {
        // %70 == 'p' — 디코딩하면 /api/v1/auth/password-reset 이다
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/%70assword-reset");
        request.setRemoteAddr("198.51.100.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:198.51.100.1")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("인코딩된 경로도 평문 경로와 같은 버킷을 공유한다 (섞어 보내도 합산된다)")
    @SuppressWarnings("unchecked")
    void percentEncodedAuthPath_sharesBucketWithPlainPath() throws ServletException, IOException {
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:198.51.100.2")), eq("60")))
                .willReturn(1L);

        for (String uri : List.of("/api/v1/auth/password-reset",
                "/api/v1/auth/%70assword-reset",
                "/api/v1/auth/passwor%64-reset",
                "/api/v1/auth/signu%70")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            request.setRemoteAddr("198.51.100.2");
            rateLimitFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        }

        verify(redisTemplate, times(4))
                .execute(any(RedisScript.class), eq(List.of("rl:auth:198.51.100.2")), eq("60"));
    }

    @Test
    @DisplayName("퍼센트 인코딩된 데모 픽 경로도 데모 버킷에 집계된다")
    @SuppressWarnings("unchecked")
    void percentEncodedDemoPath_isStillRateLimited() throws ServletException, IOException {
        // %64 == 'd'
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pick/%64emo");
        request.setRemoteAddr("198.51.100.3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:demo:198.51.100.3")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("퍼센트 인코딩된 외부 API 프록시 경로도 프록시 버킷에 집계된다")
    @SuppressWarnings("unchecked")
    void percentEncodedProxyPath_isStillRateLimited() throws ServletException, IOException {
        // %6B == 'k'
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/%6Bakao/search/keyword");
        request.setRemoteAddr("198.51.100.4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:proxy:ip:198.51.100.4")), eq("60")))
                .willReturn(31L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("경로 파라미터(;)가 붙어도 레이트 리밋 대상이다")
    @SuppressWarnings("unchecked")
    void pathParameterSuffix_isStillRateLimited() throws ServletException, IOException {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/password-reset;a=b");
        request.setRemoteAddr("198.51.100.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:198.51.100.5")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("디코딩해도 대상이 아닌 경로는 여전히 제한 없이 통과 (매칭이 과하게 넓어지지 않았다)")
    void encodedNonLimitedPath_passThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/%6Denus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(redisTemplate);
    }

    // --- X-Forwarded-For 신뢰 홉 처리 (이슈 #3) ---

    @Test
    @DisplayName("trust-proxy=true, 홉 1 - 클라이언트가 XFF를 위조해도 마지막(프록시가 관측한) 항목만 쓴다")
    @SuppressWarnings("unchecked")
    void trustProxy_singleHop_usesLastEntry_ignoringSpoofedPrefix() throws ServletException, IOException {
        RateLimitFilter proxyTrustingFilter = new RateLimitFilter(redisTemplate, props(true, 1));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        // 앞의 두 항목은 공격자가 직접 넣은 값, 마지막 항목만 우리 프록시가 붙인 실제 IP
        request.addHeader("X-Forwarded-For", "1.2.3.4, 5.6.7.8, 203.0.113.50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:203.0.113.50")), eq("60")))
                .willReturn(11L);

        proxyTrustingFilter.doFilterInternal(request, response, filterChain);

        // 위조 항목이 몇 개 붙든 같은 버킷으로 집계되어 429가 나야 한다
        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("trust-proxy=true, 홉 1 - 위조 항목 개수를 바꿔도 항상 같은 키가 잡힌다")
    @SuppressWarnings("unchecked")
    void trustProxy_spoofedPrefixLengthDoesNotChangeKey() throws ServletException, IOException {
        RateLimitFilter proxyTrustingFilter = new RateLimitFilter(redisTemplate, props(true, 1));

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:203.0.113.50")), eq("60")))
                .willReturn(1L);

        for (String spoofed : List.of("203.0.113.50",
                "9.9.9.9, 203.0.113.50",
                "9.9.9.9, 8.8.8.8, 7.7.7.7, 203.0.113.50")) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
            request.setRemoteAddr("10.0.0.1");
            request.addHeader("X-Forwarded-For", spoofed);
            proxyTrustingFilter.doFilterInternal(request, new MockHttpServletResponse(), filterChain);
        }

        // 세 번 모두 동일 키로 조회됐다면 stubbing이 3회 매칭된다
        verify(redisTemplate, times(3))
                .execute(any(RedisScript.class), eq(List.of("rl:auth:203.0.113.50")), eq("60"));
    }

    @Test
    @DisplayName("trust-proxy=true, 홉 2 - 오른쪽에서 두 번째 항목을 클라이언트 IP로 쓴다")
    @SuppressWarnings("unchecked")
    void trustProxy_twoHops_usesSecondFromRight() throws ServletException, IOException {
        RateLimitFilter proxyTrustingFilter = new RateLimitFilter(redisTemplate, props(true, 2));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        // [위조] , [실제 클라이언트 IP: 바깥 프록시가 추가] , [바깥 프록시 IP: 안쪽 프록시가 추가]
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.50, 70.41.3.18");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:203.0.113.50")), eq("60")))
                .willReturn(1L);

        proxyTrustingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("trust-proxy=true인데 XFF 항목 수가 신뢰 홉보다 적으면 실제 연결 IP로 폴백")
    @SuppressWarnings("unchecked")
    void trustProxy_tooFewEntries_fallsBackToRemoteAddr() throws ServletException, IOException {
        RateLimitFilter proxyTrustingFilter = new RateLimitFilter(redisTemplate, props(true, 2));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:10.0.0.1")), eq("60")))
                .willReturn(1L);

        proxyTrustingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("trust-proxy가 false면 X-Forwarded-For를 무시하고 실제 연결 IP를 사용한다")
    @SuppressWarnings("unchecked")
    void noTrustProxy_ignoresXForwardedFor() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.50");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:auth:10.0.0.1")), eq("60")))
                .willReturn(1L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    // --- 외부 API 프록시 경로 제한 (이슈 #10) ---

    @Test
    @DisplayName("카카오 프록시 GET - 인증 사용자 ID 기준 키로 집계된다")
    @SuppressWarnings("unchecked")
    void kakaoProxy_usesUserIdKey() throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/kakao/search/keyword");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:proxy:user:42")), eq("60")))
                .willReturn(1L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("네이버 프록시 GET - 분당 30회 초과 시 429와 Retry-After 반환")
    @SuppressWarnings("unchecked")
    void naverProxy_exceedsLimit_returns429() throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/naver/geocode");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:proxy:user:7")), eq("60")))
                .willReturn(31L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("60");
        assertThat(response.getContentAsString()).contains("TOO_MANY_REQUESTS");
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("네이버 프록시 GET - 30회 이내는 통과 (auth 한도 10과 별개)")
    @SuppressWarnings("unchecked")
    void naverProxy_withinProxyLimit_passThrough() throws ServletException, IOException {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(7L, null, List.of()));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/naver/reverse-geocode");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:proxy:user:7")), eq("60")))
                .willReturn(30L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("프록시 경로인데 SecurityContext가 비어 있으면 IP 기준 키로 폴백")
    @SuppressWarnings("unchecked")
    void proxyPath_withoutAuthentication_fallsBackToIpKey() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/kakao/search/category");
        request.setRemoteAddr("198.51.100.7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:proxy:ip:198.51.100.7")), eq("60")))
                .willReturn(1L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("프록시 경로의 GET 이외 메서드는 제한 대상이 아니다")
    void proxyPath_nonGet_passThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/kakao/search/keyword");
        MockHttpServletResponse response = new MockHttpServletResponse();

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("게스트 데모 픽은 IP 기준 버킷을 쓴다")
    @SuppressWarnings("unchecked")
    void demoPick_usesIpBucket() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pick/demo");
        request.setRemoteAddr("203.0.113.7");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:demo:203.0.113.7")), eq("60")))
                .willReturn(1L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("게스트 데모 픽 10회 초과 - 429")
    @SuppressWarnings("unchecked")
    void demoPick_exceedsLimit_returns429() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/pick/demo");
        request.setRemoteAddr("203.0.113.8");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rl:demo:203.0.113.8")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("인증이 필요한 POST /pick은 데모 버킷에 걸리지 않는다")
    void authenticatedPick_isNotDemoLimited() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/pick");
        MockHttpServletResponse response = new MockHttpServletResponse();

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("Redis 장애 시 요청을 통과시킨다 (fail-open)")
    @SuppressWarnings("unchecked")
    void redisFailure_passThrough() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), any(List.class), any()))
                .willThrow(new RuntimeException("Redis connection refused"));

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }
}
