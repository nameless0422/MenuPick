package com.nameless0422.MenuPick.common.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Redis가 흔들릴 때 무엇을 로그에 남기는지 검증한다.
 *
 * <p>카카오 프록시의 캐시 키에는 사용자가 입력한 상호명과 100m 격자로 반올림한 실제 좌표가
 * 들어 있다. Redis 컨테이너가 30초만 재시작해도 그 사이의 모든 검색이 한 줄씩 쌓이므로,
 * "키 원문은 어떤 실패 경로에서도 남지 않는다"를 테스트로 고정한다.
 * {@code AccessLogFilter}가 쿼리 값을 빼고 이름만 남기는 것과 같은 계약이다.
 */
class RedisConfigCacheErrorHandlerTest {

    /** 실제 카카오 키워드 검색 캐시 키 형태 — 상호명과 좌표가 그대로 들어 있다. */
    private static final String KEY = "진주회관:FD6:126.985:37.561:500:1:15:accuracy";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;
    private RedisConfig.LoggingCacheErrorHandler handler;
    private Cache cache;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(RedisConfig.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);

        handler = new RedisConfig.LoggingCacheErrorHandler();
        cache = mock(Cache.class);
        given(cache.getName()).willReturn("kakaoKeywordSearch");
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("조회·저장·삭제 실패 로그 어디에도 캐시 키 원문이 남지 않는다")
    void neverLogsRawKey() {
        RuntimeException failure = new RuntimeException("connection refused");

        handler.handleCacheGetError(failure, cache, KEY);
        handler.handleCachePutError(failure, cache, KEY, "value");
        handler.handleCacheEvictError(failure, cache, KEY);

        List<String> messages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();

        assertThat(messages).hasSize(3);
        assertThat(messages).noneMatch(message -> message.contains(KEY));
        // 상호명·좌표가 조각으로도 새면 안 된다
        assertThat(messages).noneMatch(message -> message.contains("진주회관"));
        assertThat(messages).noneMatch(message -> message.contains("126.985"));
        assertThat(messages).noneMatch(message -> message.contains("37.561"));
        // 캐시 이름과 원인은 남아야 운영자가 무엇이 왜 실패했는지 안다
        assertThat(messages).allMatch(message -> message.contains("kakaoKeywordSearch"));
        assertThat(messages).allMatch(message -> message.contains("connection refused"));
    }

    @Test
    @DisplayName("같은 키는 같은 지문으로 묶이고, 다른 키는 갈라진다")
    void digestIsStableAndDistinct() {
        String digest = RedisConfig.LoggingCacheErrorHandler.keyDigest(KEY);

        assertThat(digest).isEqualTo(RedisConfig.LoggingCacheErrorHandler.keyDigest(KEY));
        assertThat(digest).isNotEqualTo(
                RedisConfig.LoggingCacheErrorHandler.keyDigest(KEY + ":other"));
        // 8자리 hex — 원문 길이를 짐작할 단서가 남지 않도록 항상 같은 길이다
        assertThat(digest).hasSize(8).matches("[0-9a-f]+");
    }

    @Test
    @DisplayName("키가 null이어도 로깅이 터지지 않는다")
    void nullKeyDoesNotThrow() {
        handler.handleCacheGetError(new RuntimeException("boom"), cache, null);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("keyHash=null");
    }
}
