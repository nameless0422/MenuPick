package com.nameless0422.MenuPick.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nameless0422.MenuPick.common.logging.AccessLogFilter;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code AccessLogFilter}가 실제 필터 체인에서 올바른 자리에 등록됐는지 검증한다.
 *
 * <p>단위 테스트는 필터를 직접 호출하므로 등록 순서를 증명하지 못한다. 순서가 시큐리티 체인
 * 안쪽으로 밀리면 인증 실패(401)·레이트 리밋(429)이 접근 로그에서 통째로 빠지는데, 하필
 * "왜 못 들어갔나"를 따질 때 가장 필요한 줄이 그것이다. 그래서 실제 포트를 열고 왕복시킨다.
 *
 * <p>{@code TraceIdFilterOrderIntegrationTest}가 traceId 쪽 순서를 보는 것과 같은 이유의 테스트다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class AccessLogOrderIntegrationTest extends AbstractIntegrationTest {

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private void call(String path) throws Exception {
        HttpResponse<Void> response = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        // 응답을 받은 시점에 필터의 finally는 이미 지났다.
        assertThat(response.statusCode()).isNotZero();
    }

    private List<String> loggedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("인증 없이 보호된 경로를 부르면 401이 접근 로그에 남는다 (시큐리티 체인 바깥에 등록됨)")
    void unauthenticatedRequestIsLogged() throws Exception {
        call("/api/v1/menus");

        assertThat(loggedLines())
                .as("401이 안 남으면 AccessLogFilter가 시큐리티 체인보다 안쪽에 등록된 것")
                .anySatisfy(line -> assertThat(line).contains("GET", "/api/v1/menus", "401"));
    }

    @Test
    @DisplayName("접근 로그에 traceId가 함께 찍힌다 (TraceIdFilter 안쪽에 등록됨)")
    void accessLogCarriesTraceId() throws Exception {
        call("/api/v1/menus");

        assertThat(appender.list).isNotEmpty();
        assertThat(appender.list.get(0).getMDCPropertyMap())
                .as("traceId가 비어 있으면 AccessLogFilter가 TraceIdFilter보다 바깥에 등록된 것")
                .containsKey("traceId");
        assertThat(appender.list.get(0).getMDCPropertyMap().get("traceId")).isNotBlank();
    }

    @Test
    @DisplayName("헬스체크는 실제 체인에서도 접근 로그에 남지 않는다")
    void healthCheckIsNotLogged() throws Exception {
        call("/actuator/health");

        assertThat(loggedLines()).isEmpty();
    }
}
