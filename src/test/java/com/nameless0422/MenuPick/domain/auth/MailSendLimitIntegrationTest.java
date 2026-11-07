package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** 수신 주소별 메일 한도를 실 Redis와 실제 인증 흐름으로 검증한다(D-076). */
@SpringBootTest
@ActiveProfiles("integration")
class MailSendLimitIntegrationTest extends AbstractIntegrationTest {

    private static final String PASSWORD = "mail-limit-password-1";
    private static final String NEW_PASSWORD = "mail-limit-password-2";
    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 1_000_000);

    @Autowired private LocalAuthService localAuthService;
    @Autowired private MailSendLimiter mailSendLimiter;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private UserRepository userRepository;
    @Autowired private AuthProviderRepository authProviderRepository;

    @MockitoBean private AuthMailer authMailer;

    private String local;
    private String email;

    @BeforeEach
    void setUp() {
        reset(authMailer);
        local = "maillimit" + SEQ.incrementAndGet();
        email = local + "@example.invalid";
    }

    private String lastVerificationToken(int expectedSends) {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(authMailer, timeout(2000).times(expectedSends))
                .sendVerification(eq(email), token.capture(), any());
        return token.getValue();
    }

    @Test
    @DisplayName("한도까지 보내고 그다음 건부터 거절하며 주소마다 따로 센다")
    void countsPerAddress() {
        for (int i = 0; i < MailSendLimiter.MAX_MAILS; i++) {
            assertThat(mailSendLimiter.isExhausted(email)).isFalse();
            assertThat(mailSendLimiter.countAndCheckExceeded(email)).isFalse();
        }

        assertThat(mailSendLimiter.isExhausted(email)).isTrue();
        assertThat(mailSendLimiter.countAndCheckExceeded(email)).isTrue();
        assertThat(mailSendLimiter.countAndCheckExceeded("other-" + email)).isFalse();
    }

    @Test
    @DisplayName("주소를 Redis 키에 그대로 남기지 않고 창이 끝나면 사라지게 둔다")
    void keyHidesAddressAndExpires() {
        mailSendLimiter.countAndCheckExceeded(email);

        var keys = redisTemplate.keys("rl:mail:*");
        assertThat(keys).isNotEmpty().noneMatch(key -> key.contains(local));
        assertThat(keys).allSatisfy(key -> assertThat(redisTemplate.getExpire(key)).isPositive());
    }

    @Test
    @DisplayName("재설정 요청이 한도를 넘으면 메일을 더 보내지 않고 이미 받은 링크를 유지한다")
    void resetOverLimitKeepsDeliveredLink() {
        localAuthService.signup(email, PASSWORD, local);
        localAuthService.verifyEmail(lastVerificationToken(1));
        int budget = MailSendLimiter.MAX_MAILS - 1;

        for (int i = 0; i < budget + 3; i++) {
            localAuthService.requestPasswordReset(email);
        }

        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        verify(authMailer, after(300).times(budget)).sendPasswordReset(eq(email), token.capture(), any());
        localAuthService.confirmPasswordReset(token.getValue(), NEW_PASSWORD);
        localAuthService.login(email, NEW_PASSWORD);
    }

    @Test
    @DisplayName("가입·재발송은 같은 예산을 쓰고 소진 뒤 재가입은 비밀번호와 받은 링크를 바꾸지 않는다")
    void signupOverLimitChangesNothing() {
        localAuthService.signup(email, PASSWORD, local);
        for (int i = 1; i < MailSendLimiter.MAX_MAILS; i++) {
            localAuthService.resendVerification(email);
        }
        String delivered = lastVerificationToken(MailSendLimiter.MAX_MAILS);

        localAuthService.signup(email, NEW_PASSWORD, local);
        localAuthService.resendVerification(email);

        verify(authMailer, after(300).times(MailSendLimiter.MAX_MAILS))
                .sendVerification(eq(email), any(), any());
        localAuthService.verifyEmail(delivered);
        localAuthService.login(email, PASSWORD);
    }

    @Test
    @DisplayName("이미 가입된 주소의 안내 메일도 같은 한도를 따른다")
    void alreadyRegisteredNoticeIsLimited() {
        localAuthService.signup(email, PASSWORD, local);
        localAuthService.verifyEmail(lastVerificationToken(1));

        for (int i = 0; i < MailSendLimiter.MAX_MAILS + 2; i++) {
            localAuthService.signup(email, NEW_PASSWORD, local + "x" + i);
        }

        verify(authMailer, after(300).times(MailSendLimiter.MAX_MAILS - 1)).sendAlreadyRegistered(email);
        assertThat(userRepository.existsByEmailAndDeletedAtIsNull(email)).isTrue();
        assertThat(authProviderRepository.findUserIdByProviderAndSocialId("LOCAL", email)).isPresent();
    }
}
