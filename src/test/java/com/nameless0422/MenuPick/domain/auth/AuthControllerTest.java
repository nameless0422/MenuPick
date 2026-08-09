package com.nameless0422.MenuPick.domain.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nameless0422.MenuPick.common.security.CustomAccessDeniedHandler;
import com.nameless0422.MenuPick.common.security.CustomAuthenticationEntryPoint;
import com.nameless0422.MenuPick.common.security.JwtAuthenticationFilter;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.common.security.RateLimitFilter;
import com.nameless0422.MenuPick.common.security.SecurityConfig;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.OAuthLoginRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, RateLimitFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@ActiveProfiles("test")
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @MockitoBean private AuthService authService;
    @MockitoBean private JwtTokenProvider jwtTokenProvider;
    @MockitoBean private StringRedisTemplate redisTemplate;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        ValueOperations<String, String> valueOps = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOps);
        given(valueOps.increment(any())).willReturn(1L);
    }

    @Test
    @DisplayName("POST /api/v1/auth/kakao - 카카오 로그인 성공 (Refresh Token은 HttpOnly 쿠키로만 전달)")
    void kakaoLogin_success() throws Exception {
        given(authService.socialLogin("KAKAO", "test_code"))
                .willReturn(new TokenResponse("access_token", "refresh_token"));

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("test_code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.accessToken").value("access_token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().value("refresh_token", "refresh_token"))
                .andExpect(cookie().httpOnly("refresh_token", true))
                .andExpect(cookie().secure("refresh_token", true))
                .andExpect(cookie().path("refresh_token", "/api/v1/auth"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")));
    }

    @Test
    @DisplayName("POST /api/v1/auth/kakao - 인가 코드 누락 시 400")
    void kakaoLogin_blankCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/google - 구글 로그인 성공")
    void googleLogin_success() throws Exception {
        given(authService.socialLogin("GOOGLE", "google_code"))
                .willReturn(new TokenResponse("g_access", "g_refresh"));

        mockMvc.perform(post("/api/v1/auth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("google_code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("g_access"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - 쿠키의 Refresh Token으로 갱신 성공")
    void refresh_success() throws Exception {
        given(authService.refresh("old_refresh"))
                .willReturn(new TokenResponse("new_access", "new_refresh"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("refresh_token", "old_refresh")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new_access"))
                .andExpect(cookie().value("refresh_token", "new_refresh"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/refresh - 쿠키 없이 요청하면 401")
    void refresh_withoutCookie_unauthorized() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/logout - 인증 없이 요청하면 401")
    void logout_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/logout - 로그아웃 성공 시 Refresh Token 쿠키가 만료된다")
    void logout_success() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/logout")
                        .with(authentication(
                                new UsernamePasswordAuthenticationToken(1L, null, List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge("refresh_token", 0));

        verify(authService).logout(1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/withdraw - 인증된 사용자 탈퇴 성공")
    void withdraw_success() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/withdraw")
                        .with(authentication(
                                new UsernamePasswordAuthenticationToken(1L, null, List.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).withdraw(1L);
    }
}
