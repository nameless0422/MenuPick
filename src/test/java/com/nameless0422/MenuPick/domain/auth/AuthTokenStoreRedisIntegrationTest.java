package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.domain.auth.AuthTokenStore.Consumed;
import com.nameless0422.MenuPick.domain.auth.AuthTokenStore.Purpose;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** 발급·소비 Lua 스크립트의 판정은 Redis 안에서 일어나므로 실제 Redis 위에서 확인한다. */
@SpringBootTest
@ActiveProfiles("integration")
class AuthTokenStoreRedisIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 7171L;
    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired private AuthTokenStore store;
    @Autowired private StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        store.revoke(Purpose.PASSWORD_RESET, USER_ID);
        store.revoke(Purpose.VERIFY_EMAIL, USER_ID);
    }

    @Test
    @DisplayName("소비하면 주인과 발급 당시 자격증명 버전을 돌려주고, 두 번은 소비되지 않는다")
    void consumeReturnsBindingOnce() {
        String token = store.issue(Purpose.PASSWORD_RESET, USER_ID, 3L, TTL);

        assertThat(store.consume(Purpose.PASSWORD_RESET, token)).contains(new Consumed(USER_ID, 3L));
        assertThat(store.consume(Purpose.PASSWORD_RESET, token)).isEmpty();
        assertThat(redis.hasKey("auth:reset:user:" + USER_ID)).isFalse();
    }

    @Test
    @DisplayName("재발급하면 이전 토큰은 폐기되고 용도가 다른 토큰은 건드리지 않는다")
    void reissueRevokesPrevious() {
        String verify = store.issue(Purpose.VERIFY_EMAIL, USER_ID, 0L, TTL);
        String older = store.issue(Purpose.PASSWORD_RESET, USER_ID, 0L, TTL);
        String newer = store.issue(Purpose.PASSWORD_RESET, USER_ID, 0L, TTL);

        assertThat(store.consume(Purpose.PASSWORD_RESET, older)).isEmpty();
        assertThat(store.consume(Purpose.PASSWORD_RESET, newer)).isPresent();
        assertThat(store.consume(Purpose.VERIFY_EMAIL, verify)).isPresent();
    }

    @Test
    @DisplayName("동시에 발급해도 소비할 수 있는 토큰은 마지막 하나뿐이다")
    void concurrentIssueLeavesSingleValidToken() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return store.issue(Purpose.PASSWORD_RESET, USER_ID, 0L, TTL);
                }));
            }
            start.countDown();

            int valid = 0;
            for (Future<String> future : futures) {
                if (store.consume(Purpose.PASSWORD_RESET, future.get()).isPresent()) {
                    valid++;
                }
            }
            assertThat(valid).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("역인덱스가 가리키지 않는 토큰은 키가 남아 있어도 거절한다")
    void consumeRejectsTokenNotCurrent() throws Exception {
        String older = store.issue(Purpose.PASSWORD_RESET, USER_ID, 0L, TTL);
        String newer = store.issue(Purpose.PASSWORD_RESET, USER_ID, 0L, TTL);
        redis.opsForValue().set("auth:reset:" + sha256(older), USER_ID + ":0", TTL);

        assertThat(store.consume(Purpose.PASSWORD_RESET, older)).isEmpty();
        assertThat(store.consume(Purpose.PASSWORD_RESET, newer)).isPresent();
    }

    @Test
    @DisplayName("revoke 뒤에는 살아 있던 토큰을 쓸 수 없다")
    void revokeInvalidatesCurrent() {
        String token = store.issue(Purpose.PASSWORD_RESET, USER_ID, 0L, TTL);

        store.revoke(Purpose.PASSWORD_RESET, USER_ID);

        assertThat(store.consume(Purpose.PASSWORD_RESET, token)).isEmpty();
    }

    @Test
    @DisplayName("배포 전 형식(userId만 저장)의 링크는 어떤 비밀번호에 묶였는지 몰라 거절한다")
    void legacyFormatIsRejected() throws Exception {
        String legacy = "legacy-token-" + System.nanoTime();
        String hash = sha256(legacy);
        redis.opsForValue().set("auth:verify:" + hash, String.valueOf(USER_ID), TTL);
        redis.opsForValue().set("auth:verify:user:" + USER_ID, hash, TTL);

        assertThat(store.consume(Purpose.VERIFY_EMAIL, legacy)).isEmpty();
        assertThat(redis.hasKey("auth:verify:" + hash)).isFalse();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
