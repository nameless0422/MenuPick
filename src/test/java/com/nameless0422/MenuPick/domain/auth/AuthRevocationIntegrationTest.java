package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 보안 분석에서 재현한 토큰 무효화 문제의 회귀 테스트. 실제 MySQL 잠금과 Redis 스크립트를 쓴다.
 *
 * <p>순서를 제어하는 테스트는 {@link MockitoSpyBean}으로 취약했던 틈(DB 커밋 후 Redis 폐기 전,
 * 토큰 소비 후 해싱 중)에 경쟁 요청을 끼워 넣는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
class AuthRevocationIntegrationTest extends AbstractIntegrationTest {

    private static final String ORIGINAL = "original-password-1";
    private static final String REPLACEMENT = "replacement-password-2";
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 1_000_000);

    @Autowired private MockMvc mockMvc;
    @Autowired private LocalAuthService localAuthService;
    @Autowired private AuthService authService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JwtProperties jwtProperties;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthProviderRepository authProviderRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    @MockitoBean private AuthMailer authMailer;
    @MockitoSpyBean private AuthTokenStore authTokenStore;
    @MockitoSpyBean private PasswordEncoder passwordEncoder;

    private String email;
    private String nickname;

    @BeforeEach
    void setUp() {
        reset(authMailer, authTokenStore, passwordEncoder);
        long id = SEQ.incrementAndGet();
        email = "revoke" + id + "@example.invalid";
        nickname = "revoke" + id;
    }

    private String verificationLink() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(authMailer, timeout(2000).atLeastOnce()).sendVerification(eq(email), token.capture(), any());
        clearInvocations(authMailer);
        return token.getValue();
    }

    private String resetLink() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(authMailer, timeout(2000).atLeastOnce()).sendPasswordReset(eq(email), token.capture(), any());
        clearInvocations(authMailer);
        return token.getValue();
    }

    /** 가입과 메일 인증을 마친 계정. */
    private TokenResponse verifiedAccount() {
        localAuthService.signup(email, ORIGINAL, nickname);
        return localAuthService.verifyEmail(verificationLink());
    }

    private Long userId() {
        return authProviderRepository.findUserIdByProviderAndSocialId("LOCAL", email).orElseThrow();
    }

    private void assertAccepted(String accessToken) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    private void assertRejected(String accessToken) throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    private void assertFails(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    // ---- 재가입과 메일 인증 ----

    @Test
    @DisplayName("재가입의 DB 커밋과 Redis 폐기 사이에 들어온 옛 링크는 교체된 비밀번호를 활성화하지 못한다")
    void oldVerificationLinkCannotActivateReplacementPassword() {
        localAuthService.signup(email, ORIGINAL, nickname);
        String victimLink = verificationLink();

        AtomicBoolean interleaved = new AtomicBoolean();
        doAnswer(invocation -> {
            // signup이 DB 트랜잭션을 커밋한 뒤, 옛 토큰을 폐기하기 직전이다.
            if (interleaved.compareAndSet(false, true)) {
                assertFails(ErrorCode.INVALID_AUTH_TOKEN, () -> localAuthService.verifyEmail(victimLink));
            }
            return invocation.callRealMethod();
        }).when(authTokenStore).issue(eq(AuthTokenStore.Purpose.VERIFY_EMAIL), anyLong(), anyLong(), any(Duration.class));

        localAuthService.signup(email, REPLACEMENT, nickname);

        assertThat(interleaved).isTrue();
        assertFails(ErrorCode.EMAIL_NOT_VERIFIED, () -> localAuthService.login(email, REPLACEMENT));
    }

    @Test
    @DisplayName("재가입과 옛 링크 클릭이 실제로 겹쳐도 교체된 비밀번호로는 절대 로그인되지 않는다")
    void concurrentSignupAndVerificationNeverActivateReplacement() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 8; round++) {
                setUp();
                localAuthService.signup(email, ORIGINAL, nickname);
                String victimLink = verificationLink();

                CountDownLatch start = new CountDownLatch(1);
                String attemptEmail = email;
                String attemptNickname = nickname;
                Future<?> verify = pool.submit(awaiting(start, () -> {
                    try {
                        localAuthService.verifyEmail(victimLink);
                    } catch (BusinessException expectedWhenSignupWins) {
                        assertThat(expectedWhenSignupWins.getErrorCode()).isEqualTo(ErrorCode.INVALID_AUTH_TOKEN);
                    }
                    return null;
                }));
                Future<?> signup = pool.submit(awaiting(start, () -> {
                    localAuthService.signup(attemptEmail, REPLACEMENT, attemptNickname);
                    return null;
                }));
                start.countDown();
                verify.get();
                signup.get();

                // 인증이 먼저면 원래 비밀번호, 재가입이 먼저면 미인증. 어느 순서든 공격자 비밀번호는 통하지 않는다.
                assertThatThrownBy(() -> localAuthService.login(attemptEmail, REPLACEMENT))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isIn(ErrorCode.INVALID_CREDENTIALS, ErrorCode.EMAIL_NOT_VERIFIED);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Object> awaiting(CountDownLatch start, Callable<Object> work) {
        return () -> {
            start.await();
            return work.call();
        };
    }

    // ---- 비밀번호 재설정 링크 ----

    @Test
    @DisplayName("비밀번호를 바꾸면 이전 재설정 링크가 무효가 된다")
    void passwordChangeInvalidatesOutstandingResetLink() {
        verifiedAccount();
        localAuthService.requestPasswordReset(email);
        String link = resetLink();

        localAuthService.changePassword(userId(), ORIGINAL, REPLACEMENT);

        assertFails(ErrorCode.INVALID_AUTH_TOKEN, () -> localAuthService.confirmPasswordReset(link, "older-link-password"));
        assertThat(localAuthService.login(email, REPLACEMENT)).isNotNull();
    }

    @Test
    @DisplayName("Redis 정리가 실패해 링크가 남아도 버전이 달라 DB에서 거절된다")
    void staleResetLinkIsRejectedByDatabaseEvenIfRedisKeepsIt() {
        verifiedAccount();
        localAuthService.requestPasswordReset(email);
        String link = resetLink();

        doAnswer(invocation -> {
            throw new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
        }).when(authTokenStore).revoke(eq(AuthTokenStore.Purpose.PASSWORD_RESET), anyLong());

        localAuthService.changePassword(userId(), ORIGINAL, REPLACEMENT);

        assertFails(ErrorCode.INVALID_AUTH_TOKEN, () -> localAuthService.confirmPasswordReset(link, "older-link-password"));
        assertThat(localAuthService.login(email, REPLACEMENT)).isNotNull();
    }

    @Test
    @DisplayName("링크를 이미 소비한 요청이 해싱하는 사이 비밀번호가 바뀌면 그 요청은 덮어쓰지 못한다")
    void inFlightResetCannotOverwriteConcurrentPasswordChange() {
        verifiedAccount();
        Long userId = userId();
        localAuthService.requestPasswordReset(email);
        String link = resetLink();

        AtomicBoolean interleaved = new AtomicBoolean();
        doAnswer(invocation -> {
            if (interleaved.compareAndSet(false, true)) {
                localAuthService.changePassword(userId, ORIGINAL, REPLACEMENT);
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        assertFails(ErrorCode.INVALID_AUTH_TOKEN, () -> localAuthService.confirmPasswordReset(link, "older-link-password"));

        assertThat(interleaved).isTrue();
        assertThat(localAuthService.login(email, REPLACEMENT)).isNotNull();
    }

    @Test
    @DisplayName("같은 비밀번호 변경을 대조 뒤 앞지른 변경이 있으면 뒤의 변경은 거절된다")
    void concurrentPasswordChangeUsesVerifiedCredential() {
        verifiedAccount();
        Long userId = userId();

        AtomicBoolean interleaved = new AtomicBoolean();
        doAnswer(invocation -> {
            if (interleaved.compareAndSet(false, true)) {
                localAuthService.changePassword(userId, ORIGINAL, REPLACEMENT);
            }
            return invocation.callRealMethod();
        }).when(passwordEncoder).encode(any());

        assertFails(ErrorCode.INVALID_CREDENTIALS,
                () -> localAuthService.changePassword(userId, ORIGINAL, "stale-change-password"));

        assertThat(localAuthService.login(email, REPLACEMENT)).isNotNull();
    }

    // ---- 토큰 무효화 ----

    @Test
    @DisplayName("로그아웃하면 기존 Access·Refresh Token이 즉시 거부되고, 다시 로그인하면 동작한다")
    void logoutRevokesTokens() throws Exception {
        TokenResponse tokens = verifiedAccount();
        assertAccepted(tokens.accessToken());

        mockMvc.perform(delete("/api/v1/auth/logout").header("Authorization", "Bearer " + tokens.accessToken()))
                .andExpect(status().isOk());

        assertRejected(tokens.accessToken());
        assertFails(ErrorCode.UNAUTHORIZED, () -> authService.refresh(tokens.refreshToken()));
        assertAccepted(localAuthService.login(email, ORIGINAL).accessToken());
    }

    @Test
    @DisplayName("탈퇴하면 토큰이 거부되고, 유예기간 안에 로그인하면 새 토큰으로 복구된다")
    void withdrawRevokesTokens() throws Exception {
        TokenResponse tokens = verifiedAccount();

        authService.withdraw(userId());

        assertRejected(tokens.accessToken());
        assertFails(ErrorCode.UNAUTHORIZED, () -> authService.refresh(tokens.refreshToken()));
        assertAccepted(localAuthService.login(email, ORIGINAL).accessToken());
    }

    @Test
    @DisplayName("비밀번호를 바꾸면 옛 토큰은 거부되고 새로 받은 토큰은 동작한다")
    void passwordChangeRevokesOldTokens() throws Exception {
        TokenResponse before = verifiedAccount();

        TokenResponse after = localAuthService.changePassword(userId(), ORIGINAL, REPLACEMENT);

        assertRejected(before.accessToken());
        assertAccepted(after.accessToken());
        assertThat(authService.refresh(after.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("옛 비밀번호 대조 뒤 비밀번호가 바뀌면 그 로그인은 세션을 받지 못한다")
    void loginVerifiedAgainstOldPasswordGetsNoSession() {
        verifiedAccount();
        Long userId = userId();

        AtomicBoolean interleaved = new AtomicBoolean();
        doAnswer(invocation -> {
            Object matched = invocation.callRealMethod();
            // 옛 비밀번호 대조는 끝났고 로그인 트랜잭션은 아직 커밋 전이다.
            if (interleaved.compareAndSet(false, true)) {
                Thread other = new Thread(() ->
                        localAuthService.changePassword(userId, ORIGINAL, REPLACEMENT));
                other.start();
                other.join();
            }
            return matched;
        }).when(passwordEncoder).matches(anyString(), anyString());

        assertFails(ErrorCode.UNAUTHORIZED, () -> localAuthService.login(email, ORIGINAL));

        assertThat(interleaved).isTrue();
        assertThat(localAuthService.login(email, REPLACEMENT)).isNotNull();
    }

    @Test
    @DisplayName("재사용이 탐지되면 Refresh 체인과 함께 Access Token도 끊는다")
    void refreshReuseRevokesAccessToken() throws Exception {
        TokenResponse tokens = verifiedAccount();
        // 같은 버전이지만 저장소의 현재 값이 아닌 토큰 = 유예 창 밖의 옛 토큰.
        String stolen = jwtTokenProvider.createRefreshToken(userId(), 0L);

        assertFails(ErrorCode.UNAUTHORIZED, () -> authService.refresh(stolen));

        assertRejected(tokens.accessToken());
    }

    @Test
    @DisplayName("무효화된 옛 Refresh Token으로는 현재 세션을 끊지 못한다")
    void staleRefreshTokenCannotDisturbCurrentSession() throws Exception {
        TokenResponse before = verifiedAccount();
        TokenResponse after = localAuthService.changePassword(userId(), ORIGINAL, REPLACEMENT);

        assertFails(ErrorCode.UNAUTHORIZED, () -> authService.refresh(before.refreshToken()));
        authService.logout(null, before.refreshToken());

        assertAccepted(after.accessToken());
        assertThat(authService.refresh(after.refreshToken()).accessToken()).isNotBlank();
    }

    @Test
    @DisplayName("같은 초에 다시 로그인해도 Refresh Token 문자열은 매번 다르다")
    void refreshTokensAreUniqueWithinSameSecond() {
        TokenResponse first = verifiedAccount();

        assertThat(localAuthService.login(email, ORIGINAL).refreshToken()).isNotEqualTo(first.refreshToken());
    }

    @Test
    @DisplayName("ver 클레임이 없는 배포 전 토큰은 버전이 오르기 전까지만 통한다")
    void legacyTokenWorksUntilFirstRevocation() throws Exception {
        verifiedAccount();
        Long userId = userId();
        String legacy = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("token_type", "access")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertAccepted(legacy);

        authService.logout(userId, null);

        assertRejected(legacy);
    }

    @Test
    @DisplayName("버전을 올리는 동안 다른 트랜잭션이 닉네임만 고쳐도 버전이 되돌아가지 않는다")
    void unrelatedUpdateDoesNotRollBackVersion() {
        verifiedAccount();
        Long userId = userId();

        transactionTemplate.executeWithoutResult(status -> {
            var stale = userRepository.findById(userId).orElseThrow();
            // 다른 트랜잭션이 버전을 올리고 먼저 커밋한다. 전체 칸을 쓰는 UPDATE였다면 아래 저장이 되돌린다.
            Thread other = new Thread(() -> authService.logout(userId, null));
            other.start();
            try {
                other.join();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            stale.updateNickname(nickname + "x");
        });

        assertThat(userRepository.findById(userId).orElseThrow().getAuthVersion()).isEqualTo(1L);
    }
}
