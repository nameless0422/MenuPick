package com.nameless0422.MenuPick.common.security;

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
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class LoginAttemptLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> values;

    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new LoginAttemptLimiter(redisTemplate);
    }

    private void assertRejectedWith(ErrorCode code) {
        assertThatThrownBy(() -> limiter.checkAllowed("user@example.invalid"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Test
    @DisplayName("한도 미만이면 통과하고 한도에 닿으면 429로 막는다")
    void checkAllowedHonorsLimit() {
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.get(anyString())).willReturn("9").willReturn("10");

        assertThatCode(() -> limiter.checkAllowed("user@example.invalid")).doesNotThrowAnyException();
        assertRejectedWith(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
    }

    @Test
    @DisplayName("Redis 장애면 시도를 503으로 막는다 — 카운터를 볼 수 없는 동안 대입을 허용하지 않는다")
    void checkAllowedFailsClosed() {
        given(redisTemplate.opsForValue()).willReturn(values);
        given(values.get(anyString())).willThrow(new QueryTimeoutException("down"));

        assertRejectedWith(ErrorCode.REDIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("판정이 끝난 뒤의 기록·초기화 실패는 결과를 뒤집지 않는다")
    void recordAndResetTolerateFailure() {
        given(redisTemplate.delete(anyString())).willThrow(new QueryTimeoutException("down"));

        assertThatCode(() -> limiter.reset("user@example.invalid")).doesNotThrowAnyException();
    }
}
