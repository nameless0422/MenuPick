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
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
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
 *
 * <p><b>이 저장소가 보장하는 것은 "사용자·용도별로 살아 있는 토큰은 하나"까지다.</b> 비밀번호가
 * 바뀐 뒤의 링크 거절은 여기서 할 수 없다. DB 커밋과 Redis 작업은 한 원자 단위가 아니므로, 호출자가
 * 값에 묶인 {@code credentialVersion}을 잠금 트랜잭션에서 비교한다({@code LocalAuthService}).
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

    /** 소비에 성공한 토큰의 주인과, 발급 당시의 자격증명 버전. */
    public record Consumed(Long userId, long credentialVersion) {}

    private static final int TOKEN_BYTES = 32;

    /**
     * 이전 토큰 폐기와 새 토큰 저장을 한 번에 실행한다. 명령을 나누면 동시 발급이 같은 이전 해시를
     * 읽고 각자 저장해 토큰 둘이 유효하게 남는다.
     *
     * <p>역인덱스의 TTL은 토큰과 같다. 더 길면 이미 만료된 해시를 가리킨 채 남고, 짧으면 아직 유효한
     * 토큰을 폐기할 수단이 먼저 사라진다.
     *
     * <p>이전 토큰의 키를 스크립트 안에서 조립하므로 Redis Cluster로 옮기면 같은 슬롯을 보장해야
     * 한다. 지금은 단일 노드다({@link RefreshTokenStore}와 같은 전제).
     */
    private static final DefaultRedisScript<Long> ISSUE_SCRIPT = new DefaultRedisScript<>(
            """
            local previous = redis.call('GET', KEYS[1])
            if previous then
              redis.call('DEL', ARGV[1] .. previous)
            end
            redis.call('SET', KEYS[2], ARGV[3], 'PX', ARGV[4])
            redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[4])
            return 1
            """,
            Long.class
    );

    /**
     * 토큰을 소비한다. 키가 있어도 역인덱스가 이 토큰을 가리키지 않으면 거절한다.
     *
     * <p>{@code userId:version} 형식이 아닌 값은 이 형식 도입 전에 발급된 링크다. 어떤 비밀번호에
     * 묶였는지 알 수 없으므로 통과시키지 않는다(사용자는 재발송·재요청으로 이어 갈 수 있다).
     */
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>(
            """
            local value = redis.call('GET', KEYS[1])
            if not value then
              return false
            end
            redis.call('DEL', KEYS[1])
            local userId = string.match(value, '^(%d+):%d+$')
            if not userId then
              return false
            end
            local ownerKey = ARGV[1] .. 'user:' .. userId
            if redis.call('GET', ownerKey) ~= ARGV[2] then
              return false
            end
            redis.call('DEL', ownerKey)
            return value
            """,
            String.class
    );

    private static final DefaultRedisScript<Long> REVOKE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('GET', KEYS[1])
            if current then
              redis.call('DEL', ARGV[1] .. current, KEYS[1])
            end
            return 1
            """,
            Long.class
    );

    private final SecureRandom secureRandom = new SecureRandom();
    private final StringRedisTemplate redisTemplate;

    /**
     * 새 토큰을 발급하고 해시를 저장한다. <b>같은 사용자·같은 용도의 이전 토큰은 폐기된다.</b>
     *
     * <p>살아 있는 링크가 하나뿐이어야 "한 번만 사용할 수 있다"는 안내가 사실이 되고, 유출 시
     * 회수 범위도 명확해진다.
     *
     * @param credentialVersion 이 링크를 묶을 자격증명 버전. 소비 시 그대로 돌려준다
     * @return 메일 링크에 넣을 원본 토큰. 저장되지 않으므로 이 반환값이 유일한 사본이다
     */
    public String issue(Purpose purpose, Long userId, long credentialVersion, Duration ttl) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = sha256(token);

        execute(() -> redisTemplate.execute(
                ISSUE_SCRIPT,
                List.of(ownerKey(purpose, userId), purpose.keyPrefix + tokenHash),
                purpose.keyPrefix, tokenHash, userId + ":" + credentialVersion,
                String.valueOf(ttl.toMillis())));

        return token;
    }

    /**
     * 토큰을 사용하고 즉시 폐기한다. 스크립트가 단일 원자 실행이라 같은 링크를 두 번 눌러도
     * 승자는 항상 하나다.
     *
     * @return 토큰이 유효했다면 주인과 자격증명 버전, 아니면 비어 있음
     */
    public Optional<Consumed> consume(Purpose purpose, String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        String tokenHash = sha256(token);
        String value = execute(() -> redisTemplate.execute(
                CONSUME_SCRIPT,
                List.of(purpose.keyPrefix + tokenHash),
                purpose.keyPrefix, tokenHash));

        if (value == null) {
            return Optional.empty();
        }

        String[] parts = value.split(":");
        return Optional.of(new Consumed(Long.valueOf(parts[0]), Long.parseLong(parts[1])));
    }

    /** 이 사용자의 살아 있는 토큰을 폐기한다. 없으면 아무 일도 하지 않는다. */
    public void revoke(Purpose purpose, Long userId) {
        execute(() -> redisTemplate.execute(
                REVOKE_SCRIPT,
                List.of(ownerKey(purpose, userId)),
                purpose.keyPrefix));
    }

    /** 사용자별 "지금 살아 있는 토큰"의 해시를 가리키는 역인덱스 키. */
    private String ownerKey(Purpose purpose, Long userId) {
        return purpose.keyPrefix + "user:" + userId;
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
