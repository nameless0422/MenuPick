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
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 저장소가 Redis에 무엇을 넘기고 무엇을 돌려주는지만 본다.
 *
 * <p>Lua 스크립트가 실제로 어떻게 판정하는지(유예 창 안/밖)는 여기서 확인할 수 없다 —
 * 스크립트는 Redis 안에서 돈다. 그쪽은 {@link RefreshTokenStoreRedisIntegrationTest}가 맡는다.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenStoreTest {

    private static final List<String> KEYS = List.of("refresh:1", "refresh:1:prev");

    @Mock private StringRedisTemplate redisTemplate;

    @InjectMocks private RefreshTokenStore refreshTokenStore;

    @Test
    @DisplayName("save는 본 키와 유예 키를 한 스크립트에 함께 넘긴다 — 남은 유예 값이 새 세션을 넘겨주지 못하게")
    void save_passesBothKeys() {
        refreshTokenStore.save(1L, "token", 1000L);

        verify(redisTemplate).execute(any(RedisScript.class), eq(KEYS), eq("token"), eq("1000"));
    }

    @Test
    @DisplayName("rotate는 스크립트가 돌려준 토큰을 그대로 전달한다")
    void rotate_returnsScriptToken() {
        given(redisTemplate.execute(any(RedisScript.class), eq(KEYS),
                eq("old"), eq("new"), eq("1000"), eq(String.valueOf(RefreshTokenStore.GRACE_MILLIS))))
                .willReturn("new");

        assertThat(refreshTokenStore.rotate(1L, "old", "new", 1000L)).contains("new");
    }

    @Test
    @DisplayName("유예 창 안의 직전 토큰이면 스크립트가 현재 토큰을 돌려주고 그 값이 그대로 나간다")
    void rotate_graceHit_returnsCurrentToken() {
        // 새로 만든 토큰이 아니라 이미 유효한 토큰이 나와야 한다 — 여기서 회전시키면
        // 두 탭이 서로를 계속 밀어내며 같은 문제가 반복된다.
        given(redisTemplate.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .willReturn("current");

        assertThat(refreshTokenStore.rotate(1L, "previous", "new", 1000L)).contains("current");
    }

    @Test
    @DisplayName("스크립트가 아무것도 돌려주지 않으면 거부다 (재사용 탐지 — 스크립트가 체인을 삭제한 상태)")
    void rotate_scriptReturnsNil_empty() {
        given(redisTemplate.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .willReturn(null);

        assertThat(refreshTokenStore.rotate(1L, "stolen", "new", 1000L)).isEmpty();
    }

    @Test
    @DisplayName("delete는 본 키와 유예 키를 함께 지운다")
    void delete_removesBothKeys() {
        refreshTokenStore.delete(7L);

        verify(redisTemplate).delete(List.of("refresh:7", "refresh:7:prev"));
    }

    @Test
    @DisplayName("Redis 연결 실패는 500이 아니라 REDIS_UNAVAILABLE(503)로 변환된다")
    void redisFailure_mappedTo503() {
        given(redisTemplate.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString(), anyString(), anyString()))
                .willThrow(new RedisConnectionFailureException("connection refused"));

        assertThatThrownBy(() -> refreshTokenStore.rotate(1L, "old", "new", 1000L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("save·delete의 Redis 장애도 503으로 변환된다")
    void redisFailureOnSaveAndDelete_mappedTo503() {
        given(redisTemplate.execute(any(RedisScript.class), any(List.class),
                anyString(), anyString()))
                .willThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> refreshTokenStore.save(1L, "token", 1000L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);

        given(redisTemplate.delete(anyCollection()))
                .willThrow(new RedisConnectionFailureException("down"));

        assertThatThrownBy(() -> refreshTokenStore.delete(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);
    }
}
