package com.nameless0422.MenuPick.common.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 메일 본문·발신자 설정. SMTP 접속 정보 자체는 Spring의 {@code spring.mail.*}이 담당하고,
 * 여기에는 그쪽에 없는 값만 둔다.
 *
 * <p>이름이 Spring의 {@code org.springframework.boot.autoconfigure.mail.MailProperties}와 겹치지만
 * 접두사({@code app.mail})가 다르므로 바인딩이 충돌하지 않는다.
 *
 * @param from     발신자 주소. SMTP 제공자가 인증한 도메인이어야 스팸 처리되지 않는다
 * @param baseUrl  메일에 넣을 링크의 프론트엔드 원본(예: {@code https://menupick.app}).
 *                 서버가 아니라 사용자가 브라우저로 여는 주소라 요청 Host로 유추하면 안 된다 —
 *                 Host 헤더는 위조 가능해서 인증 링크를 공격자 도메인으로 돌릴 수 있다.
 * @param logLinks SMTP가 없을 때 메일 본문을 로그에 남길지. <b>기본값 false를 유지할 것.</b>
 *                 본문에는 이메일 인증·비밀번호 재설정 토큰이 든 링크가 그대로 들어 있어서,
 *                 켜 두면 로그 열람 권한이 곧 계정 탈취 권한이 된다. 로그는 대체로 DB보다
 *                 접근 권한이 넓게 열려 있어 위험이 더 크다.
 *                 개발자가 SMTP 계정 없이 가입 흐름을 끝까지 확인하려는 로컬에서만 켠다.
 */
@ConfigurationProperties(prefix = "app.mail")
public record MailProperties(
        @DefaultValue("no-reply@menupick.local") String from,
        @DefaultValue("http://localhost:5173") String baseUrl,
        @DefaultValue("false") boolean logLinks
) {}
