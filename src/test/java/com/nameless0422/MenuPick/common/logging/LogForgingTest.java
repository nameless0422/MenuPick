package com.nameless0422.MenuPick.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.CoreConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 로그 위조(CWE-117)를 막는 두 겹을 검증한다.
 *
 * <h2>무엇을 막는가</h2>
 *
 * <p>로그 한 줄은 "한 사건"이라는 약속 위에서 읽힌다. 사용자 입력에 줄바꿈이 섞여 그대로
 * 찍히면 공격자가 <b>줄을 하나 더 만들 수 있다</b> — 시각·레벨·로거 이름까지 흉내 낸 가짜 줄을.
 * 이 서비스에서는 그게 화면에서 끝나지 않는다. {@code scripts/check-app-errors.sh}가 journal의
 * ERROR 줄을 세어 운영 알림 채널로 보내므로, 미인증 요청 한 번이 가짜 장애 알림이 된다.
 *
 * <p>그래서 (1) 레이아웃이 메시지의 제어 문자를 이스케이프하고({@link CrlfSafeMessageConverter}),
 * (2) 그 변환기가 실제 운영 구성({@code logback-spring.xml})에 걸려 있는지를 함께 본다.
 * 변환기만 맞고 구성에 안 걸려 있으면 테스트는 초록인데 운영은 그대로 뚫려 있다.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogForgingTest {

    private static final String FORGED =
            "정상 메시지\n2026-01-15T00:30:00.000Z ERROR [,] 1 --- [MenuPick] [main] c.n.M.Fake : 가짜 장애";

    @Test
    @DisplayName("변환기는 줄바꿈을 이스케이프해 한 줄을 한 줄로 유지한다")
    void converterEscapesLineBreaks() {
        assertThat(CrlfSafeMessageConverter.sanitize("a\nb")).isEqualTo("a\\nb");
        assertThat(CrlfSafeMessageConverter.sanitize("a\r\nb")).isEqualTo("a\\r\\nb");
        // 유니코드 줄 구분자도 뷰어에 따라 줄을 나눈다.
        assertThat(CrlfSafeMessageConverter.sanitize("a b c\u0085d"))
                .isEqualTo("a\\u2028b\\u2029c\\u0085d");
    }

    /**
     * ESC는 터미널 제어 시퀀스의 시작이다. 운영자가 {@code docker logs}를 터미널로 볼 때
     * 화면을 지우거나 앞줄을 덮어써 줄바꿈 없이도 로그를 조작할 수 있다.
     */
    @Test
    @DisplayName("ESC를 비롯한 제어 문자도 이스케이프한다")
    void converterEscapesControlCharacters() {
        assertThat(CrlfSafeMessageConverter.sanitize("a\u001b[2Jb")).isEqualTo("a\\u001b[2Jb");
        assertThat(CrlfSafeMessageConverter.sanitize("a\u0000b")).isEqualTo("a\\u0000b");
        assertThat(CrlfSafeMessageConverter.sanitize("a\u007fb")).isEqualTo("a\\u007fb");
    }

    @Test
    @DisplayName("탭과 평범한 문자(한글 포함)는 그대로 둔다")
    void converterKeepsOrdinaryText() {
        String plain = "GET /api/v1/menus 200 12ms params=[size]\t한글 메시지 — 그대로";
        // 같은 인스턴스를 돌려준다 — 대부분의 로그 줄은 고칠 것이 없고, 그때 복사하지 않는다.
        assertThat(CrlfSafeMessageConverter.sanitize(plain)).isSameAs(plain);
        assertThat(CrlfSafeMessageConverter.sanitize(null)).isNull();
    }

    /**
     * {@code %m}은 logback의 기본 변환어다. 같은 이름으로 등록한 규칙이 기본을 덮어쓰는지는
     * logback 구현에 달린 일이라, 이 프로젝트가 쓰는 버전에서 실제로 그런지 고정해 둔다.
     * 덮어쓰지 못하는 버전으로 올라가면 운영 구성이 조용히 무력해진다.
     */
    @Test
    @DisplayName("같은 이름으로 등록한 변환 규칙이 logback 기본 %m을 덮어쓴다")
    void registeredRuleOverridesBuiltInMessageConverter() {
        LoggerContext context = new LoggerContext();
        Map<String, Supplier<?>> rules = new HashMap<>();
        rules.put("m", CrlfSafeMessageConverter::new);
        context.putObject(CoreConstants.PATTERN_RULE_REGISTRY_FOR_SUPPLIERS, rules);

        PatternLayout layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern("%m%n");
        layout.start();

        Logger logger = context.getLogger("test");
        String rendered = layout.doLayout(
                new LoggingEvent("fqcn", logger, Level.INFO, FORGED, null, null));

        assertThat(rendered.strip().lines()).hasSize(1);
        assertThat(rendered).contains("\\n2026-01-15T00:30:00.000Z ERROR");
    }

    /**
     * 운영 구성이 실제로 그 변환기를 쓰는가. 이 테스트 JVM의 로깅은 Boot가
     * {@code logback-spring.xml}로 초기화하지 않았을 수 있으므로, 구성 파일의 선언을 직접 읽는다.
     */
    @Test
    @DisplayName("logback-spring.xml이 메시지 변환어 셋 모두에 변환기를 건다")
    void productionConfigWiresTheConverter() throws Exception {
        String xml;
        try (var in = getClass().getResourceAsStream("/logback-spring.xml")) {
            assertThat(in).as("logback-spring.xml이 클래스패스에 있어야 한다").isNotNull();
            xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        // %m, %msg, %message는 같은 변환기의 세 이름이다. 하나만 덮으면 누군가 패턴을
        // %msg로 바꾸는 순간 보호가 사라진다.
        for (String word : new String[]{"m", "msg", "message"}) {
            assertThat(xml).contains("conversionWord=\"" + word + "\"");
        }
        assertThat(xml).contains(CrlfSafeMessageConverter.class.getName());
        // Boot 기본 콘솔 구성을 그대로 쓴다 — 패턴을 복사해 두면 Boot가 바꿀 때 어긋난다.
        assertThat(xml).contains("org/springframework/boot/logging/logback/console-appender.xml");
    }

    /**
     * 끝에서 끝까지: 실제 로거로 찍어 콘솔에 나온 것을 본다. 변환기가 맞고 구성 파일에 선언도
     * 있는데 Boot가 그 파일을 안 읽거나 logback이 규칙을 무시하면, 위의 테스트는 전부 초록이고
     * 운영만 뚫려 있다 — 그 틈을 이 테스트가 막는다.
     */
    @Test
    @DisplayName("실제 로거로 찍어도 위조한 줄이 새 줄로 나오지 않는다")
    void forgedLineDoesNotStartANewLine(CapturedOutput output) {
        // 이 JVM의 로깅이 어떤 구성으로 떠 있는지는 앞서 돈 테스트에 달려 있다. 운영과 같은
        // 경로(Boot의 LoggingSystem이 logback-spring.xml을 찾아 적용)로 직접 초기화해
        // 실행 순서와 무관하게 만든다.
        LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());
        system.cleanUp();
        system.beforeInitialize();
        system.initialize(new LoggingInitializationContext(new MockEnvironment()), null, null);

        org.slf4j.Logger log = LoggerFactory.getLogger(LogForgingTest.class);
        String marker = "log-forging-marker-" + System.nanoTime();

        log.info("{} {}", marker, FORGED);

        assertThat(output.getOut().lines().filter(line -> line.contains(marker)))
                .as("표식이 든 줄이 정확히 하나여야 한다")
                .hasSize(1)
                .allSatisfy(line -> assertThat(line).contains("\\n2026-01-15T00:30:00.000Z ERROR"));
        assertThat(output.getOut().lines())
                .as("가짜 줄이 줄 머리에서 시작하면 안 된다")
                .noneMatch(line -> line.startsWith("2026-01-15T00:30:00.000Z ERROR"));
    }
}
