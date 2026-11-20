package com.nameless0422.MenuPick.common.security;

import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthVersionCacheTest {

    @Mock private UserRepository userRepository;

    private final AtomicLong clock = new AtomicLong(1_000L);
    private AuthVersionCache cache;

    @BeforeEach
    void setUp() {
        cache = new AuthVersionCache(userRepository, clock::get);
    }

    @Test
    @DisplayName("한 번 읽은 버전은 TTL 동안 DB에 다시 묻지 않는다")
    void cachesWithinTtl() {
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.of(3L));

        assertThat(cache.findActiveAuthVersion(1L)).contains(3L);
        clock.addAndGet(AuthVersionCache.TTL.toNanos() - 1);
        assertThat(cache.findActiveAuthVersion(1L)).contains(3L);

        verify(userRepository, times(1)).findActiveAuthVersion(1L);
    }

    @Test
    @DisplayName("TTL이 지나면 다시 읽는다 — 앱 밖에서 DB를 고친 경우의 안전망")
    void reloadsAfterTtl() {
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.of(3L), Optional.of(4L));

        cache.findActiveAuthVersion(1L);
        clock.addAndGet(AuthVersionCache.TTL.toNanos());

        assertThat(cache.findActiveAuthVersion(1L)).contains(4L);
    }

    @Test
    @DisplayName("없거나 탈퇴한 계정도 기억하고, 무효화하면 다시 읽는다")
    void cachesMissingAccountUntilEvicted() {
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.empty(), Optional.of(0L));

        assertThat(cache.findActiveAuthVersion(1L)).isEmpty();
        assertThat(cache.findActiveAuthVersion(1L)).isEmpty();
        cache.evict(1L);

        assertThat(cache.findActiveAuthVersion(1L)).contains(0L);
        verify(userRepository, times(2)).findActiveAuthVersion(1L);
    }

    @Test
    @DisplayName("읽는 도중 무효화가 끼면 그 값을 저장하지 않는다 — 커밋 전 옛 버전이 살아남지 않게")
    void doesNotStoreValueReadAcrossAnInvalidation() {
        willAnswer(invocation -> {
            cache.evict(1L); // 옛 값을 읽은 직후 다른 트랜잭션이 버전을 올리고 커밋했다
            return Optional.of(3L);
        }).given(userRepository).findActiveAuthVersion(1L);

        cache.findActiveAuthVersion(1L);
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.of(4L));

        assertThat(cache.findActiveAuthVersion(1L)).contains(4L);
    }

    @Test
    @DisplayName("다른 사용자의 무효화는 남의 항목을 지우지 않는다")
    void evictIsPerUser() {
        given(userRepository.findActiveAuthVersion(1L)).willReturn(Optional.of(3L));
        cache.findActiveAuthVersion(1L);

        cache.evict(2L);
        cache.findActiveAuthVersion(1L);

        verify(userRepository, times(1)).findActiveAuthVersion(1L);
    }

    @Test
    @DisplayName("DB 오류는 삼키지 않고 던지며, 실패를 기억하지도 않는다")
    void propagatesDatabaseErrors() {
        given(userRepository.findActiveAuthVersion(1L))
                .willThrow(new DataAccessResourceFailureException("down"))
                .willReturn(Optional.of(3L));

        assertThatThrownBy(() -> cache.findActiveAuthVersion(1L))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(cache.findActiveAuthVersion(1L)).contains(3L);
    }
}
