package com.nameless0422.MenuPick.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static com.nameless0422.MenuPick.common.logging.TraceIdFilter.TRACE_ID_HEADER;
import static com.nameless0422.MenuPick.common.logging.TraceIdFilter.TRACE_ID_MDC_KEY;
import static com.nameless0422.MenuPick.common.logging.TraceIdFilter.USER_ID_MDC_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceIdFilterTest {

    private TraceIdFilter filter;

    /** 체인 안쪽(= 실제 로깅이 일어나는 지점)에서 관측된 MDC traceId. */
    private AtomicReference<String> observedTraceId;

    private FilterChain capturingChain;

    @BeforeEach
    void setUp() {
        filter = new TraceIdFilter();
        observedTraceId = new AtomicReference<>();
        capturingChain = (req, res) -> observedTraceId.set(MDC.get(TRACE_ID_MDC_KEY));
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("X-Request-Id가 없으면 traceId를 새로 생성해 MDC와 응답 헤더에 넣는다")
    void noInboundHeader_generatesTraceId() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/menus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, capturingChain);

        assertThat(observedTraceId.get()).isNotBlank();
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(observedTraceId.get());
    }

    @Test
    @DisplayName("생성된 traceId는 화이트리스트를 만족한다 (로그 인젝션 여지 없음)")
    void generatedTraceId_matchesWhitelist() throws ServletException, IOException {
        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), capturingChain);

        assertThat(observedTraceId.get()).matches("[A-Za-z0-9_-]{1,64}");
    }

    @Test
    @DisplayName("요청마다 서로 다른 traceId가 생성된다")
    void generatesDistinctTraceIdPerRequest() throws ServletException, IOException {
        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), capturingChain);
        String first = observedTraceId.get();

        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), capturingChain);

        assertThat(observedTraceId.get()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("유효한 X-Request-Id가 오면 그대로 이어받는다")
    void validInboundHeader_isPropagated() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, "edge-proxy_9f3a2b");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, capturingChain);

        assertThat(observedTraceId.get()).isEqualTo("edge-proxy_9f3a2b");
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo("edge-proxy_9f3a2b");
    }

    @Test
    @DisplayName("64자 경계값은 유효한 값으로 이어받는다")
    void maxLengthInboundHeader_isPropagated() throws ServletException, IOException {
        String boundary = "a".repeat(64);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, boundary);

        filter.doFilterInternal(request, new MockHttpServletResponse(), capturingChain);

        assertThat(observedTraceId.get()).isEqualTo(boundary);
    }

    // --- 로그 인젝션 방어: 부정한 인바운드 헤더는 조용히 폐기하고 새로 생성한다 ---

    @ParameterizedTest(name = "부정한 헤더 [{0}]")
    @ValueSource(strings = {
            "abc\ndef",                      // 개행 — 가짜 로그 줄 주입
            "abc\r\nINFO fake log line",     // CRLF + 그럴듯한 로그 줄
            "abc\tdef",                      // 탭
            "trace id with spaces",
            "trace:id;with|meta",
            ""                               // 빈 값
    })
    @DisplayName("부정한 X-Request-Id는 거부하고 안전한 값을 새로 생성한다")
    void maliciousInboundHeader_isRejectedAndRegenerated(String malicious) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, malicious);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, capturingChain);

        assertThat(observedTraceId.get())
                .isNotEqualTo(malicious)
                .matches("[A-Za-z0-9_-]{1,64}");
        // 응답 헤더로도 부정한 값이 되돌아 나가면 안 된다 (헤더 스플리팅 방지)
        assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(observedTraceId.get());
    }

    @Test
    @DisplayName("64자를 넘는 X-Request-Id는 거부하고 새로 생성한다")
    void overlongInboundHeader_isRejectedAndRegenerated() throws ServletException, IOException {
        String overlong = "a".repeat(65);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(TRACE_ID_HEADER, overlong);

        filter.doFilterInternal(request, new MockHttpServletResponse(), capturingChain);

        assertThat(observedTraceId.get())
                .isNotEqualTo(overlong)
                .matches("[A-Za-z0-9_-]{1,64}");
    }

    @Test
    @DisplayName("비출력 제어문자(NUL/ESC)가 섞인 X-Request-Id는 거부하고 새로 생성한다")
    void controlCharacterInboundHeader_isRejectedAndRegenerated() throws ServletException, IOException {
        // 값 자체를 테스트 이름에 넣지 않는다 — NUL/ESC는 JUnit XML 리포트에 실을 수 없는 코드포인트다
        for (String malicious : java.util.List.of("abc\0def", "abc\033[31mdef")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(TRACE_ID_HEADER, malicious);
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilterInternal(request, response, capturingChain);

            assertThat(observedTraceId.get())
                    .isNotEqualTo(malicious)
                    .matches("[A-Za-z0-9_-]{1,64}");
            assertThat(response.getHeader(TRACE_ID_HEADER)).isEqualTo(observedTraceId.get());
        }
    }

    // --- MDC 누수 회귀 방지 (서블릿 스레드 재사용) ---

    @Test
    @DisplayName("필터를 통과하고 나면 MDC에 traceId가 남지 않는다")
    void afterFilter_mdcIsClean() throws ServletException, IOException {
        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), capturingChain);

        assertThat(MDC.get(TRACE_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("체인 안에서 채워진 userId도 요청 종료 시 함께 정리된다")
    void afterFilter_userIdIsAlsoCleared() throws ServletException, IOException {
        FilterChain chainSettingUserId = (req, res) -> MDC.put(USER_ID_MDC_KEY, "42");

        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), chainSettingUserId);

        assertThat(MDC.get(USER_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("체인에서 예외가 터져도 MDC는 정리된다 - 다음 요청에 남의 traceId가 붙으면 안 된다")
    void chainThrows_mdcIsStillCleaned() {
        FilterChain throwingChain = (req, res) -> {
            throw new ServletException("downstream boom");
        };

        assertThatThrownBy(() ->
                filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), throwingChain))
                .isInstanceOf(ServletException.class);

        assertThat(MDC.get(TRACE_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(USER_ID_MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("이전 요청이 남긴 traceId가 다음 요청으로 새지 않는다")
    void consecutiveRequests_doNotLeakTraceId() throws ServletException, IOException {
        MockHttpServletRequest first = new MockHttpServletRequest();
        first.addHeader(TRACE_ID_HEADER, "first-request");
        filter.doFilterInternal(first, new MockHttpServletResponse(), capturingChain);

        // 같은 스레드로 들어온 두 번째 요청 (서블릿 컨테이너의 스레드 재사용 상황)
        filter.doFilterInternal(new MockHttpServletRequest(), new MockHttpServletResponse(), capturingChain);

        assertThat(observedTraceId.get()).isNotEqualTo("first-request");
        assertThat(MDC.get(TRACE_ID_MDC_KEY)).isNull();
    }
}
