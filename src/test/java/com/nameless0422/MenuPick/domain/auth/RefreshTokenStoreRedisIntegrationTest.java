package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 회전 Lua 스크립트의 판정을 실제 Redis 위에서 확인한다.
 *
 * <p>스크립트는 Redis 안에서 돌기 때문에 목으로는 "무엇을 넘겼는가"까지만 볼 수 있고,
 * 정작 중요한 "유예 창 안이면 통과, 밖이면 체인 삭제"는 검증되지 않는다. 이 구분이
 * 틀리면 탭 두 개만으로 양쪽 세션이 끊기거나(너무 엄격), 탈취된 옛 토큰이 살아난다(너무 느슨).
 */
@SpringBootTest
@ActiveProfiles("integration")
class RefreshTokenStoreRedisIntegrationTest extends AbstractIntegrationTest {

    private static final long USER_ID = 4242L;
    private static final String KEY = "refresh:" + USER_ID;
    private static final String GRACE_KEY = KEY + ":prev";
    private static final long TTL = 60_000L;

    @Autowired private RefreshTokenStore refreshTokenStore;
    @Autowired private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clean() {
        redisTemplate.delete(List.of(KEY, GRACE_KEY));
    }

    @Test
    @DisplayName("현재 토큰을 제시하면 새 토큰으로 회전하고 직전 토큰은 유예 키에 남는다")
    void rotate_currentToken() {
        refreshTokenStore.save(USER_ID, "t1", TTL);

        assertThat(refreshTokenStore.rotate(USER_ID, "t1", "t2", TTL)).contains("t2");

        assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("t2");
        assertThat(redisTemplate.opsForValue().get(GRACE_KEY)).isEqualTo("t1");
        // 유예 값은 짧게만 살아야 한다 — 길수록 탈취된 옛 토큰의 수명이 그대로 늘어난다.
        assertThat(redisTemplate.getExpire(GRACE_KEY))
                .isLessThanOrEqualTo(RefreshTokenStore.GRACE_MILLIS / 1000);
    }

    @Test
    @DisplayName("유예 창 안의 직전 토큰은 탈취로 보지 않고 현재 토큰을 그대로 돌려준다")
    void rotate_previousTokenWithinGrace() {
        // 탭1이 t1 → t2로 회전한 직후, 탭2가 아직 t1을 들고 온 상황.
        refreshTokenStore.save(USER_ID, "t1", TTL);
        refreshTokenStore.rotate(USER_ID, "t1", "t2", TTL);

        assertThat(refreshTokenStore.rotate(USER_ID, "t1", "t3", TTL)).contains("t2");

        // 체인이 살아 있어야 하고, 여기서 t3로 굴러가서도 안 된다 — 굴러가면 두 탭이
        // 서로를 계속 밀어내며 같은 문제가 반복된다.
        assertThat(redisTemplate.opsForValue().get(KEY)).isEqualTo("t2");
        assertThat(refreshTokenStore.rotate(USER_ID, "t2", "t4", TTL)).contains("t4");
    }

    @Test
    @DisplayName("유예 창 밖의 옛 토큰은 여전히 재사용으로 보고 체인 전체를 삭제한다")
    void rotate_previousTokenAfterGrace() {
        refreshTokenStore.save(USER_ID, "t1", TTL);
        refreshTokenStore.rotate(USER_ID, "t1", "t2", TTL);
        // 유예 키의 만료를 기다리는 대신 직접 지운다 — 30초를 실제로 기다릴 수는 없다.
        redisTemplate.delete(GRACE_KEY);

        assertThat(refreshTokenStore.rotate(USER_ID, "t1", "t3", TTL)).isEmpty();

        assertThat(redisTemplate.hasKey(KEY)).isFalse();
        assertThat(redisTemplate.hasKey(GRACE_KEY)).isFalse();
    }

    @Test
    @DisplayName("아예 모르는 토큰도 체인 전체를 삭제한다")
    void rotate_unknownToken() {
        refreshTokenStore.save(USER_ID, "t1", TTL);

        assertThat(refreshTokenStore.rotate(USER_ID, "stolen", "t2", TTL)).isEmpty();

        assertThat(redisTemplate.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("저장된 체인이 없으면 어떤 토큰도 받아주지 않는다")
    void rotate_noChain() {
        assertThat(refreshTokenStore.rotate(USER_ID, "t1", "t2", TTL)).isEmpty();
    }

    @Test
    @DisplayName("새 로그인은 유예 값까지 버린다 — 비밀번호 재설정이 옛 토큰에 새 세션을 넘겨주면 안 된다")
    void save_dropsGraceValue() {
        refreshTokenStore.save(USER_ID, "t1", TTL);
        refreshTokenStore.rotate(USER_ID, "t1", "t2", TTL);

        refreshTokenStore.save(USER_ID, "fresh", TTL);

        assertThat(redisTemplate.hasKey(GRACE_KEY)).isFalse();
        assertThat(refreshTokenStore.rotate(USER_ID, "t1", "t3", TTL)).isEmpty();
    }

    @Test
    @DisplayName("로그아웃은 본 키와 유예 키를 함께 지운다")
    void delete_removesGraceValue() {
        refreshTokenStore.save(USER_ID, "t1", TTL);
        refreshTokenStore.rotate(USER_ID, "t1", "t2", TTL);

        refreshTokenStore.delete(USER_ID);

        assertThat(redisTemplate.hasKey(KEY)).isFalse();
        assertThat(redisTemplate.hasKey(GRACE_KEY)).isFalse();
    }
}
