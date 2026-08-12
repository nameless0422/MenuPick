package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.mail.EmailSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

/**
 * {@code @Async}는 프록시를 통해서만 동작하므로 빈을 직접 호출하는 이 테스트에서는 적용되지 않는다.
 * 여기서 확인하는 것은 "발송이 실패해도 호출자에게 예외가 올라가지 않는다"는 격리 자체이고,
 * 스레드를 실제로 갈아타는지는 {@code MailAsyncConfigTest}가 실행기 쪽에서 따로 본다.
 */
@ExtendWith(MockitoExtension.class)
class AuthMailerTest {

    private static final String EMAIL = "user@example.com";

    @Mock private EmailSender emailSender;
    @InjectMocks private AuthMailer authMailer;

    @BeforeEach
    void setUp() {
        given(emailSender.baseUrl()).willReturn("https://menupick.example.com");
    }

    @Test
    @DisplayName("인증 메일 본문에 프론트엔드 인증 경로와 토큰이 들어간다")
    void verificationLink() {
        authMailer.sendVerification(EMAIL, "tok-123", Duration.ofHours(24));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(EMAIL), any(), body.capture());

        assertThat(body.getValue())
                .contains("https://menupick.example.com/verify-email?token=tok-123")
                .contains("24시간");
    }

    @Test
    @DisplayName("재설정 메일 본문에 프론트엔드 재설정 경로와 토큰이 들어간다")
    void passwordResetLink() {
        authMailer.sendPasswordReset(EMAIL, "tok-456", Duration.ofMinutes(30));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq(EMAIL), any(), body.capture());

        assertThat(body.getValue())
                .contains("https://menupick.example.com/reset-password?token=tok-456")
                .contains("30분");
    }

    @Test
    @DisplayName("발송이 실패해도 예외를 올리지 않는다 — 풀 스레드에서 던져봐야 호출자에게 닿지 않는다")
    void swallowsSendFailure() {
        willThrow(new BusinessException(ErrorCode.MAIL_SEND_FAILED))
                .given(emailSender).send(any(), any(), any());

        assertThatCode(() -> authMailer.sendVerification(EMAIL, "tok", Duration.ofHours(24)))
                .doesNotThrowAnyException();
        assertThatCode(() -> authMailer.sendPasswordReset(EMAIL, "tok", Duration.ofMinutes(30)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SMTP 라이브러리가 예상 밖 예외를 던져도 호출자에게 새지 않는다")
    void swallowsUnexpectedFailure() {
        willThrow(new IllegalStateException("SMTP 커넥션 풀 손상"))
                .given(emailSender).send(any(), any(), any());

        assertThatCode(() -> authMailer.sendVerification(EMAIL, "tok", Duration.ofHours(24)))
                .doesNotThrowAnyException();
    }
}
