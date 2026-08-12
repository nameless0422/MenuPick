package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 메일로 보내는 일회용 토큰(이메일 인증·비밀번호 재설정)의 보관소.
 *
 * <p>Redis에 두는 이유는 두 토큰 모두 수명이 짧고, 만료된 행을 청소하는 스케줄러 없이
 * TTL로 끝나기 때문이다. Redis가 재시작되면 발송된 링크가 무효가 되지만, 두 흐름 모두
 * 재발송 수단이 있어 복구 가능하다.
 *
 * <p>저장하는 것은 토큰 자체가 아니라 SHA-256 해시다. Redis 덤프가 유출되어도 링크를
 * 되살릴 수 없다. 토큰이 256비트 난수라 사전 공격 대상이 아니므로 솔트·스트레칭은 두지 않았다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthTokenStore {

    /** 용도가 다른 토큰이 서로의 자리에서 통하지 않도록 키 공간을 분리한다. */
    public enum Purpose {
        VERIFY_EMAIL("auth:verify:"),
        PASSWORD_RESET("auth:reset:");

        private final String keyPrefix;

        Purpose(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }
    }

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate redisTemplate;

    /**
     * 새 토큰을 발급하고 해시를 저장한다.
     *
     * @return 메일 링크에 넣을 원본 토큰. 저장되지 않으므로 이 반환값이 유일한 사본이다
     */
    public String issue(Purpose purpose, Long userId, Duration ttl) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        execute(() -> {
            redisTemplate.opsForValue().set(key(purpose, token), String.valueOf(userId), ttl);
            return null;
        });

        return token;
    }

    /**
     * 토큰을 사용하고 즉시 폐기한다.
     *
     * <p>조회와 삭제를 나누면 같은 링크를 두 번 눌렀을 때 양쪽이 모두 통과하는 창이 열린다.
     * {@code GETDEL}은 Redis에서 단일 원자 연산이라 승자가 항상 하나다.
     *
     * @return 토큰이 유효했다면 그 주인의 userId, 아니면 비어 있음
     */
    public Optional<Long> consume(Purpose purpose, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String userId = execute(() -> redisTemplate.opsForValue().getAndDelete(key(purpose, token)));

        if (userId == null) {
            return Optional.empty();
        }
        return Optional.of(Long.valueOf(userId));
    }

    private String key(Purpose purpose, String token) {
        return purpose.keyPrefix + sha256(token);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256은 모든 JVM이 제공해야 하는 알고리즘이라 실제로는 도달하지 않는다.
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    /** {@link RefreshTokenStore}와 같은 이유로 Redis 장애를 503으로 바꾼다(그대로 두면 500). */
    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.error("인증 토큰 Redis 작업 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
        }
    }
}
