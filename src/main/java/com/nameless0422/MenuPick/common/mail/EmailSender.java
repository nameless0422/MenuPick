package com.nameless0422.MenuPick.common.mail;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * 평문 메일 발송기.
 *
 * <p>{@code spring.mail.host}가 설정된 환경에서만 Boot가 {@link JavaMailSender}를 만든다.
 * 그래서 빈을 직접 주입받지 않고 {@link ObjectProvider}로 받아, 없으면 본문을 로그로 떨어뜨린다.
 * 로컬 개발과 테스트에서 SMTP 자격증명 없이도 인증 링크를 눌러볼 수 있게 하기 위한 것이다.
 * 조건부 빈({@code @ConditionalOnMissingBean}) 두 개로 나누지 않은 이유는 그쪽이 선언 순서에
 * 의존해 깨지기 쉬운 반면, 이 방식은 순서와 무관하기 때문이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailSender {

    private final ObjectProvider<JavaMailSender> javaMailSender;
    private final MailProperties mailProperties;

    /**
     * 설정된 SMTP 호스트. 빈 문자열이면 미설정으로 본다.
     *
     * <p>{@code spring.mail.host: ${MAIL_HOST:}}처럼 기본값을 빈 문자열로 둔 프로파일에서는
     * 프로퍼티가 "존재"하므로 Boot의 조건부 자동설정이 통과해 호스트가 빈 JavaMailSender가 만들어진다.
     * 그대로 두면 로그 폴백 대신 매번 연결 실패(502)가 나므로 여기서 한 번 더 본다.
     */
    @Value("${spring.mail.host:}")
    private String configuredHost;

    public void send(String to, String subject, String body) {
        JavaMailSender sender = configuredHost.isBlank() ? null : javaMailSender.getIfAvailable();

        if (sender == null) {
            // 수신자 주소는 개인정보라 로그에 남기지 않는다. 링크가 담긴 본문만 남겨
            // 로컬에서 인증 흐름을 끝까지 확인할 수 있게 한다.
            log.warn("SMTP가 설정되지 않아 메일을 발송하지 않습니다 (spring.mail.host 미지정 또는 빈 값). "
                    + "제목='{}'\n{}", subject, body);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.from());
        message.setTo(to);
        message.setSubject(subject);
        message.setText(body);

        try {
            sender.send(message);
        } catch (MailException e) {
            // 원인(호스트·인증 실패 등)은 로그에만 남기고 클라이언트에는 일반 메시지를 준다.
            log.error("메일 발송 실패: subject={}, cause={}", subject, e.getMessage(), e);
            throw new BusinessException(ErrorCode.MAIL_SEND_FAILED);
        }
    }

    public String baseUrl() {
        return mailProperties.baseUrl();
    }
}
