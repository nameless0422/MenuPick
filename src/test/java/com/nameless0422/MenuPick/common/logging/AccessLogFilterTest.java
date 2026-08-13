package com.nameless0422.MenuPick.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter();
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

    private MockHttpServletRequest get(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }

    private ILoggingEvent onlyEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }

    @Test
    @DisplayName("메서드·경로·상태·처리 시간을 한 줄로 남긴다")
    void logsRequestLine() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        filter.doFilter(get("/api/v1/menus"), response, new MockFilterChain());

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage())
                .contains("GET", "/api/v1/menus", "200")
                .containsPattern("\\d+ms");
    }

    @Test
    @DisplayName("쿼리 값은 남기지 않고 파라미터 이름만 남긴다 — 검색어가 로그에 쌓이면 안 된다")
    void doesNotLogQueryValues() throws Exception {
        MockHttpServletRequest request = get("/api/v1/kakao/search");
        request.setParameter("query", "역삼동 김치찌개");
        request.setParameter("size", "20");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        String logged = onlyEvent().getFormattedMessage();
        assertThat(logged).doesNotContain("역삼동", "김치찌개");
        assertThat(logged).contains("params=[query, size]");
    }

    @Test
    @DisplayName("헬스체크는 남기지 않는다 — 30초마다 들어와 사람이 만든 요청을 묻는다")
    void skipsHealthCheck() throws Exception {
        filter.doFilter(get("/actuator/health"), new MockHttpServletResponse(), new MockFilterChain());
        assertThat(appender.list).isEmpty();
    }

    @Test
    @DisplayName("5xx는 WARN으로 올린다")
    void warnsOnServerError() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(500);

        filter.doFilter(get("/api/v1/menus"), response, new MockFilterChain());

        assertThat(onlyEvent().getLevel()).isEqualTo(Level.WARN);
    }

    @Test
    @DisplayName("인증 실패(401)도 기록에 남는다 — 시큐리티 체인 안에서 끝나는 요청이다")
    void logsUnauthorized() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        filter.doFilter(get("/api/v1/menus"), response, new MockFilterChain());

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).contains("401");
    }

    @Test
    @DisplayName("느린 요청은 WARN으로 올린다")
    void warnsOnSlowRequest() throws Exception {
        FilterChain slowChain = (req, res) -> {
            try {
                Thread.sleep(1_050);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        filter.doFilter(get("/api/v1/pick"), new MockHttpServletResponse(), slowChain);

        ILoggingEvent event = onlyEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).contains("느린 요청");
    }

    @Test
    @DisplayName("예외가 위로 튀어도 한 줄은 남긴다 — 실패한 요청일수록 기록이 필요하다")
    void logsEvenWhenChainThrows() {
        FilterChain failingChain = (req, res) -> {
            throw new ServletException("체인 실패");
        };

        assertThatThrownBy(() ->
                filter.doFilter(get("/api/v1/menus"), new MockHttpServletResponse(), failingChain))
                .isInstanceOf(ServletException.class);

        assertThat(appender.list).hasSize(1);
        assertThat(onlyEvent().getFormattedMessage()).contains("/api/v1/menus");
    }

    @Test
    @DisplayName("요청마다 한 줄만 남긴다 (OncePerRequestFilter)")
    void logsOncePerRequest() throws IOException, ServletException {
        MockHttpServletRequest request = get("/api/v1/menus");
        MockHttpServletResponse response = new MockHttpServletResponse();

        // 필터가 두 번 걸리는 상황(포워드 등)을 흉내낸다.
        filter.doFilter(request, response, (req, res) -> filter.doFilter(req, res, new MockFilterChain()));

        assertThat(appender.list).hasSize(1);
    }
}
