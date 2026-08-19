package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
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

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest extends AbstractControllerTest {

    @MockitoBean private AuthService authService;
    @MockitoBean private LocalAuthService localAuthService;

    @Test
    @DisplayName("GET /api/v1/auth/kakao/authorize - 동의 화면으로 302, client_id는 응답에 노출되지 않는다")
    void kakaoAuthorize_redirects() throws Exception {
        given(authService.authorizeUrl("KAKAO", "0123456789abcdef"))
                .willReturn("https://kauth.kakao.com/oauth/authorize?client_id=secret&state=0123456789abcdef");

        mockMvc.perform(get("/api/v1/auth/kakao/authorize").param("state", "0123456789abcdef"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "https://kauth.kakao.com/oauth/authorize?client_id=secret&state=0123456789abcdef"))
                // 사용자마다 state가 달라 중간 캐시가 남기면 남의 인가 요청을 재사용하게 된다.
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/google/authorize - state 없이 호출하면 400")
    void googleAuthorize_requiresState() throws Exception {
        mockMvc.perform(get("/api/v1/auth/google/authorize"))
                .andExpect(status().isBadRequest());
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
    @DisplayName("GET /api/v1/auth/me - 계정 정보와 비밀번호 보유 여부, 연동 목록을 반환한다")
    void me_success() throws Exception {
        given(localAuthService.me(1L))
                .willReturn(new MeResponse("user@example.com", "테스터", true, List.of("KAKAO")));

        mockMvc.perform(get("/api/v1/auth/me").with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("user@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("테스터"))
                .andExpect(jsonPath("$.data.hasPassword").value(true))
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("KAKAO"));
    }

    @Test
    @DisplayName("GET /api/v1/auth/me - 인증 없이 요청하면 401")
    void me_unauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 소셜 계정 연동 ----

    @Test
    @DisplayName("POST /api/v1/auth/{provider}/link - 연동 후 갱신된 연동 목록을 반환한다")
    void linkSocialAccount_success() throws Exception {
        given(authService.linkSocialAccount(1L, "kakao", "link_code")).willReturn(List.of("KAKAO"));

        mockMvc.perform(post("/api/v1/auth/kakao/link")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("link_code"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedProviders[0]").value("KAKAO"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/{provider}/link - 인증 없이 요청하면 401")
    void linkSocialAccount_unauthorized() throws Exception {
        // permitAll 목록에 딸려 들어가면 남의 계정에 소셜 계정을 붙일 수 있게 된다.
        mockMvc.perform(post("/api/v1/auth/kakao/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("link_code"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/auth/{provider}/link - 인가 코드 누락 시 400")
    void linkSocialAccount_blankCode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/kakao/link")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest(""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/{provider}/link - 해제 후 남은 연동 목록을 반환한다")
    void unlinkSocialAccount_success() throws Exception {
        given(authService.unlinkSocialAccount(1L, "kakao")).willReturn(List.of());

        mockMvc.perform(delete("/api/v1/auth/kakao/link").with(authentication(AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.linkedProviders").isEmpty());
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/{provider}/link - 인증 없이 요청하면 401")
    void unlinkSocialAccount_unauthorized() throws Exception {
        mockMvc.perform(delete("/api/v1/auth/kakao/link"))
                .andExpect(status().isUnauthorized());
    }

    // ---- 거절 경로 ----
    //
    // 서비스가 던진 사유가 HTTP 상태와 errorCode로 온전히 나가는지 본다. 프론트는 이 두 값으로만
    // 화면을 가르므로(콜백 화면의 SOCIAL_ACCOUNT_NOT_LINKED 분기, 설정 화면의 실패 문구),
    // 상태 코드가 뭉개지면 사용자는 "로그인 실패"라는 말만 보고 이유를 영영 알 수 없게 된다.

    @Test
    @DisplayName("POST /api/v1/auth/kakao - 연동된 적 없는 소셜 계정이면 401 SOCIAL_ACCOUNT_NOT_LINKED")
    void socialLogin_notLinked_unauthorized() throws Exception {
        willThrow(new BusinessException(ErrorCode.SOCIAL_ACCOUNT_NOT_LINKED))
                .given(authService).socialLogin("KAKAO", "test_code");

        mockMvc.perform(post("/api/v1/auth/kakao")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("test_code"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SOCIAL_ACCOUNT_NOT_LINKED"))
                // 거절인데 세션 쿠키가 나가면 로그인하지 못한 사용자가 갱신 쿠키만 쥐게 된다
                .andExpect(cookie().doesNotExist("refresh_token"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/{provider}/link - 남의 계정에 연동된 소셜 계정이면 409 SOCIAL_ACCOUNT_TAKEN")
    void linkSocialAccount_takenByAnotherUser_conflict() throws Exception {
        willThrow(new BusinessException(ErrorCode.SOCIAL_ACCOUNT_TAKEN))
                .given(authService).linkSocialAccount(1L, "kakao", "link_code");

        mockMvc.perform(post("/api/v1/auth/kakao/link")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("link_code"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SOCIAL_ACCOUNT_TAKEN"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/{provider}/link - 이미 연동돼 있으면 409 SOCIAL_ALREADY_LINKED")
    void linkSocialAccount_alreadyLinked_conflict() throws Exception {
        willThrow(new BusinessException(ErrorCode.SOCIAL_ALREADY_LINKED))
                .given(authService).linkSocialAccount(1L, "kakao", "link_code");

        mockMvc.perform(post("/api/v1/auth/kakao/link")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("link_code"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("SOCIAL_ALREADY_LINKED"));
    }

    @Test
    @DisplayName("POST /api/v1/auth/{provider}/link - 지원하지 않는 제공자면 400")
    void linkSocialAccount_unknownProvider_badRequest() throws Exception {
        // 제공자 이름은 경로 변수라 아무 문자열이나 들어온다. 404가 아니라 400인 것은
        // 매핑이 없는 게 아니라 값이 잘못된 것이기 때문이다(AuthService.findProvider).
        willThrow(new BusinessException(ErrorCode.INVALID_INPUT))
                .given(authService).linkSocialAccount(1L, "naver", "link_code");

        mockMvc.perform(post("/api/v1/auth/naver/link")
                        .with(authentication(AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new OAuthLoginRequest("link_code"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/{provider}/link - 연동한 적 없는 제공자면 404 SOCIAL_LINK_NOT_FOUND")
    void unlinkSocialAccount_notLinked_notFound() throws Exception {
        willThrow(new BusinessException(ErrorCode.SOCIAL_LINK_NOT_FOUND))
                .given(authService).unlinkSocialAccount(1L, "kakao");

        mockMvc.perform(delete("/api/v1/auth/kakao/link").with(authentication(AUTH)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("SOCIAL_LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("DELETE /api/v1/auth/{provider}/link - 마지막 로그인 수단이면 409 LAST_LOGIN_METHOD")
    void unlinkSocialAccount_lastLoginMethod_conflict() throws Exception {
        // 통과시키면 그 계정에는 영원히 들어갈 수 없고, 탈퇴조차 못 해 데이터만 남는다.
        willThrow(new BusinessException(ErrorCode.LAST_LOGIN_METHOD))
                .given(authService).unlinkSocialAccount(1L, "kakao");

        mockMvc.perform(delete("/api/v1/auth/kakao/link").with(authentication(AUTH)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("LAST_LOGIN_METHOD"));
    }
}
