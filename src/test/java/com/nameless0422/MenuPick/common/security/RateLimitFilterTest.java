package com.nameless0422.MenuPick.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter(redisTemplate, new RateLimitProperties(false));
    }

    @Test
    @DisplayName("로그인 API가 아닌 요청은 제한 없이 통과")
    void nonLoginRequest_passThrough() throws ServletException, IOException {
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

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate_limit:127.0.0.1")), eq("60")))
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

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate_limit:192.168.1.1")), eq("60")))
                .willReturn(10L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("로그인 API 10회 초과 요청 - 429 반환")
    @SuppressWarnings("unchecked")
    void loginRequest_exceedsLimit_returns429() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate_limit:10.0.0.1")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
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

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate_limit:10.0.0.2")), eq("60")))
                .willReturn(11L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(429);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    @DisplayName("trust-proxy가 true면 X-Forwarded-For의 첫 IP를 사용한다")
    @SuppressWarnings("unchecked")
    void trustProxy_usesXForwardedFor() throws ServletException, IOException {
        RateLimitFilter proxyTrustingFilter =
                new RateLimitFilter(redisTemplate, new RateLimitProperties(true));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/kakao");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate_limit:203.0.113.50")), eq("60")))
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

        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("rate_limit:10.0.0.1")), eq("60")))
                .willReturn(1L);

        rateLimitFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
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
