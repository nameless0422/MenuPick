package com.nameless0422.MenuPick.integration;

import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code TraceIdFilter}가 실제 서블릿 필터 체인의 <b>최바깥</b>에 등록됐는지 검증한다.
 *
 * <p>단위 테스트({@code TraceIdFilterTest})는 필터를 직접 호출하므로 등록 순서를 증명하지 못하고,
 * {@code @WebMvcTest} 슬라이스는 {@code TraceIdFilterConfig}를 스캔조차 하지 않는다. 순서가 틀리면
 * 인증 실패(401)·레이트 리밋(429)처럼 <b>시큐리티 체인 안에서 종결되는</b> 응답에는 traceId가 붙지
 * 않는데, 하필 그 구간이 상관관계 추적이 가장 필요한 곳이다. 그래서 실제 포트를 열고 HTTP로
 * 왕복시켜 확인한다.
 *
 * <p>4xx에서 예외를 던지지 않고 응답 헤더를 그대로 볼 수 있도록 JDK {@link HttpClient}를 쓴다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration")
class TraceIdFilterOrderIntegrationTest extends AbstractIntegrationTest {

    private static final String TRACE_ID_HEADER = "X-Request-Id";

    @LocalServerPort private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    /** 보호된 경로. 토큰을 붙이지 않으므로 시큐리티 체인 안에서 401로 끝난다. */
    private HttpResponse<Void> callMenus(String inboundTraceId) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/v1/menus"))
                .GET();
        if (inboundTraceId != null) {
            builder.header(TRACE_ID_HEADER, inboundTraceId);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
    }

    private static String traceIdOf(HttpResponse<?> response) {
        return response.headers().firstValue(TRACE_ID_HEADER).orElse(null);
    }

    @Test
    @DisplayName("인증 없이 보호된 경로를 호출하면 401이지만 X-Request-Id는 붙는다 (필터가 시큐리티 체인 바깥)")
    void unauthenticatedRequest_stillCarriesTraceId() throws Exception {
        HttpResponse<Void> response = callMenus(null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(traceIdOf(response))
                .as("401은 시큐리티 체인 안에서 종결된다 — 헤더가 없다면 TraceIdFilter가 그보다 안쪽에 등록된 것")
                .isNotBlank();
    }

    @Test
    @DisplayName("유효한 X-Request-Id는 실제 체인을 왕복해도 그대로 돌아온다")
    void validInboundTraceId_isEchoedBack() throws Exception {
        assertThat(traceIdOf(callMenus("abc123DEF456_-xy"))).isEqualTo("abc123DEF456_-xy");
    }

    @Test
    @DisplayName("형식에 어긋난 X-Request-Id는 그대로 돌려주지 않는다 (로그 인젝션 방어)")
    void malformedInboundTraceId_isNotReflected() throws Exception {
        String echoed = traceIdOf(callMenus("abc def:ghi"));

        assertThat(echoed).isNotNull().isNotEqualTo("abc def:ghi");
        assertThat(echoed).matches("[A-Za-z0-9_-]{1,64}");
    }

    @Test
    @DisplayName("헤더를 안 보내면 요청마다 서로 다른 traceId가 발급된다")
    void traceId_isUniquePerRequest() throws Exception {
        String first = traceIdOf(callMenus(null));
        String second = traceIdOf(callMenus(null));

        assertThat(first).isNotBlank();
        assertThat(second).isNotBlank().isNotEqualTo(first);
    }
}
