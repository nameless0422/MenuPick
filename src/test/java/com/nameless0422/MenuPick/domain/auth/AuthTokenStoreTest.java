package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/** Redis 밖에서 정해지는 동작만 본다. 스크립트 판정은 {@link AuthTokenStoreRedisIntegrationTest}가 본다. */
@ExtendWith(MockitoExtension.class)
class AuthTokenStoreTest {

    @Mock private StringRedisTemplate redisTemplate;

    private AuthTokenStore store;

    @BeforeEach
    void setUp() {
        store = new AuthTokenStore(redisTemplate);
    }

    @Test
    @DisplayName("빈 토큰은 Redis를 건드리지 않고 바로 실패한다")
    void blankTokenIsRejected() {
        assertThat(store.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, "  ")).isEmpty();
        assertThat(store.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, null)).isEmpty();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    @DisplayName("스크립트가 돌려준 값을 주인과 자격증명 버전으로 해석한다")
    void consumeParsesBinding() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).willReturn("7:3");

        assertThat(store.consume(AuthTokenStore.Purpose.PASSWORD_RESET, "token"))
                .contains(new AuthTokenStore.Consumed(7L, 3L));
    }

    @Test
    @DisplayName("Redis 장애는 503으로 바꾼다")
    void redisFailureBecomesServiceUnavailable() {
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .willThrow(new QueryTimeoutException("down"));

        assertThatThrownBy(() -> store.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 1L, 0L, Duration.ofMinutes(1)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("키에는 토큰 원문을 넣지 않는다")
    void keysDoNotContainRawToken() {
        org.mockito.ArgumentCaptor<List<String>> keys = org.mockito.ArgumentCaptor.captor();
        given(redisTemplate.execute(any(RedisScript.class), keys.capture(), any(), any(), any(), any()))
                .willReturn(1L);

        String token = store.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 1L, 0L, Duration.ofMinutes(1));

        assertThat(keys.getValue()).noneMatch(key -> key.contains(token));
    }
}
