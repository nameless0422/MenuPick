package com.nameless0422.MenuPick.domain.auth;

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
 * 수신 주소별 인증 메일 한도(D-076).
 *
 * <p>IP 버킷({@code RateLimitFilter})은 여러 IP에서 한 주소로 메일을 퍼붓는 것을 막지 못한다.
 * 가입·재발송·재설정이 같은 예산을 쓴다. 경로마다 나누면 번갈아 호출해 한도가 늘어난다.
 *
 * <p>한도를 넘어도 호출자는 평소와 같은 응답을 준다. 거절을 드러내면 "이 주소에 계정이 있어 메일이
 * 나갔다"는 사실이 응답으로 새기 때문이다. 토큰도 새로 만들지 않으므로 이미 받은 링크는 유효하다.
 *
 * <p>Redis 장애 시 503으로 막는다. 카운터를 볼 수 없는 동안 무제한 발송을 허용하지 않으며, 토큰 발급도
 * 어차피 Redis가 있어야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailSendLimiter {

    private static final String KEY_PREFIX = "rl:mail:";
    static final int MAX_MAILS = 5;
    private static final Duration WINDOW = Duration.ofHours(1);

    private static final DefaultRedisScript<Long> COUNT_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('INCR', KEYS[1]) " +
            "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return count",
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /** 이미 예산을 다 썼는지 세지 않고 본다. 가입은 비밀번호를 덮어쓰기 전에 이 값으로 멈춘다. */
    public boolean isExhausted(String email) {
        try {
            String value = redisTemplate.opsForValue().get(key(email));
            return value != null && Long.parseLong(value) >= MAX_MAILS;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    /** 발송 한 건을 세고, 이 건이 한도를 넘으면 true를 돌려준다. true면 보내지 않는다. */
    public boolean countAndCheckExceeded(String email) {
        try {
            Long count = redisTemplate.execute(COUNT_SCRIPT, List.of(key(email)),
                    String.valueOf(WINDOW.toSeconds()));
            return count != null && count > MAX_MAILS;
        } catch (DataAccessException e) {
            throw unavailable(e);
        }
    }

    private BusinessException unavailable(DataAccessException e) {
        log.warn("Redis 장애로 인증 메일 발송을 차단합니다: {}", e.getMessage());
        return new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
    }

    /** 주소를 그대로 키에 넣지 않는다 — 근거는 {@code LoginAttemptLimiter}와 같다. */
    private String key(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(email.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", e);
        }
    }
}
