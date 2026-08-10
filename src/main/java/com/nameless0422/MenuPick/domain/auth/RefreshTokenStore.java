package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Refresh Token의 Redis 보관소.
 *
 * <p>키 포맷({@code refresh:{userId}})과 Redis 장애 처리(503 매핑)를 한 곳에 모아
 * AuthService·UserHardDeleteService가 같은 규칙을 공유하게 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";

    private static final Long ROTATED = 1L;

    /**
     * Refresh Token 회전을 compare-and-swap으로 원자화한다.
     *
     * <p>GET→비교→SET을 애플리케이션에서 나눠 수행하면 동시에 도착한 두 refresh 요청이
     * 모두 "일치" 판정을 받아 두 개의 유효한 토큰이 생기는 창이 열린다. Lua 스크립트는
     * Redis에서 단일 원자 실행되므로 승자는 항상 하나다.
     *
     * <p>저장값이 제시값과 다르면(= 이미 회전됐거나 탈취된 토큰) 키를 지워 해당 유저의
     * 토큰 체인 전체를 무효화한다 — 기존 탈취 대응 정책 그대로다.
     */
    private static final DefaultRedisScript<Long> ROTATE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "  redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3]) " +
            "  return 1 " +
            "else " +
            "  redis.call('DEL', KEYS[1]) " +
            "  return 0 " +
            "end",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /** 신규 로그인 시 Refresh Token을 저장한다(기존 값 덮어쓰기). */
    public void save(Long userId, String refreshToken, long ttlMillis) {
        execute(() -> {
            redisTemplate.opsForValue()
                    .set(key(userId), refreshToken, ttlMillis, TimeUnit.MILLISECONDS);
            return null;
        });
    }

    /**
     * 저장값이 {@code presentedToken}과 같을 때만 {@code newToken}으로 교체한다.
     *
     * @return 회전 성공 여부. false면 키가 삭제된 상태다.
     */
    public boolean rotate(Long userId, String presentedToken, String newToken, long ttlMillis) {
        Long result = execute(() -> redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(key(userId)),
                presentedToken, newToken, String.valueOf(ttlMillis)));
        return ROTATED.equals(result);
    }

    public void delete(Long userId) {
        execute(() -> redisTemplate.delete(key(userId)));
    }

    /**
     * Redis 장애(연결 실패·타임아웃 등)를 503으로 변환한다.
     * 그대로 두면 DataAccessException이 전역 핸들러의 Exception 분기로 떨어져 500이 된다.
     */
    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.error("Refresh Token Redis 작업 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }
}
