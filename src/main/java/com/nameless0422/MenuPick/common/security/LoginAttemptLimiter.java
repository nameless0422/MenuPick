package com.nameless0422.MenuPick.common.security;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

/**
 * 계정(이메일) 단위 로그인 실패 횟수 제한.
 *
 * <p>{@link RateLimitFilter}의 인증 버킷은 IP 기준이라, 여러 IP에서 한 계정의 비밀번호를
 * 나눠 대입하는 공격을 막지 못한다. 소셜 로그인만 있을 때는 대입할 비밀번호 자체가 없어
 * 문제가 되지 않았지만, 자체 계정이 생기면서 필요해졌다.
 *
 * <p>성공하면 카운터를 지우므로 정상 사용자는 영향을 받지 않는다. 공격자가 남의 계정을
 * 일부러 잠글 수 있다는 부작용이 있으나, 잠기는 것은 비밀번호 로그인뿐이고 메일을 통한
 * 비밀번호 재설정은 계속 동작하므로 사용자가 스스로 빠져나올 길이 남는다.
 *
 * <p>Redis 장애 시 시도 전 검사는 503으로 막는다. 카운터를 볼 수 없는 동안 비밀번호를 무제한
 * 대입하게 둘 수 없고, 로그인 성공 뒤의 세션 저장도 어차피 Redis가 있어야 한다.
 * 이미 판정이 끝난 뒤의 기록·초기화 실패는 결과를 뒤집지 않고 로그만 남긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginAttemptLimiter {

    private static final String KEY_PREFIX = "rl:login:";

    /** 이 횟수만큼 연속 실패하면 창이 끝날 때까지 막는다. */
    private static final int MAX_FAILURES = 10;

    /** ErrorCode.TOO_MANY_LOGIN_ATTEMPTS의 안내 문구("15분 후")와 같은 값이어야 한다. */
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private static final DefaultRedisScript<Long> COUNT_FAILURE_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /** 시도 전 호출. 이미 한도를 넘겼으면 429로 끊는다. */
    public void checkAllowed(String email) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(key(email));
        } catch (DataAccessException e) {
            log.warn("Redis 장애로 로그인 시도를 차단합니다: {}", e.getMessage());
            throw new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
        }

        if (value != null && Long.parseLong(value) >= MAX_FAILURES) {
            throw new BusinessException(ErrorCode.TOO_MANY_LOGIN_ATTEMPTS);
        }
    }

    public void recordFailure(String email) {
        execute(() -> redisTemplate.execute(COUNT_FAILURE_SCRIPT, List.of(key(email)),
                String.valueOf(WINDOW.toSeconds())), null);
    }

    public void reset(String email) {
        execute(() -> redisTemplate.delete(key(email)), null);
    }

    /**
     * 이메일을 그대로 키에 넣지 않는다 — Redis를 들여다볼 수 있는 사람에게 "어떤 주소가
     * 이 서비스를 쓰는지"를 넘겨줄 이유가 없다.
     */
    private String key(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }

    private <T> T execute(java.util.function.Supplier<T> operation, T fallback) {
        try {
            return operation.get();
        } catch (Exception e) {
            log.warn("Redis 장애로 로그인 실패 횟수를 갱신하지 못했습니다: {}", e.getMessage());
            return fallback;
        }
    }
}
