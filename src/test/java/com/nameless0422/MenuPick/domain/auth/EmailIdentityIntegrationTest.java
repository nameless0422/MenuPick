package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 이메일 식별자는 accent 변형을 다른 주소로 다룬다(V22, D-074). 실제 콜레이션이 판정하므로 실 MySQL에서 돈다.
 */
@SpringBootTest
@ActiveProfiles("integration")
class EmailIdentityIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "identity-password-1";
    private static final String OTHER_PASSWORD = "identity-password-2";
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 1_000_000);

    @Autowired private LocalAuthService localAuthService;
    @Autowired private AuthService authService;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthProviderRepository authProviderRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private AuthMailer authMailer;
    @MockitoBean private GoogleOAuthProvider googleOAuthProvider;

    private String local;
    private String email;
    private String variant;

    @BeforeEach
    void setUp() {
        reset(authMailer, googleOAuthProvider);
        given(googleOAuthProvider.getProviderName()).willReturn("GOOGLE");
        local = "identity" + SEQ.incrementAndGet();
        email = local + "@example.invalid";
        variant = local + "@exámple.invalid";
    }

    private String verificationToken(String to) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(authMailer, timeout(2000).atLeastOnce()).sendVerification(eq(to), token.capture(), any());
        return token.getValue();
    }

    private Long userIdOf(TokenResponse tokens) {
        return jwtTokenProvider.getUserId(jwtTokenProvider.parseAccessToken(tokens.accessToken()).orElseThrow());
    }

    private void assertFails(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    private User saveVerifiedUser(String address, String nickname) {
        return userRepository.save(User.builder()
                .email(address)
                .nickname(nickname)
                .emailVerified(true)
                .build());
    }

    @Test
    @DisplayName("로그인 식별자 두 컬럼은 바이트 단위로 비교한다")
    void identifierColumnsAreBinary() {
        String sql = "SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?";

        assertThat(jdbcTemplate.queryForObject(sql, String.class, "users", "email"))
                .isEqualTo("utf8mb4_0900_bin");
        assertThat(jdbcTemplate.queryForObject(sql, String.class, "auth_providers", "social_id"))
                .isEqualTo("utf8mb4_0900_bin");
    }

    @Test
    @DisplayName("변형 주소로는 계정에 로그인할 수 없어 계정별 실패 제한을 우회하지 못한다")
    void variantCannotAuthenticate() {
        localAuthService.signup(email, PASSWORD, local);
        localAuthService.verifyEmail(verificationToken(email));

        assertFails(ErrorCode.INVALID_CREDENTIALS, () -> localAuthService.login(variant, PASSWORD));
    }

    @Test
    @DisplayName("대소문자가 다른 입력은 같은 계정으로 로그인한다")
    void uppercaseInputStillAuthenticates() {
        localAuthService.signup(email, PASSWORD, local);
        Long userId = userIdOf(localAuthService.verifyEmail(verificationToken(email)));

        assertThat(userIdOf(localAuthService.login(email.toUpperCase(), PASSWORD))).isEqualTo(userId);
    }

    @Test
    @DisplayName("변형 주소가 먼저 가입해도 실제 주소의 인증 메일은 실제 주소로 나간다")
    void variantCannotPreemptRealAddress() {
        localAuthService.signup(variant, OTHER_PASSWORD, local + "v");

        localAuthService.signup(email, PASSWORD, local);

        TokenResponse tokens = localAuthService.verifyEmail(verificationToken(email));
        assertThat(userRepository.findById(userIdOf(tokens)).orElseThrow().getEmail()).isEqualTo(email);
        assertThat(authProviderRepository.findUserIdByProviderAndSocialId("LOCAL", variant))
                .isPresent()
                .get()
                .isNotEqualTo(userIdOf(tokens));
    }

    @Test
    @DisplayName("변형 주소를 인증해도 실제 주소의 탈퇴 계정에 병합되지 않는다")
    void variantVerificationDoesNotMergeIntoOwner() {
        User owner = saveVerifiedUser(email, local);
        owner.softDelete(LocalDateTime.now());
        userRepository.saveAndFlush(owner);

        localAuthService.signup(variant, OTHER_PASSWORD, local + "v");
        Long verifiedUserId = userIdOf(localAuthService.verifyEmail(verificationToken(variant)));

        assertThat(verifiedUserId).isNotEqualTo(owner.getId());
        assertThat(userRepository.findById(owner.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test
    @DisplayName("소셜 로그인은 변형 주소의 계정에 자동 연동되지 않는다")
    void socialLoginDoesNotLinkToVariantOwner() {
        saveVerifiedUser(variant, local + "v");
        given(googleOAuthProvider.getUserProfile("code"))
                .willReturn(new OAuthUserProfile("google-" + local, email, local, true));

        assertFails(ErrorCode.SOCIAL_ACCOUNT_NOT_LINKED, () -> authService.socialLogin("GOOGLE", "code"));
    }

    @Test
    @DisplayName("소셜 제공자가 대문자를 섞어 줘도 같은 주소의 계정에 연동된다")
    void socialLoginCanonicalizesProviderEmail() {
        User owner = saveVerifiedUser(email, local);
        given(googleOAuthProvider.getUserProfile("code"))
                .willReturn(new OAuthUserProfile("google-" + local, email.toUpperCase(), local, true));

        assertThat(userIdOf(authService.socialLogin("GOOGLE", "code"))).isEqualTo(owner.getId());
    }
}
