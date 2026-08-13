package com.nameless0422.MenuPick.common.mail;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * SMTP가 없을 때 무엇을 로그에 남기는지 검증한다.
 *
 * <p>본문에는 이메일 인증·비밀번호 재설정 토큰이 든 링크가 그대로 들어 있다. 이걸 기본으로
 * 찍으면 로그 열람 권한이 곧 계정 탈취 권한이 되므로, "기본값에서는 절대 안 남는다"를
 * 테스트로 고정해 둔다. 로컬 편의를 위한 플래그가 실수로 기본값이 되는 것을 막는 장치다.
 */
class EmailSenderTest {

    private static final String BODY = """
            아래 링크를 눌러 이메일 인증을 완료하면 로그인할 수 있습니다.
            https://menupick.example.com/verify-email?token=SECRET-TOKEN-VALUE
            """;

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(EmailSender.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    /** SMTP 미설정 상태를 만든다 — host가 비어 있으면 EmailSender가 발송을 건너뛴다. */
    @SuppressWarnings("unchecked")
    private EmailSender senderWithoutSmtp(boolean logLinks) {
        ObjectProvider<JavaMailSender> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(null);

        EmailSender sender = new EmailSender(
                provider,
                new MailProperties("no-reply@menupick.local", "https://menupick.example.com", logLinks));
        ReflectionTestUtils.setField(sender, "configuredHost", "");
        return sender;
    }

    private String loggedText() {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).as("발송을 건너뛰었으면 흔적은 남아야 한다").isNotEmpty();
        return events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
    }

    @Test
    @DisplayName("기본값에서는 본문과 토큰이 로그에 남지 않는다")
    void doesNotLogTokenByDefault() {
        senderWithoutSmtp(false).send("user@example.com", "[메뉴픽] 이메일 인증", BODY);

        String logged = loggedText();
        assertThat(logged).doesNotContain("SECRET-TOKEN-VALUE");
        assertThat(logged).doesNotContain("verify-email?token=");
        // 발송이 안 됐다는 사실 자체는 운영자가 알아야 하므로 남아야 한다.
        assertThat(logged).contains("발송하지 못했습니다");
    }

    @Test
    @DisplayName("수신자 주소는 어느 경우에도 로그에 남지 않는다")
    void neverLogsRecipient() {
        senderWithoutSmtp(true).send("user@example.com", "[메뉴픽] 이메일 인증", BODY);
        assertThat(loggedText()).doesNotContain("user@example.com");
    }

    @Test
    @DisplayName("log-links를 켜면 본문을 남긴다 — 로컬에서 인증 링크를 눌러보기 위한 것")
    void logsBodyWhenExplicitlyEnabled() {
        senderWithoutSmtp(true).send("user@example.com", "[메뉴픽] 이메일 인증", BODY);
        assertThat(loggedText()).contains("SECRET-TOKEN-VALUE");
    }

    /**
     * 위 테스트들은 플래그가 주어졌을 때의 동작을 볼 뿐, 아무것도 설정하지 않았을 때 무엇이
     * 되는지는 보지 않는다. 정작 위험한 것은 그쪽이다 — 설정을 빠뜨린 환경(스테이징 등)에서
     * 토큰이 새는 것이므로, 바인딩 기본값 자체를 고정한다.
     */
    @Test
    @DisplayName("설정을 하나도 주지 않으면 log-links는 false로 바인딩된다")
    void defaultsToDisabled() {
        // 프로퍼티가 하나도 없으면 bind()는 아무것도 돌려주지 않는다. bindOrCreate가
        // @DefaultValue만으로 인스턴스를 만들어 주므로 "설정을 안 준 환경"과 같은 상태가 된다.
        MailProperties bound = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("app.mail", MailProperties.class);

        assertThat(bound.logLinks())
                .as("기본값이 true가 되면 설정을 빠뜨린 환경의 로그로 계정이 털린다")
                .isFalse();
    }
}
