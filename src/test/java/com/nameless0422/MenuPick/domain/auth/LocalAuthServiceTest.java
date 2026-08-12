package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.LoginAttemptLimiter;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.MeResponse;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.AuthProvider;
import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserHardDeleteService;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LocalAuthServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct-horse-battery";

    @Mock private UserRepository userRepository;
    @Mock private AuthProviderRepository authProviderRepository;
    @Mock private UserHardDeleteService userHardDeleteService;
    @Mock private LoginAttemptLimiter loginAttemptLimiter;
    @Mock private AuthTokenStore authTokenStore;
    @Mock private AuthMailer authMailer;
    @Mock private TokenIssuer tokenIssuer;

    /**
     * 해시 검증 동작 자체가 검사 대상이라 인코더만 실물을 쓴다.
     *
     * <p>알고리즘 선택(argon2로 인코딩, bcrypt도 검증)은 운영과 같지만 비용 파라미터는 낮췄다 —
     * 이 클래스가 수십 번 인코딩하므로 운영 설정(m=9216, t=4)을 그대로 쓰면 테스트가 몇 초씩 느려진다.
     * 운영 파라미터의 근거와 값은 {@code SecurityConfig#passwordEncoder()}에 있다.
     */
    private final PasswordEncoder passwordEncoder = new DelegatingPasswordEncoder(
            "argon2",
            Map.of(
                    "argon2", new Argon2PasswordEncoder(16, 32, 1, 1024, 1),
                    "bcrypt", new BCryptPasswordEncoder(4)
            ));

    private LocalAuthService localAuthService;

    /** 트랜잭션 경계만 흉내내는 최소 매니저 — AuthServiceTest와 같은 이유로 실제 리소스는 없다. */
    private static final PlatformTransactionManager NO_OP_TX_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    @BeforeEach
    void setUp() {
        localAuthService = new LocalAuthService(
                userRepository, authProviderRepository, userHardDeleteService,
                loginAttemptLimiter, passwordEncoder, authTokenStore, authMailer,
                tokenIssuer, new TransactionTemplate(NO_OP_TX_MANAGER)
        );
        localAuthService.initDummyHash();

        given(tokenIssuer.issue(anyLong()))
                .willReturn(new TokenResponse("access_token", "refresh_token"));
    }

    /** 저장된 자체 계정 한 건을 만든다. id는 JPA가 채우는 값이라 리플렉션으로 넣는다. */
    private AuthProvider localAccount(long userId, String email, String rawPassword, boolean verified) {
        User user = User.builder()
                .email(verified ? email : null)
                .emailVerified(verified)
                .nickname("테스터")
                .build();
        ReflectionTestUtils.setField(user, "id", userId);

        return AuthProvider.builder()
                .user(user)
                .provider(AuthProvider.LOCAL)
                .socialId(email)
                .passwordHash(passwordEncoder.encode(rawPassword))
                .build();
    }

    @Nested
    @DisplayName("가입")
    class Signup {

        @Test
        @DisplayName("새 이메일이면 유저와 자격증명을 만들고 인증 메일을 보낸다")
        void newAccount() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());

            User saved = User.builder().nickname("테스터").emailVerified(false).build();
            ReflectionTestUtils.setField(saved, "id", 1L);
            given(userRepository.save(any(User.class))).willReturn(saved);
            given(authTokenStore.issue(eq(AuthTokenStore.Purpose.VERIFY_EMAIL), eq(1L), any()))
                    .willReturn("verify-token");

            localAuthService.signup(EMAIL, PASSWORD, "테스터");

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            // 인증 전에는 users.email을 비워 둬야 한다 — 소셜 로그인의 이메일 기준 자동 병합이
            // 미검증 주소를 집어삼키면 계정 탈취 경로가 열린다.
            assertThat(userCaptor.getValue().getEmail()).isNull();
            assertThat(userCaptor.getValue().isEmailVerified()).isFalse();

            ArgumentCaptor<AuthProvider> providerCaptor = ArgumentCaptor.forClass(AuthProvider.class);
            verify(authProviderRepository).save(providerCaptor.capture());
            assertThat(providerCaptor.getValue().getSocialId()).isEqualTo(EMAIL);
            // 원문이 아니라 인코딩 결과가 저장돼야 하고, 새 해시는 argon2여야 한다
            assertThat(providerCaptor.getValue().getPasswordHash())
                    .isNotEqualTo(PASSWORD)
                    .startsWith("{argon2}");

            verify(authMailer).sendVerification(eq(EMAIL), eq("verify-token"), any(Duration.class));
        }

        @Test
        @DisplayName("대소문자와 공백이 달라도 같은 계정으로 취급한다")
        void normalizesEmail() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());
            User saved = User.builder().nickname("테스터").build();
            ReflectionTestUtils.setField(saved, "id", 1L);
            given(userRepository.save(any(User.class))).willReturn(saved);

            localAuthService.signup("  User@Example.COM  ", PASSWORD, "테스터");

            verify(authProviderRepository).findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL);
        }

        @Test
        @DisplayName("이미 인증된 계정이 있으면 409로 막는다")
        void alreadyRegistered() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(localAccount(1L, EMAIL, PASSWORD, true)));

            assertThatThrownBy(() -> localAuthService.signup(EMAIL, PASSWORD, "테스터"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("미인증 계정이 있으면 덮어쓴다 — 주소 선점으로 남의 가입을 막지 못하게")
        void overwritesUnverified() {
            AuthProvider pending = localAccount(1L, EMAIL, "old-password-1234", false);
            String oldHash = pending.getPasswordHash();
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(pending));

            localAuthService.signup(EMAIL, PASSWORD, "새닉네임");

            assertThat(pending.getPasswordHash()).isNotEqualTo(oldHash);
            assertThat(passwordEncoder.matches(PASSWORD, pending.getPasswordHash())).isTrue();
            assertThat(pending.getUser().getNickname()).isEqualTo("새닉네임");
            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("긴 비밀번호도 잘리지 않는다 — BCrypt와 달리 Argon2는 72바이트 절단이 없다")
        void doesNotTruncateLongPassword() {
            // 한글 30자 = 90바이트. BCrypt였다면 24자 이후가 잘려 아래 두 비밀번호가 같은 해시를 갖는다.
            String longPassword = "가".repeat(30);
            String longerPassword = longPassword + "다르다";

            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());
            User saved = User.builder().nickname("테스터").build();
            ReflectionTestUtils.setField(saved, "id", 1L);
            given(userRepository.save(any(User.class))).willReturn(saved);

            localAuthService.signup(EMAIL, longPassword, "테스터");

            ArgumentCaptor<AuthProvider> captor = ArgumentCaptor.forClass(AuthProvider.class);
            verify(authProviderRepository).save(captor.capture());
            String hash = captor.getValue().getPasswordHash();

            assertThat(passwordEncoder.matches(longPassword, hash)).isTrue();
            assertThat(passwordEncoder.matches(longerPassword, hash)).isFalse();
        }
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("인증된 계정에 올바른 비밀번호면 토큰을 발급하고 실패 카운터를 지운다")
        void success() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(localAccount(7L, EMAIL, PASSWORD, true)));

            TokenResponse result = localAuthService.login(EMAIL, PASSWORD);

            assertThat(result.accessToken()).isEqualTo("access_token");
            verify(tokenIssuer).issue(7L);
            verify(loginAttemptLimiter).reset(EMAIL);
        }

        @Test
        @DisplayName("비밀번호가 틀리면 401과 함께 실패를 센다")
        void wrongPassword() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(localAccount(7L, EMAIL, PASSWORD, true)));

            assertThatThrownBy(() -> localAuthService.login(EMAIL, "wrong-password"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);

            verify(loginAttemptLimiter).recordFailure(EMAIL);
            verify(tokenIssuer, never()).issue(anyLong());
        }

        @Test
        @DisplayName("없는 계정도 같은 코드로 응답한다 — 가입 여부를 흘리지 않는다")
        void unknownAccount() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> localAuthService.login(EMAIL, PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);
        }

        @Test
        @DisplayName("메일 인증 전에는 로그인할 수 없다")
        void notVerified() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(localAccount(7L, EMAIL, PASSWORD, false)));

            assertThatThrownBy(() -> localAuthService.login(EMAIL, PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.EMAIL_NOT_VERIFIED);

            // 비밀번호는 맞았으므로 실패로 세지 않는다 — 안내를 보려고 재시도하다 잠기면 안 된다.
            verify(loginAttemptLimiter, never()).recordFailure(EMAIL);
        }

        @Test
        @DisplayName("시도 한도를 넘겼으면 비밀번호를 보기도 전에 막는다")
        void tooManyAttempts() {
            org.mockito.BDDMockito.willThrow(new BusinessException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS))
                    .given(loginAttemptLimiter).checkAllowed(EMAIL);

            assertThatThrownBy(() -> localAuthService.login(EMAIL, PASSWORD))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);

            verify(authProviderRepository, never())
                    .findByProviderAndSocialId(any(), any());
        }

        @Test
        @DisplayName("bcrypt로 저장된 기존 해시도 계속 검증된다 — 알고리즘 교체가 기존 계정을 잠그지 않는다")
        void legacyBcryptHashStillVerifies() {
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            // DelegatingPasswordEncoder는 접두사를 보고 알고리즘을 고른다.
            provider.changePassword("{bcrypt}" + new BCryptPasswordEncoder(4).encode(PASSWORD));
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(provider));

            TokenResponse result = localAuthService.login(EMAIL, PASSWORD);

            assertThat(result.accessToken()).isEqualTo("access_token");
        }

        @Test
        @DisplayName("탈퇴 유예기간 안이면 계정을 되살린다")
        void reactivatesWithinGrace() {
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            provider.getUser().softDelete();
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(provider));

            localAuthService.login(EMAIL, PASSWORD);

            assertThat(provider.getUser().isDeleted()).isFalse();
            // 이미 검증된 주소는 그대로 유지돼야 한다
            assertThat(provider.getUser().getEmail()).isEqualTo(EMAIL);
            assertThat(provider.getUser().isEmailVerified()).isTrue();
            verify(tokenIssuer).issue(7L);
        }
    }

    @Nested
    @DisplayName("메일 인증")
    class VerifyEmail {

        @Test
        @DisplayName("토큰이 유효하면 users.email을 확정하고 로그인시킨다")
        void confirms() {
            AuthProvider pending = localAccount(3L, EMAIL, PASSWORD, false);
            given(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, "tok"))
                    .willReturn(Optional.of(3L));
            given(authProviderRepository.findByUserIdAndProvider(3L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(pending));
            given(userRepository.findByEmail(EMAIL)).willReturn(Optional.empty());

            TokenResponse result = localAuthService.verifyEmail("tok");

            assertThat(pending.getUser().getEmail()).isEqualTo(EMAIL);
            assertThat(pending.getUser().isEmailVerified()).isTrue();
            assertThat(result.accessToken()).isEqualTo("access_token");
        }

        @Test
        @DisplayName("같은 주소의 소셜 계정이 이미 있으면 그쪽에 붙이고 임시 계정을 지운다")
        void mergesIntoExistingSocialAccount() {
            AuthProvider pending = localAccount(3L, EMAIL, PASSWORD, false);
            User pendingUser = pending.getUser();

            User socialUser = User.builder().email(EMAIL).emailVerified(true).nickname("소셜").build();
            ReflectionTestUtils.setField(socialUser, "id", 9L);

            given(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, "tok"))
                    .willReturn(Optional.of(3L));
            given(authProviderRepository.findByUserIdAndProvider(3L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(pending));
            given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(socialUser));

            localAuthService.verifyEmail("tok");

            // 자격증명의 주인이 기존 계정으로 옮겨가고, 로그인도 그 계정으로 이뤄져야 한다.
            assertThat(pending.getUser()).isSameAs(socialUser);
            verify(userRepository).delete(pendingUser);
            verify(tokenIssuer).issue(9L);
        }

        @Test
        @DisplayName("만료·재사용된 토큰은 400으로 끊는다")
        void invalidToken() {
            given(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, "tok"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> localAuthService.verifyEmail("tok"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_AUTH_TOKEN);
        }
    }

    @Nested
    @DisplayName("비밀번호 변경·재설정")
    class Password {

        @Test
        @DisplayName("현재 비밀번호가 맞으면 새 해시로 바꾸고 세션을 새로 발급한다")
        void change() {
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(provider));

            localAuthService.changePassword(7L, PASSWORD, "brand-new-password");

            assertThat(passwordEncoder.matches("brand-new-password", provider.getPasswordHash()))
                    .isTrue();
            verify(tokenIssuer).issue(7L);
        }

        @Test
        @DisplayName("현재 비밀번호가 틀리면 바꾸지 않는다")
        void changeWithWrongCurrent() {
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            String original = provider.getPasswordHash();
            given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(provider));

            assertThatThrownBy(() ->
                    localAuthService.changePassword(7L, "wrong", "brand-new-password"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.INVALID_CREDENTIALS);

            assertThat(provider.getPasswordHash()).isEqualTo(original);
            verify(tokenIssuer, never()).issue(anyLong());
        }

        @Test
        @DisplayName("소셜 전용 계정은 비밀번호를 바꿀 수 없다")
        void changeOnSocialOnlyAccount() {
            given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> localAuthService.changePassword(7L, "x", "brand-new-password"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.LOCAL_ACCOUNT_REQUIRED);
        }

        @Test
        @DisplayName("재설정 요청은 가입 여부와 무관하게 성공으로 끝난다")
        void requestResetForUnknownEmail() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());

            localAuthService.requestPasswordReset(EMAIL);

            verify(authMailer, never()).sendPasswordReset(any(), any(), any());
        }

        @Test
        @DisplayName("재설정 토큰이 유효하면 비밀번호를 바꾸고 세션을 새로 발급한다")
        void confirmReset() {
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            given(authTokenStore.consume(AuthTokenStore.Purpose.PASSWORD_RESET, "tok"))
                    .willReturn(Optional.of(7L));
            given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(provider));

            localAuthService.confirmPasswordReset("tok", "brand-new-password");

            assertThat(passwordEncoder.matches("brand-new-password", provider.getPasswordHash()))
                    .isTrue();
            verify(tokenIssuer).issue(7L);
        }
    }

    @Test
    @DisplayName("계정 조회는 자체 자격증명 보유 여부를 함께 알려준다")
    void me() {
        User user = User.builder().email(EMAIL).emailVerified(true).nickname("테스터").build();
        ReflectionTestUtils.setField(user, "id", 7L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                .willReturn(Optional.of(localAccount(7L, EMAIL, PASSWORD, true)));

        MeResponse result = localAuthService.me(7L);

        assertThat(result.email()).isEqualTo(EMAIL);
        assertThat(result.nickname()).isEqualTo("테스터");
        assertThat(result.hasPassword()).isTrue();
    }
}
