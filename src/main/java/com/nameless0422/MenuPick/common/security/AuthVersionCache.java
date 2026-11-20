package com.nameless0422.MenuPick.common.security;

import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * 요청마다 보는 {@code users.auth_version}을 앱 메모리에 잠깐 들고 있는다(D-088).
 *
 * <p>D-070 이후 인증이 필요한 요청은 전부 이 값을 DB에서 읽었다. 필터는 트랜잭션 밖이라
 * ({@code open-in-view: false}) 그 조회가 커넥션을 따로 한 번 더 빌렸고, 2026-11-21 부하 시험에서
 * 풀(10)이 300~350 rps에서 마르는 원인 중 하나로 보였다.
 *
 * <p><b>무효화는 시간이 아니라 변경으로 한다.</b> 버전·탈퇴 상태가 바뀌는 경로는 전부 {@code User}
 * 엔티티를 고치거나 지우므로, {@link UserAuthStateListener}가 커밋 직후 그 사용자 항목을 지운다.
 * 앱은 한 대라 이 삭제가 곧 전체 무효화다 — 로그아웃·비밀번호 변경·탈퇴는 지금처럼 즉시 반영된다.
 * {@link #TTL}은 앱 밖에서 DB를 직접 고친 경우(운영자 SQL, 백업 복구)만 덮는 안전망이다.
 *
 * <p><b>읽는 도중 무효화가 끼면 저장하지 않는다.</b> 커밋 전의 옛 값을 읽은 요청이 무효화 뒤에
 * 그 값을 넣으면 TTL 동안 옛 세션이 살아난다. 그래서 읽기 전후로 무효화 횟수를 비교하고,
 * 바뀌었으면 넣지 않는다(넣은 직후에 바뀌었으면 도로 뺀다).
 */
@Component
public class AuthVersionCache {

    static final Duration TTL = Duration.ofSeconds(10);
    /** 활성 사용자 수보다 넉넉하게. 넘으면 통째로 비운다 — 다시 채우는 비용은 조회 한 번씩이다. */
    static final int MAX_ENTRIES = 10_000;

    /** {@code version}이 null이면 "없거나 탈퇴한 계정"을 기억한 것이다. */
    private record Entry(Long version, long expiresAtNanos) {}

    private final UserRepository userRepository;
    private final LongSupplier nanoTime;
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong invalidations = new AtomicLong();

    @Autowired
    public AuthVersionCache(UserRepository userRepository) {
        this(userRepository, System::nanoTime);
    }

    AuthVersionCache(UserRepository userRepository, LongSupplier nanoTime) {
        this.userRepository = userRepository;
        this.nanoTime = nanoTime;
    }

    /** {@link UserRepository#findActiveAuthVersion}과 같은 답. DB 오류는 그대로 던진다. */
    public Optional<Long> findActiveAuthVersion(Long userId) {
        long now = nanoTime.getAsLong();
        Entry cached = entries.get(userId);
        if (cached != null && now - cached.expiresAtNanos() < 0) {
            return Optional.ofNullable(cached.version());
        }

        long seen = invalidations.get();
        Optional<Long> loaded = userRepository.findActiveAuthVersion(userId);
        if (invalidations.get() == seen) {
            if (entries.size() >= MAX_ENTRIES) entries.clear();
            entries.put(userId, new Entry(loaded.orElse(null), now + TTL.toNanos()));
            // 비교와 저장 사이에 무효화가 끼었으면 방금 넣은 값을 믿을 수 없다.
            if (invalidations.get() != seen) entries.remove(userId);
        }
        return loaded;
    }

    /** 이 사용자의 버전·탈퇴 상태가 바뀌었다. 변경이 커밋된 뒤에 부른다. */
    public void evict(Long userId) {
        invalidations.incrementAndGet();
        entries.remove(userId);
    }
}
