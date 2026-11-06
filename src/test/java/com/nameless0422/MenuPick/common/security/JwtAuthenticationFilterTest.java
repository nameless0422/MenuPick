package com.nameless0422.MenuPick.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private FilterChain filterChain;

    @Mock
    private UserRepository userRepository;

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter(jwtTokenProvider, userRepository);
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("유효한 JWT가 있으면 SecurityContext에 인증 정보를 설정한다")
    void validToken_setsAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = Jwts.claims().subject("1").build();
        given(jwtTokenProvider.parseAccessToken("valid.token.here")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(1L);
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.of(0L));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(1L);
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Authorization 헤더가 없으면 SecurityContext에 인증을 설정하지 않는다")
    void noHeader_noAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(jwtTokenProvider.parseAccessToken(null)).willReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("유효하지 않거나 만료된 토큰이면 예외 없이 인증만 비운 채 체인을 계속 태운다")
    void invalidToken_noAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer invalid.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(jwtTokenProvider.parseAccessToken("invalid.token")).willReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("subject가 숫자가 아니면 인증을 설정하지 않는다")
    void nonNumericSubject_noAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer weird.token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = Jwts.claims().subject("not-a-number").build();
        given(jwtTokenProvider.parseAccessToken("weird.token")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("Bearer 접두사가 없으면 토큰을 추출하지 않는다")
    void noBearerPrefix_noAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic some.credentials");
        MockHttpServletResponse response = new MockHttpServletResponse();

        given(jwtTokenProvider.parseAccessToken(null)).willReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    private MockHttpServletRequest validBearerRequest(Claims claims, long tokenVersion) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid.token.here");
        given(jwtTokenProvider.parseAccessToken("valid.token.here")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(1L);
        given(jwtTokenProvider.getAuthVersion(claims)).willReturn(tokenVersion);
        return request;
    }

    @Test
    @DisplayName("토큰 버전이 현재 버전과 다르면 서명이 유효해도 인증하지 않는다")
    void staleVersion_noAuthentication() throws Exception {
        MockHttpServletRequest request = validBearerRequest(Jwts.claims().subject("1").build(), 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.of(1L));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("탈퇴했거나 삭제된 계정의 토큰은 인증하지 않는다")
    void withdrawnUser_noAuthentication() throws Exception {
        MockHttpServletRequest request = validBearerRequest(Jwts.claims().subject("1").build(), 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.empty());

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("버전을 조회하지 못하면 통과시키지 않고 503을 준다")
    void databaseFailure_serviceUnavailable() throws Exception {
        MockHttpServletRequest request = validBearerRequest(Jwts.claims().subject("1").build(), 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        given(userRepository.findActiveAuthVersion(1L))
                .willThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        org.mockito.Mockito.verifyNoInteractions(filterChain);
    }

    @Test
    @DisplayName("트랜잭션을 열지 못해도 503을 준다")
    void transactionFailure_serviceUnavailable() throws Exception {
        MockHttpServletRequest request = validBearerRequest(Jwts.claims().subject("1").build(), 0L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        given(userRepository.findActiveAuthVersion(1L))
                .willThrow(new org.springframework.transaction.CannotCreateTransactionException("down"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(503);
        org.mockito.Mockito.verifyNoInteractions(filterChain);
    }
}
