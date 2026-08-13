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
            logUnsent(subject, body);
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

    /**
     * SMTP가 없어 발송을 건너뛴 사실을 남긴다.
     *
     * <p>본문은 {@code app.mail.log-links}가 켜져 있을 때만 남긴다. 본문에는 이메일 인증·
     * 비밀번호 재설정 토큰이 든 링크가 그대로 들어 있어서, 그대로 찍으면 로그 열람 권한이
     * 곧 계정 탈취 권한이 된다(재설정 링크는 30분 유효하고 한 번 쓰면 흔적도 남지 않는다).
     * 로컬에서 SMTP 계정 없이 흐름을 확인하려는 용도라 로컬 프로파일에서만 켠다.
     *
     * <p>수신자 주소는 어느 경우에도 남기지 않는다 — 개인정보다.
     */
    private void logUnsent(String subject, String body) {
        if (mailProperties.logLinks()) {
            log.warn("SMTP가 없어 발송하지 않고 본문을 남깁니다 (app.mail.log-links=true). "
                    + "제목='{}'\n{}", subject, body);
            return;
        }
        log.warn("SMTP가 설정되지 않아 메일을 발송하지 못했습니다 (spring.mail.host 미지정 또는 빈 값). "
                + "제목='{}'. 본문에 인증 토큰이 들어 있어 로그에 남기지 않습니다 — "
                + "로컬에서 링크를 확인하려면 app.mail.log-links=true로 켜세요.", subject);
    }

    public String baseUrl() {
        return mailProperties.baseUrl();
    }
}
