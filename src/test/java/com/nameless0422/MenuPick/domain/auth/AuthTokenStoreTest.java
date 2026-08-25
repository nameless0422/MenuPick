package com.nameless0422.MenuPick.domain.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

/**
 * 토큰 하나만 살아 있게 유지하는 규칙을 본다.
 *
 * <p>Redis 대신 {@link HashMap}을 깔아 둔다. 여기서 확인할 것은 Redis의 동작이 아니라
 * "발급이 이전 토큰을 지우는가, 소비가 역인덱스를 정리하는가"라는 저장소 자신의 규칙이라,
 * 키가 어떤 순서로 오가는지만 정확히 보이면 충분하다. TTL은 이 테스트의 관심사가 아니다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthTokenStoreTest {

    private static final Duration TTL = Duration.ofHours(24);

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;

    private final Map<String, String> redis = new HashMap<>();

    private AuthTokenStore authTokenStore;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);

        willAnswer(invocation -> redis.put(invocation.getArgument(0), invocation.getArgument(1)))
                .given(valueOperations).set(anyString(), anyString(), any(Duration.class));
        given(valueOperations.get(anyString()))
                .willAnswer(invocation -> redis.get(invocation.getArgument(0)));
        given(valueOperations.getAndDelete(anyString()))
                .willAnswer(invocation -> redis.remove(invocation.getArgument(0)));
        given(redisTemplate.delete(anyString()))
                .willAnswer(invocation -> redis.remove(invocation.getArgument(0)) != null);

        authTokenStore = new AuthTokenStore(redisTemplate);
    }

    @Test
    @DisplayName("발급한 토큰은 그 주인의 id로 소비된다")
    void issueThenConsume() {
        String token = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 1L, TTL);

        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, token)).contains(1L);
    }

    /**
     * 이 테스트가 막는 시나리오. 피해자가 인증 메일을 받은 뒤 아직 누르지 않은 사이,
     * 공격자가 같은 주소로 재가입해 미인증 계정의 비밀번호를 덮어쓴다. 피해자가 뒤늦게
     * 자기 링크를 누르면 계정이 공격자 비밀번호를 가진 채 활성화된다.
     * 재발급이 이전 토큰을 죽이면 그 순간 피해자의 링크도 함께 무효가 된다.
     */
    @Test
    @DisplayName("같은 사용자에게 다시 발급하면 이전 토큰은 더 이상 통하지 않는다")
    void reissueInvalidatesPreviousToken() {
        String victimToken = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 1L, TTL);
        String attackerToken = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 1L, TTL);

        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, victimToken)).isEmpty();
        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, attackerToken)).contains(1L);
    }

    @Test
    @DisplayName("다른 사용자의 발급은 남의 토큰을 건드리지 않는다")
    void reissueDoesNotTouchOtherUsers() {
        String other = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 2L, TTL);
        authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 1L, TTL);

        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, other)).contains(2L);
    }

    @Test
    @DisplayName("용도가 다르면 서로의 토큰을 폐기하지 않는다")
    void purposesAreIndependent() {
        String verify = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 1L, TTL);
        authTokenStore.issue(AuthTokenStore.Purpose.PASSWORD_RESET, 1L, TTL);

        // 비밀번호 재설정을 요청했다고 진행 중이던 이메일 인증이 끊기면 안 된다.
        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, verify)).contains(1L);
    }

    @Test
    @DisplayName("한 번 쓴 토큰은 두 번 통하지 않는다")
    void consumeIsOneShot() {
        String token = authTokenStore.issue(AuthTokenStore.Purpose.PASSWORD_RESET, 1L, TTL);

        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.PASSWORD_RESET, token)).contains(1L);
        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.PASSWORD_RESET, token)).isEmpty();
    }

    /**
     * 소비가 역인덱스를 치우지 않으면, 이미 없는 토큰을 가리키는 항목이 TTL 동안 남는다.
     * 사실과 어긋난 인덱스는 그 자체로 다음 버그의 씨앗이라 소비 시점에 정리한다.
     */
    @Test
    @DisplayName("소비하면 그 사용자의 역인덱스도 함께 정리된다")
    void consumeClearsOwnerIndex() {
        String token = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 7L, TTL);
        authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, token);

        assertThat(redis).doesNotContainKey("auth:verify:user:7");
    }

    /**
     * 옛 토큰을 소비해도 그 뒤에 발급된 최신 토큰의 인덱스는 지우면 안 된다.
     * 지우면 그 다음 발급이 최신 토큰을 "이전 것"으로 인식하지 못해 둘이 동시에 살아남는다.
     */
    @Test
    @DisplayName("최신이 아닌 토큰을 소비해도 최신 토큰의 인덱스는 건드리지 않는다")
    void consumeKeepsIndexOfNewerToken() {
        String older = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 7L, TTL);
        String newer = authTokenStore.issue(AuthTokenStore.Purpose.VERIFY_EMAIL, 7L, TTL);
        // 폐기된 옛 토큰의 키를 되살려, "지워지기 전에 소비가 들어온" 경쟁 상황을 만든다.
        // 이 줄이 없으면 옛 토큰 소비가 곧바로 빈 값으로 끝나 인덱스 정리 경로에 닿지 않는다.
        redis.put("auth:verify:" + sha256(older), "7");

        assertThat(authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, older)).contains(7L);

        assertThat(redis).containsEntry("auth:verify:user:7", sha256(newer));
    }

    @Test
    @DisplayName("빈 토큰은 Redis를 건드리지 않고 바로 실패한다")
    void blankTokenIsRejected() {
        Optional<Long> result = authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, "  ");

        assertThat(result).isEmpty();
    }

    /** 저장소가 키를 만들 때 쓰는 것과 같은 방식. */
    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
