package com.nameless0422.MenuPick.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Set;

/**
 * 로그 메시지의 줄바꿈·제어 문자를 이스케이프하는 {@code %m} 변환기 — 로그 위조(CWE-117) 방어.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>로그 한 줄은 "한 사건"이라는 약속 위에서 읽힌다. 그런데 이 서비스의 로그에는 사용자가
 * 고른 문자열이 여러 군데서 들어간다 — 접근 로그의 파라미터 이름({@code AccessLogFilter}),
 * 타입이 안 맞는 요청 값과 파싱 실패 메시지({@code GlobalExceptionHandler}). 전부 URL 디코딩을
 * 거친 값이라 {@code %0a}는 진짜 줄바꿈이 되어 도착한다. 그대로 찍으면 공격자가 시각·레벨·로거
 * 이름까지 흉내 낸 <b>가짜 줄을 하나 더 만들 수 있다.</b>
 *
 * <p>2026-11-13 점검에서 이것이 화면 밖으로 이어진다는 것을 확인했다. 컨테이너 로그는 journal로
 * 가고({@code docker-compose.prod.yml}), {@code scripts/check-app-errors.sh}가 거기서 ERROR 줄을
 * 세어 운영 알림 채널로 보낸다. 즉 <b>미인증 요청 한 번으로 가짜 장애 알림을 띄우고 그 본문을
 * 고를 수 있었다.</b> 장애 대응에서 가장 비싼 것은 믿을 수 없는 알림이다.
 *
 * <h2>왜 호출부가 아니라 여기서 막는가</h2>
 *
 * <p>호출부마다 값을 씻는 방식은 <b>새 로그 문장이 추가될 때마다 다시 뚫린다.</b> 누군가
 * {@code log.warn("...: {}", userInput)}을 한 줄 더 쓰는 순간이 그때다. 레이아웃에서 막으면
 * 지금 있는 문장과 앞으로 생길 문장이 같은 보호를 받는다. 호출부의 정리({@code AccessLogFilter}의
 * 길이·개수 상한)는 이것과 별개로 남긴다 — 그쪽은 위조가 아니라 로그 폭주를 막는다.
 *
 * <h2>지우지 않고 이스케이프한다</h2>
 *
 * <p>{@code \n}을 지우거나 {@code _}로 바꾸면 "누가 줄바꿈을 넣으려 했다"는 사실도 함께 사라진다.
 * 그건 조사할 때 필요한 정보다. 눈에 보이는 {@code \\n}으로 남긴다.
 *
 * <h2>예외</h2>
 *
 * <p>스택 트레이스는 이 변환기를 지나지 않는다({@code %wEx}가 따로 그린다) — 여러 줄인 것이
 * 정상이고, 줄 머리가 시각으로 시작하지 않아 로그 줄로 오인되지 않는다. 그리고 Spring Boot가
 * 기동 실패를 설명하는 보고서는 <b>여러 줄이어야 읽을 수 있고 요청 데이터가 들어가지 않으므로</b>
 * 그대로 둔다({@link #MULTILINE_BY_DESIGN}). 한 줄로 뭉개면 배포가 죽었을 때 원인을 읽을 수 없다.
 */
public class CrlfSafeMessageConverter extends MessageConverter {

    /**
     * 여러 줄이 곧 내용인 로거. <b>요청 처리 경로에서 호출되지 않는 것만</b> 넣는다 — 여기 들어간
     * 로거는 보호를 받지 못하므로, 사용자 입력이 닿을 수 있는 로거를 넣으면 그 자리가 구멍이 된다.
     */
    static final Set<String> MULTILINE_BY_DESIGN = Set.of(
            // "APPLICATION FAILED TO START" 보고서. 기동이 실패했을 때만 찍힌다.
            "org.springframework.boot.diagnostics.LoggingFailureAnalysisReporter",
            // --debug로 켰을 때의 자동 구성 조건 평가 보고서. 기동 시점에만 찍힌다.
            "org.springframework.boot.autoconfigure.logging.ConditionEvaluationReportLogger");

    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (MULTILINE_BY_DESIGN.contains(event.getLoggerName())) {
            return message;
        }
        return sanitize(message);
    }

    /**
     * 줄을 나누거나 터미널을 조작할 수 있는 문자를 눈에 보이는 이스케이프로 바꾼다.
     *
     * <p>고칠 것이 없으면 <b>받은 문자열을 그대로 돌려준다.</b> 로그 줄의 대부분이 그 경우이고,
     * 모든 로그 문장이 지나가는 자리라 복사 한 번이 곧 전체 비용이 된다.
     */
    static String sanitize(String message) {
        if (message == null) {
            return null;
        }
        int first = firstUnsafe(message);
        if (first < 0) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length() + 16);
        out.append(message, 0, first);
        for (int i = first; i < message.length(); i++) {
            char c = message.charAt(i);
            if (c == '\n') {
                out.append("\\n");
            } else if (c == '\r') {
                out.append("\\r");
            } else if (isUnsafe(c)) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static int firstUnsafe(String message) {
        for (int i = 0; i < message.length(); i++) {
            if (isUnsafe(message.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 탭을 뺀 C0 제어 문자와 DEL, C1 제어 문자(NEL 포함), 유니코드 줄·문단 구분자.
     *
     * <p>줄바꿈만 막으면 부족하다. ESC(0x1b)는 터미널 제어 시퀀스의 시작이라 운영자가
     * {@code docker logs}를 터미널로 볼 때 화면을 지우거나 앞줄을 덮어쓸 수 있고, U+2028·U+2029·
     * U+0085는 뷰어에 따라 줄을 나눈다.
     */
    private static boolean isUnsafe(char c) {
        if (c == '\t') {
            return false;
        }
        return c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == ' ' || c == ' ';
    }
}
