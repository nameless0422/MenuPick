package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.mail.EmailSender;
import com.nameless0422.MenuPick.common.mail.MailAsyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;

/**
 * 인증 관련 메일의 본문과 링크를 만들고, 발송을 요청 스레드에서 떼어낸다.
 *
 * <p>발송 수단({@link EmailSender})과 분리해 둔 이유는, 문구·링크 형태가 바뀌는 빈도가
 * SMTP 설정이 바뀌는 빈도보다 훨씬 높기 때문이다.
 *
 * <p><b>비동기 경계가 여기인 이유</b>: {@link EmailSender}는 실패를 예외로 알리는 동기
 * 프리미티브로 두는 편이 테스트하기 쉽고, 언젠가 동기 발송이 필요한 곳이 생겨도 그대로 쓸 수 있다.
 * 반면 인증 흐름에서는 "메일이 안 나갔다고 가입 요청을 실패시킬 것인가"라는 판단이 필요한데,
 * 그 판단은 한 곳에 모여 있어야 흐름마다 갈리지 않는다.
 *
 * <p><b>실패를 삼키는 이유</b>: 발송이 풀 스레드에서 일어나므로 예외를 던져봐야 호출자에게
 * 닿지 않는다. 대신 로그로 남긴다. 사용자 쪽 복구 수단은 두 흐름 모두 이미 있다 —
 * 가입은 재발송 버튼, 비밀번호는 재설정 재요청. 여기서 중요한 것은 운영자가
 * "메일이 안 나가고 있다"를 로그에서 바로 알아볼 수 있는 것이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthMailer {

    private final EmailSender emailSender;

    @Async(MailAsyncConfig.EXECUTOR)
    public void sendVerification(String to, String token, Duration validFor) {
        String link = link("/verify-email", token);
        send("이메일 인증", to, "[메뉴픽] 이메일 인증을 완료해주세요", """
                메뉴픽 가입을 환영합니다.

                아래 링크를 눌러 이메일 인증을 완료하면 로그인할 수 있습니다.
                %s

                이 링크는 %d시간 동안 유효합니다.
                직접 가입하지 않으셨다면 이 메일을 무시하셔도 됩니다.
                """.formatted(link, validFor.toHours()));
    }

    /**
     * 이미 가입된 주소로 가입 시도가 들어왔을 때 그 주소로 보내는 안내.
     *
     * <p>가입 API가 신규/기존을 같은 응답으로 돌려주기 때문에 필요하다. 응답만 통일하고
     * 아무 메일도 보내지 않으면, 정말로 자기가 가입을 시도한 사람은 오지 않는 인증 메일을
     * 기다리다 막힌다. 여기서 "이미 계정이 있고 다음에 무엇을 하면 되는지"를 알려야
     * 응답 통일이 사용자를 버리지 않는다.
     *
     * <p>링크도 토큰도 넣지 않는다. 이 메일은 <b>가입을 시도한 사람</b>이 아니라
     * <b>주소의 주인</b>에게 가므로, 남이 시킨 요청 하나로 주인의 계정에 영향을 줄 수 있는
     * 것을 쥐여 주면 안 된다. 비밀번호 재설정은 주인이 직접 시작하게 둔다.
     */
    @Async(MailAsyncConfig.EXECUTOR)
    public void sendAlreadyRegistered(String to) {
        send("가입 안내", to, "[메뉴픽] 이미 가입된 계정입니다", """
                이 주소로 메뉴픽 가입 시도가 있었습니다.

                이미 가입된 계정이라 새로 만들지 않았습니다. 그대로 로그인하시면 됩니다.
                비밀번호가 기억나지 않으면 로그인 화면의 "비밀번호를 잊으셨나요?"로
                재설정할 수 있습니다.

                본인이 시도하지 않았다면 이 메일을 무시하셔도 됩니다.
                계정과 비밀번호는 그대로입니다.
                """);
    }

    /**
     * 로그인 수단(소셜 연동)이 추가·해제됐음을 계정 주인에게 알린다.
     *
     * <p>이 알림이 이 변경에 대한 <b>유일한 방어선</b>이다. Access Token을 탈취한 쪽이 자기
     * 소셜 계정을 붙여 두면 영구적인 문이 하나 생기는데, 세션을 끊는 것만으로는 그 문이 닫히지
     * 않는다(공격자는 자기 소셜로 다시 들어온다). 주인이 사실을 알아야 연동을 해제하고
     * 비밀번호를 바꿀 수 있다.
     *
     * <p>본인이 한 일이면 그냥 넘기면 되므로 링크는 넣지 않는다 — 대응 경로는 앱 안의
     * 설정 화면이고, 메일에 들어간 링크는 그 자체로 피싱 표적이 된다.
     */
    @Async(MailAsyncConfig.EXECUTOR)
    public void sendLoginMethodChanged(String to, String providerLabel, boolean linked) {
        String what = linked ? "연동됐습니다" : "해제됐습니다";
        send("로그인 수단 변경", to, "[메뉴픽] 로그인 수단이 변경됐습니다", """
                메뉴픽 계정의 %s 로그인 연동이 %s.

                본인이 하신 변경이라면 이 메일은 무시하셔도 됩니다.

                하지 않은 변경이라면 계정에 다른 사람이 접근했을 수 있습니다.
                지금 바로 로그인해 설정 > 소셜 계정 연동에서 모르는 연동을 해제하고,
                비밀번호를 바꿔주세요. 비밀번호를 바꾸면 다른 기기의 로그인이 모두 끊깁니다.
                """.formatted(providerLabel, what));
    }

    @Async(MailAsyncConfig.EXECUTOR)
    public void sendPasswordReset(String to, String token, Duration validFor) {
        String link = link("/reset-password", token);
        send("비밀번호 재설정", to, "[메뉴픽] 비밀번호 재설정 안내", """
                아래 링크에서 새 비밀번호를 설정할 수 있습니다.
                %s

                이 링크는 %d분 동안 유효하며, 한 번만 사용할 수 있습니다.
                본인이 요청하지 않았다면 이 메일을 무시하세요. 비밀번호는 그대로 유지됩니다.
                """.formatted(link, validFor.toMinutes()));
    }

    /**
     * 발송하고, 실패해도 호출자에게 예외를 올리지 않는다.
     *
     * @param flow 어떤 흐름의 메일인지. 실패 로그만으로 "가입이 막혔는지 재설정이 막혔는지"를
     *             구분할 수 있어야 대응 우선순위를 정할 수 있다
     */
    private void send(String flow, String to, String subject, String body) {
        try {
            emailSender.send(to, subject, body);
        } catch (RuntimeException e) {
            // 수신자 주소는 개인정보라 로그에 남기지 않는다 (EmailSender와 같은 기준).
            // 원인과 스택은 EmailSender가 이미 남겼으므로 여기서는 영향 범위만 적는다.
            log.error("{} 메일 발송 실패 — 해당 사용자는 재발송을 요청해야 흐름을 이어갈 수 있습니다.", flow);
        }
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
