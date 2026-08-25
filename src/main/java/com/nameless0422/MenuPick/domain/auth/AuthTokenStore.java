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
     * 새 토큰을 발급하고 해시를 저장한다. <b>같은 사용자·같은 용도의 이전 토큰은 폐기된다.</b>
     *
     * <p><b>왜 이전 토큰을 지워야 하는가.</b> 예전에는 {@code auth:verify:{hash} -> userId} 한
     * 방향만 저장해, 발급된 토큰을 사용자 기준으로 찾아낼 수단이 아예 없었다. 그래서 같은
     * 사용자에게 토큰이 둘 이상 동시에 유효할 수 있었고, 다음이 실제로 열렸다:
     *
     * <ol>
     *   <li>피해자가 정상 가입하고 인증 메일을 받는다(TTL 24시간). 아직 누르지 않았다.</li>
     *   <li>그 사이 공격자가 같은 주소로 재가입한다. 미인증 계정은 주소 선점을 막기 위해
     *       비밀번호를 덮어쓰도록 돼 있어({@code createOrReplacePendingAccount}),
     *       LOCAL 행의 해시가 공격자 비밀번호로 바뀐다.</li>
     *   <li>피해자가 뒤늦게 <b>자기가 받은</b> 링크를 누른다. 그 토큰은 여전히 유효하므로
     *       계정이 <b>공격자 비밀번호를 가진 채</b> 활성화된다. 피해자는 "로그인이 안 된다"고만
     *       인지한다.</li>
     * </ol>
     *
     * <p>역인덱스({@code auth:verify:user:{userId} -> 현재 토큰 해시})를 두고 발급 때마다
     * 이전 것을 지우면, 2단계에서 피해자의 토큰이 함께 죽는다.
     *
     * <p>비밀번호 재설정에도 같은 규칙을 적용한다. "한 번만 사용할 수 있다"고 안내하면서
     * 재요청 전의 링크가 계속 살아 있는 것이 더 이상하고, 살아 있는 링크가 하나뿐이면
     * 유출 시 회수 범위도 명확해진다.
     *
     * <p>동시 발급 사이의 경쟁은 남는다 — 두 요청이 겹치면 잠깐 토큰 둘이 유효할 수 있다.
     * 다만 그 둘은 모두 정당하게 발급된 것이고, 위 시나리오처럼 "비밀번호가 바뀐 뒤에도
     * 옛 토큰이 남는" 상황과는 다르다. Lua 스크립트로 원자화할 수 있으나 이 위험에 비해
     * 과하다고 판단했다.
     *
     * @return 메일 링크에 넣을 원본 토큰. 저장되지 않으므로 이 반환값이 유일한 사본이다
     */
    public String issue(Purpose purpose, Long userId, Duration ttl) {
        byte[] raw = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenHash = sha256(token);

        execute(() -> {
            String ownerKey = ownerKey(purpose, userId);

            String previousHash = redisTemplate.opsForValue().get(ownerKey);
            if (previousHash != null) {
                redisTemplate.delete(purpose.keyPrefix + previousHash);
            }

            redisTemplate.opsForValue().set(purpose.keyPrefix + tokenHash, String.valueOf(userId), ttl);
            // 역인덱스의 TTL은 토큰과 같다. 더 길면 이미 만료된 해시를 가리킨 채 남고,
            // 짧으면 아직 유효한 토큰을 폐기할 수단이 먼저 사라진다.
            redisTemplate.opsForValue().set(ownerKey, tokenHash, ttl);
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

        String tokenHash = sha256(token);
        String userId = execute(() -> redisTemplate.opsForValue().getAndDelete(purpose.keyPrefix + tokenHash));

        if (userId == null) {
            return Optional.empty();
        }

        // 역인덱스도 함께 치운다. 남겨 두면 이미 없는 토큰을 가리킨 채 TTL 동안 버티다가,
        // 그 사이 발급되는 새 토큰이 "이전 토큰"이라며 엉뚱한 키를 지우게 된다(실제로는
        // 없는 키라 무해하지만, 인덱스가 사실과 어긋난 채 도는 것 자체가 다음 버그의 씨앗이다).
        // 이 토큰이 최신이 아니었다면 인덱스는 더 새 토큰을 가리키고 있으므로 건드리지 않는다.
        execute(() -> {
            String ownerKey = ownerKey(purpose, Long.valueOf(userId));
            if (tokenHash.equals(redisTemplate.opsForValue().get(ownerKey))) {
                redisTemplate.delete(ownerKey);
            }
            return null;
        });

        return Optional.of(Long.valueOf(userId));
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
