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

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
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

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 0, 30, 0, 0, KST).toInstant(), KST);
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);

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
                tokenIssuer, new TransactionTemplate(NO_OP_TX_MANAGER), FIXED_CLOCK
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

        /**
         * 예전에는 여기서 409 EMAIL_ALREADY_REGISTERED를 돌려줬다. 그러면 재발송·비밀번호
         * 재설정이 "대상이 없어도 성공으로 끝낸다"며 막아 둔 사용자 열거가 무의미해진다 —
         * 주소 목록을 가입 API에 넣고 409/201만 구분하면 가입 여부가 그대로 수집된다.
         */
        @Test
        @DisplayName("이미 가입된 주소여도 새 가입과 같은 응답을 준다 (사용자 열거 차단)")
        void alreadyRegistered_respondsSameAsNewSignup() {
            given(userRepository.existsByEmailAndDeletedAtIsNull(EMAIL)).willReturn(true);

            localAuthService.signup(EMAIL, PASSWORD, "테스터");

            verify(userRepository, never()).save(any());
            // 인증 메일이 나가면 "이 주소는 미가입"이라는 사실이 메일함 쪽으로 새어 나간다.
            verify(authMailer, never()).sendVerification(anyString(), anyString(), any(Duration.class));
        }

        /**
         * 응답만 통일하고 아무것도 보내지 않으면, 정말로 자기가 가입을 시도한 사람이
         * 오지 않는 인증 메일을 기다리다 막힌다.
         */
        @Test
        @DisplayName("이미 가입된 주소에는 그 주소로 안내 메일이 간다")
        void alreadyRegistered_sendsNotice() {
            given(userRepository.existsByEmailAndDeletedAtIsNull(EMAIL)).willReturn(true);

            localAuthService.signup(EMAIL, PASSWORD, "테스터");

            verify(authMailer).sendAlreadyRegistered(EMAIL);
        }

        /**
         * #71 이전에는 소셜 로그인이 계정을 만들었다. 그 시절 계정은 users.email이 있고
         * LOCAL 행이 없을 수 있어, provider 조회만으로는 걸리지 않고 pending이 만들어졌다.
         * 그 pending은 메일 인증 시점에 병합 분기로 흘러 남이 정한 비밀번호를 그 계정에 옮긴다.
         */
        @Test
        @DisplayName("LOCAL 행이 없는 레거시 소셜 계정의 주소도 새 pending을 만들지 않는다")
        void legacySocialOnlyAccount_isTreatedAsRegistered() {
            given(userRepository.existsByEmailAndDeletedAtIsNull(EMAIL)).willReturn(true);
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());

            localAuthService.signup(EMAIL, PASSWORD, "테스터");

            verify(userRepository, never()).save(any());
            verify(authProviderRepository, never()).save(any());
        }

        /**
         * 탈퇴 계정까지 "이미 가입됨"으로 막으면 같은 주소로 다시 가입해 돌아오는 길이 끊긴다.
         * 비밀번호 재설정도 탈퇴 계정은 대상에서 빼므로 되돌아올 수단이 하나도 남지 않는다.
         */
        @Test
        @DisplayName("탈퇴한 계정의 주소는 막지 않는다 — 재가입으로 돌아올 수 있어야 한다")
        void withdrawnAccountAddress_isNotBlocked() {
            given(userRepository.existsByEmailAndDeletedAtIsNull(EMAIL)).willReturn(false);
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());
            User saved = User.builder().nickname("테스터").emailVerified(false).build();
            ReflectionTestUtils.setField(saved, "id", 1L);
            given(userRepository.save(any(User.class))).willReturn(saved);
            given(authTokenStore.issue(eq(AuthTokenStore.Purpose.VERIFY_EMAIL), eq(1L), any()))
                    .willReturn("verify-token");

            localAuthService.signup(EMAIL, PASSWORD, "테스터");

            verify(authMailer).sendVerification(eq(EMAIL), eq("verify-token"), any(Duration.class));
        }

        /**
         * 주소 검사가 닉네임 검사보다 뒤에 있으면, 이미 가입된 주소에 이미 쓰이는 닉네임을
         * 넣었을 때만 409가 나온다 — 응답 차이가 다시 생겨 열거가 되살아난다.
         */
        @Test
        @DisplayName("이미 가입된 주소면 닉네임이 겹쳐도 409가 아니라 같은 응답을 준다")
        void alreadyRegistered_isNotMaskedByNicknameConflict() {
            given(userRepository.existsByEmailAndDeletedAtIsNull(EMAIL)).willReturn(true);
            given(userRepository.existsByNickname("테스터")).willReturn(true);

            localAuthService.signup(EMAIL, PASSWORD, "테스터");

            verify(authMailer).sendAlreadyRegistered(EMAIL);
        }

        @Test
        @DisplayName("이미 쓰이는 닉네임이면 409로 막는다")
        void duplicateNickname() {
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.empty());
            given(userRepository.existsByNickname("테스터")).willReturn(true);

            // 자체 가입은 사용자가 직접 고른 값이라 다시 고를 수 있다 — 소셜 로그인처럼
            // 번호를 붙여 넘기면 원하지도 않은 이름을 받게 된다.
            assertThatThrownBy(() -> localAuthService.signup(EMAIL, PASSWORD, "테스터"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.NICKNAME_ALREADY_REGISTERED);

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("미인증 계정이 자기 닉네임을 그대로 다시 보내면 중복이 아니다")
        void keepingOwnNicknameIsNotDuplicate() {
            // 자기 행이 인덱스를 점유하고 있어 그대로 검사하면 자기 이름에 자기가 걸린다.
            AuthProvider pending = localAccount(1L, EMAIL, "old-password-1234", false);
            pending.getUser().updateNickname("테스터");
            given(authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, EMAIL))
                    .willReturn(Optional.of(pending));

            localAuthService.signup(EMAIL, PASSWORD, "테스터");

            assertThat(pending.getUser().getNickname()).isEqualTo("테스터");
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
            provider.getUser().softDelete(NOW);
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
        @DisplayName("같은 주소의 계정이 탈퇴 상태여도 유예기간 안이면 되살린 뒤 병합한다")
        void mergeIntoWithdrawnOwner_withinGrace_reactivates() {
            AuthProvider pending = localAccount(3L, EMAIL, PASSWORD, false);
            User pendingUser = pending.getUser();

            User withdrawn = User.builder().email(EMAIL).emailVerified(true).nickname("소셜").build();
            ReflectionTestUtils.setField(withdrawn, "id", 9L);
            withdrawn.softDelete(NOW.minusDays(25));

            given(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, "tok"))
                    .willReturn(Optional.of(3L));
            given(authProviderRepository.findByUserIdAndProvider(3L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(pending));
            given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(withdrawn));

            localAuthService.verifyEmail("tok");

            // deletedAt이 남은 채 병합되면 로그인은 되지만, 사용자가 복귀했다고 믿는 사이
            // 유예기간이 끝나면서 정리 배치가 메뉴·식당·히스토리를 전부 하드 삭제한다.
            assertThat(withdrawn.isDeleted()).isFalse();
            // 이미 검증된 주소와 쓰던 닉네임은 그대로 유지된다.
            assertThat(withdrawn.getEmail()).isEqualTo(EMAIL);
            assertThat(withdrawn.isEmailVerified()).isTrue();
            assertThat(withdrawn.getNickname()).isEqualTo("소셜");

            assertThat(pending.getUser()).isSameAs(withdrawn);
            verify(userRepository).delete(pendingUser);
            verify(userHardDeleteService, never()).purge(anyLong());
            verify(tokenIssuer).issue(9L);
        }

        @Test
        @DisplayName("같은 주소의 탈퇴 계정이 유예기간을 넘겼으면 옛 데이터를 지우고 이 계정이 주소를 가져간다")
        void mergeIntoWithdrawnOwner_pastGrace_purgesAndKeepsNewAccount() {
            AuthProvider pending = localAccount(3L, EMAIL, PASSWORD, false);
            User pendingUser = pending.getUser();

            User withdrawn = User.builder().email(EMAIL).emailVerified(true).nickname("소셜").build();
            ReflectionTestUtils.setField(withdrawn, "id", 9L);
            withdrawn.softDelete(NOW.minusDays(31));

            given(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, "tok"))
                    .willReturn(Optional.of(3L));
            given(authProviderRepository.findByUserIdAndProvider(3L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(pending));
            // 정리가 끝난 뒤 다시 보면 그 주소를 붙잡고 있던 행은 사라져 있다.
            given(userRepository.findByEmail(EMAIL))
                    .willReturn(Optional.of(withdrawn), Optional.empty());

            localAuthService.verifyEmail("tok");

            // 소셜 경로(AuthService.resolveUser)와 같은 판단 — 유예가 지났으면 되살리지 않고 버린다.
            verify(userHardDeleteService).purge(9L);
            assertThat(pending.getUser()).isSameAs(pendingUser);
            assertThat(pendingUser.getEmail()).isEqualTo(EMAIL);
            assertThat(pendingUser.isEmailVerified()).isTrue();
            verify(userRepository, never()).delete(any(User.class));
            verify(tokenIssuer).issue(3L);
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
        @DisplayName("현재 비밀번호 실패는 로그인과 같은 계정 버킷에 집계된다")
        void changeWithWrongCurrent_countsAgainstLoginBucket() {
            // 버킷을 나누면 공격자가 login과 changePassword를 번갈아 써서 한도를 두 배로 늘린다.
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(provider));

            assertThatThrownBy(() ->
                    localAuthService.changePassword(7L, "wrong", "brand-new-password"))
                    .isInstanceOf(BusinessException.class);

            verify(loginAttemptLimiter).recordFailure(EMAIL);
            verify(loginAttemptLimiter, never()).reset(EMAIL);
        }

        @Test
        @DisplayName("이미 잠긴 계정은 현재 비밀번호를 검증조차 하지 않는다")
        void changeWhenLockedOut_doesNotVerify() {
            // Argon2 검증에 들어가기 전에 막아야 한다 — 요청마다 m=9216KiB가 잡히므로
            // 검증까지 가는 것 자체가 증폭 수단이 된다.
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            String original = provider.getPasswordHash();
            given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(provider));
            willThrow(new BusinessException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS))
                    .given(loginAttemptLimiter).checkAllowed(EMAIL);

            assertThatThrownBy(() ->
                    localAuthService.changePassword(7L, PASSWORD, "brand-new-password"))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);

            // 맞는 비밀번호를 줬는데도 바뀌지 않았다 = 검증 자체에 도달하지 않았다
            assertThat(provider.getPasswordHash()).isEqualTo(original);
            verify(tokenIssuer, never()).issue(anyLong());
        }

        @Test
        @DisplayName("성공하면 잠금 카운터를 초기화한다")
        void changeSucceeds_resetsCounter() {
            AuthProvider provider = localAccount(7L, EMAIL, PASSWORD, true);
            given(authProviderRepository.findByUserIdAndProvider(7L, AuthProvider.LOCAL))
                    .willReturn(Optional.of(provider));

            localAuthService.changePassword(7L, PASSWORD, "brand-new-password");

            verify(loginAttemptLimiter).reset(EMAIL);
            verify(loginAttemptLimiter, never()).recordFailure(EMAIL);
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
    @DisplayName("계정 조회는 자체 자격증명 보유 여부와 연동된 소셜 제공자를 함께 알려준다")
    void me() {
        User user = User.builder().email(EMAIL).emailVerified(true).nickname("테스터").build();
        ReflectionTestUtils.setField(user, "id", 7L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(authProviderRepository.findAllByUserId(7L)).willReturn(List.of(
                localAccount(7L, EMAIL, PASSWORD, true),
                AuthProvider.builder().user(user).provider("KAKAO").socialId("kakao_1").build()));

        MeResponse result = localAuthService.me(7L);

        assertThat(result.email()).isEqualTo(EMAIL);
        assertThat(result.nickname()).isEqualTo("테스터");
        assertThat(result.hasPassword()).isTrue();
        // LOCAL은 연동한 소셜 계정이 아니라 자체 자격증명이라 목록에 들어가면 안 된다 —
        // 설정 화면이 "LOCAL 연동 해제" 버튼을 그리게 된다.
        assertThat(result.linkedProviders()).containsExactly("KAKAO");
    }

    @Test
    @DisplayName("계정 조회 - 소셜 연동이 없으면 빈 목록을 준다")
    void me_withoutSocialLink() {
        User user = User.builder().email(EMAIL).emailVerified(true).nickname("테스터").build();
        ReflectionTestUtils.setField(user, "id", 7L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(authProviderRepository.findAllByUserId(7L))
                .willReturn(List.of(localAccount(7L, EMAIL, PASSWORD, true)));

        assertThat(localAuthService.me(7L).linkedProviders()).isEmpty();
    }
}
