package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    @InjectMocks private RefreshTokenStore refreshTokenStore;

    @Test
    @DisplayName("save는 refresh:{userId} 키에 TTL과 함께 저장한다")
    void save_usesRefreshKeyWithTtl() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        refreshTokenStore.save(1L, "token", 1000L);

        verify(valueOperations).set("refresh:1", "token", 1000L, TimeUnit.MILLISECONDS);
    }

    @Test
    @DisplayName("rotate는 Lua 스크립트가 1을 반환하면 true다")
    void rotate_scriptReturnsOne_true() {
        given(redisTemplate.execute(any(RedisScript.class), eq(List.of("refresh:1")),
                eq("old"), eq("new"), eq("1000"))).willReturn(1L);

        assertThat(refreshTokenStore.rotate(1L, "old", "new", 1000L)).isTrue();
    }

    @Test
    @DisplayName("rotate는 Lua 스크립트가 0을 반환하면 false다 (불일치 — 스크립트가 키를 삭제한 상태)")
    void rotate_scriptReturnsZero_false() {
        given(redisTemplate.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString())).willReturn(0L);

        assertThat(refreshTokenStore.rotate(1L, "stolen", "new", 1000L)).isFalse();
    }

    @Test
    @DisplayName("delete는 refresh:{userId} 키를 지운다")
    void delete_usesRefreshKey() {
        refreshTokenStore.delete(7L);

        verify(redisTemplate).delete("refresh:7");
    }

    @Test
    @DisplayName("Redis 연결 실패는 500이 아니라 REDIS_UNAVAILABLE(503)로 변환된다")
    void redisFailure_mappedTo503() {
        given(redisTemplate.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString()))
                .willThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> refreshTokenStore.rotate(1L, "old", "new", 1000L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("save·delete의 Redis 장애도 503으로 변환된다")
    void redisFailureOnSaveAndDelete_mappedTo503() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        org.mockito.BDDMockito.willThrow(new RedisConnectionFailureException("down"))
                .given(valueOperations).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        assertThatThrownBy(() -> refreshTokenStore.save(1L, "token", 1000L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);

        given(redisTemplate.delete(anyString()))
                .willThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> refreshTokenStore.delete(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);
    }
}
