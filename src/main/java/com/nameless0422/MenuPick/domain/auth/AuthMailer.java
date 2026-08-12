package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.mail.EmailSender;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;

/**
 * 인증 관련 메일의 본문과 링크를 만든다.
 *
 * <p>발송 수단({@link EmailSender})과 분리해 둔 이유는, 문구·링크 형태가 바뀌는 빈도가
 * SMTP 설정이 바뀌는 빈도보다 훨씬 높기 때문이다.
 */
@Component
@RequiredArgsConstructor
public class AuthMailer {

    private final EmailSender emailSender;

    public void sendVerification(String to, String token, Duration validFor) {
        String link = link("/verify-email", token);
        emailSender.send(to, "[메뉴픽] 이메일 인증을 완료해주세요", """
                메뉴픽 가입을 환영합니다.

                아래 링크를 눌러 이메일 인증을 완료하면 로그인할 수 있습니다.
                %s

                이 링크는 %d시간 동안 유효합니다.
                직접 가입하지 않으셨다면 이 메일을 무시하셔도 됩니다.
                """.formatted(link, validFor.toHours()));
    }

    public void sendPasswordReset(String to, String token, Duration validFor) {
        String link = link("/reset-password", token);
        emailSender.send(to, "[메뉴픽] 비밀번호 재설정 안내", """
                아래 링크에서 새 비밀번호를 설정할 수 있습니다.
                %s

                이 링크는 %d분 동안 유효하며, 한 번만 사용할 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시하세요. 비밀번호는 그대로 유지됩니다.
                """.formatted(link, validFor.toMinutes()));
    }

    /**
     * 토큰을 쿼리 파라미터로 붙인 프론트엔드 링크.
     *
     * <p>토큰에는 URL-safe Base64 문자만 들어가지만, 인코딩을 생략하면 나중에 토큰 형식이
     * 바뀌었을 때 조용히 깨지므로 빌더에 맡긴다.
     */
    private String link(String path, String token) {
        return UriComponentsBuilder.fromUriString(emailSender.baseUrl())
                .path(path)
                .queryParam("token", token)
                .build()
                .encode()
                .toUriString();
    }
}
