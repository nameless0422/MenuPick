package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.AuthProvider;
import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthProviderRepository authProviderRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private OAuthProvider kakaoProvider;

    private AuthService authService;
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(
                "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!",
                1800000L, 1209600000L
        );
        authService = new AuthService(
                userRepository, authProviderRepository,
                jwtTokenProvider, redisTemplate, jwtProperties,
                List.of(kakaoProvider)
        );
    }

    @Test
    @DisplayName("신규 사용자 소셜 로그인 시 유저를 생성하고 토큰을 발급한다")
    void socialLogin_newUser() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "test@email.com", "테스트"));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.empty());

        User savedUser = User.builder().email("test@email.com").nickname("테스트").build();
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willReturn(AuthProvider.builder().user(savedUser).provider("KAKAO").socialId("kakao_123").build());

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        assertThat(result.refreshToken()).isEqualTo("refresh_token");
        verify(userRepository).save(any(User.class));
        verify(authProviderRepository).save(any(AuthProvider.class));
    }

    @Test
    @DisplayName("기존 사용자 소셜 로그인 시 새 유저를 생성하지 않고 토큰을 발급한다")
    void socialLogin_existingUser() {
        User existingUser = User.builder().email("existing@email.com").nickname("기존유저").build();
        AuthProvider existingProvider = AuthProvider.builder()
                .user(existingUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "existing@email.com", "기존유저"));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(existingProvider));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
    }

    @Test
    @DisplayName("탈퇴 후 유예기간 내 로그인하면 계정이 재활성화된다")
    void socialLogin_deletedUser_withinGracePeriod_reactivated() {
        User deletedUser = User.builder().email("deleted@email.com").nickname("탈퇴유저").build();
        deletedUser.softDelete();
        AuthProvider provider = AuthProvider.builder()
                .user(deletedUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "deleted@email.com", "탈퇴유저"));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(provider));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        assertThat(deletedUser.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("탈퇴 후 유예기간이 지나면 로그인 시 예외가 발생한다")
    void socialLogin_deletedUser_afterGracePeriod_throwsException() throws Exception {
        User deletedUser = User.builder().email("deleted@email.com").nickname("탈퇴유저").build();
        deletedUser.softDelete();

        // 리플렉션으로 deletedAt을 31일 전으로 설정
        var field = User.class.getDeclaredField("deletedAt");
        field.setAccessible(true);
        field.set(deletedUser, java.time.LocalDateTime.now().minusDays(31));

        AuthProvider provider = AuthProvider.builder()
                .user(deletedUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "deleted@email.com", "탈퇴유저"));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(provider));

        assertThatThrownBy(() -> authService.socialLogin("KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("유효한 Refresh Token으로 토큰을 재발급한다")
    void refresh_validToken() {
        given(jwtTokenProvider.validate("old_refresh")).willReturn(true);
        given(jwtTokenProvider.getUserId("old_refresh")).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("refresh:1")).willReturn("old_refresh");
        given(jwtTokenProvider.createAccessToken(1L)).willReturn("new_access");
        given(jwtTokenProvider.createRefreshToken(1L)).willReturn("new_refresh");

        TokenResponse result = authService.refresh("old_refresh");

        assertThat(result.accessToken()).isEqualTo("new_access");
        assertThat(result.refreshToken()).isEqualTo("new_refresh");
    }

    @Test
    @DisplayName("Redis에 저장된 것과 다른 Refresh Token이면 예외가 발생한다")
    void refresh_tokenMismatch() {
        given(jwtTokenProvider.validate("stolen_refresh")).willReturn(true);
        given(jwtTokenProvider.getUserId("stolen_refresh")).willReturn(1L);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.get("refresh:1")).willReturn("original_refresh");

        assertThatThrownBy(() -> authService.refresh("stolen_refresh"))
                .isInstanceOf(BusinessException.class);
        verify(redisTemplate).delete("refresh:1");
    }

    @Test
    @DisplayName("로그아웃 시 Redis에서 Refresh Token을 삭제한다")
    void logout() {
        authService.logout(1L);

        verify(redisTemplate).delete("refresh:1");
    }

    @Test
    @DisplayName("회원 탈퇴 시 soft delete 후 Redis Refresh Token을 삭제한다")
    void withdraw() {
        User user = User.builder().email("withdraw@email.com").nickname("탈퇴예정").build();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        authService.withdraw(1L);

        assertThat(user.isDeleted()).isTrue();
        verify(redisTemplate).delete("refresh:1");
    }
}
