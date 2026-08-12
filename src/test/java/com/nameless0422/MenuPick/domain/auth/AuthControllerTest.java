package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.EmailRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.LoginRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.OAuthLoginRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.PasswordChangeRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.SignupRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthRequest.TokenRequest;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.MeResponse;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.support.AbstractControllerTest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest extends AbstractControllerTest {

    @MockitoBean private AuthService authService;
    @MockitoBean private LocalAuthService localAuthService;

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
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(cookie().maxAge("refresh_token", 0));

        verify(authService).logout(1L);
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/withdraw - 인증된 사용자 탈퇴 성공")
    void withdraw_success() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/withdraw")
                        .with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(authService).withdraw(1L);
    }

    // ---- 자체 계정 ----

    @Test
    @DisplayName("POST /api/v1/auth/signup - 가입 성공 시 201이고 토큰을 주지 않는다 (인증 전이라 로그인 불가)")
    void signup_success() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("user@example.com", "password1234", "테스터"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(cookie().doesNotExist("refresh_token"));

        verify(localAuthService).signup("user@example.com", "password1234", "테스터");
    }

    @Test
    @DisplayName("POST /api/v1/auth/signup - 이메일 형식이 아니면 400")
    void signup_invalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("not-an-email", "password1234", "테스터"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/signup - 비밀번호가 8자 미만이면 400")
    void signup_shortPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("user@example.com", "short", "테스터"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/v1/auth/login - 로그인 성공 (Refresh Token은 HttpOnly 쿠키로만 전달)")
    void login_success() throws Exception {
        given(localAuthService.login("user@example.com", "password1234"))
                .willReturn(new TokenResponse("access_token", "refresh_token"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("user@example.com", "password1234"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access_token"))
                .andExpect(jsonPath("$.data.refreshToken").doesNotExist())
                .andExpect(cookie().value("refresh_token", "refresh_token"))
                .andExpect(cookie().httpOnly("refresh_token", true));
    }

    @Test
    @DisplayName("POST /api/v1/auth/verify-email - 인증과 동시에 로그인된다")
    void verifyEmail_success() throws Exception {
        given(localAuthService.verifyEmail("verify-token"))
                .willReturn(new TokenResponse("access_token", "refresh_token"));

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TokenRequest("verify-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access_token"))
                .andExpect(cookie().value("refresh_token", "refresh_token"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/password-reset - 가입 여부와 무관하게 200 (계정 존재 여부를 흘리지 않는다)")
    void requestPasswordReset_alwaysOk() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new EmailRequest("nobody@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(localAuthService).requestPasswordReset("nobody@example.com");
    }

    @Test
    @DisplayName("PATCH /api/v1/auth/password - 인증 없이 요청하면 401")
    void changePassword_unauthorized() throws Exception {
        mockMvc.perform(patch("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordChangeRequest("old-password", "new-password-1"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PATCH /api/v1/auth/password - 변경 성공 시 새 세션을 발급한다")
    void changePassword_success() throws Exception {
        given(localAuthService.changePassword(1L, "old-password", "new-password-1"))
                .willReturn(new TokenResponse("new_access", "new_refresh"));

        mockMvc.perform(patch("/api/v1/auth/password")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordChangeRequest("old-password", "new-password-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("new_access"))
                .andExpect(cookie().value("refresh_token", "new_refresh"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - 계정 정보와 비밀번호 보유 여부를 반환한다")
    void me_success() throws Exception {
        given(localAuthService.me(1L))
                .willReturn(new MeResponse("user@example.com", "테스터", true));

        mockMvc.perform(get("/api/v1/auth/me").with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("user@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("테스터"))
                .andExpect(jsonPath("$.data.hasPassword").value(true));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - 인증 없이 요청하면 401")
    void me_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }
}
