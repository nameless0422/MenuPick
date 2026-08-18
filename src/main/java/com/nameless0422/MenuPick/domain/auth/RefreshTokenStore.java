package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Refresh Token의 Redis 보관소.
 *
 * <p>키 포맷({@code refresh:{userId}})과 Redis 장애 처리(503 매핑)를 한 곳에 모아
 * AuthService·UserHardDeleteService가 같은 규칙을 공유하게 한다.
 *
 * <p>유효한 토큰은 사용자당 하나지만, 회전 직전 토큰만 짧은 유예 키
 * ({@code refresh:{userId}:prev})에 남긴다 — 아래 {@link #GRACE_MILLIS} 참고.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:";
    private static final String GRACE_SUFFIX = ":prev";

    /**
     * 회전에 성공한 직후, 방금 밀려난 토큰을 이만큼 더 받아준다.
     *
     * <p>유예 창이 없으면 탭을 두 개 열어 둔 것만으로 양쪽 세션이 끊긴다. 프론트는 앱이
     * 뜰 때마다 {@code /auth/refresh}를 부르고 탭 사이에 중복 제거가 없어서, 탭1이 회전에
     * 성공하는 순간 탭2가 들고 있던 쿠키는 즉시 "저장값과 다른 토큰"이 된다. 그러면 재사용
     * 탐지가 걸려 체인 전체가 삭제되고 두 탭이 함께 로그아웃된다(폰+PC 동시 사용도 같다).
     *
     * <p>그래서 직전 토큰이 제시되면 탈취로 보지 않고 현재 유효한 토큰을 그대로 돌려준다 —
     * 새로 회전시키면 두 탭이 서로를 계속 밀어내며 같은 문제가 반복된다.
     *
     * <p>길이는 "같은 부팅에서 거의 동시에 날아온 요청"만 덮을 만큼으로 잡는다. 길게 잡을수록
     * 탈취된 옛 토큰이 살아 있는 시간이 그대로 늘어난다. 30초면 네트워크 지연·재시도까지
     * 흡수하면서 창이 충분히 짧다.
     */
    static final long GRACE_MILLIS = 30_000L;

    /**
     * Refresh Token 회전을 compare-and-swap으로 원자화한다.
     *
     * <p>GET→비교→SET을 애플리케이션에서 나눠 수행하면 동시에 도착한 두 refresh 요청이
     * 모두 "일치" 판정을 받아 두 개의 유효한 토큰이 생기는 창이 열린다. Lua 스크립트는
     * Redis에서 단일 원자 실행되므로 승자는 항상 하나다. 유예 창을 붙이면서도 이 성질을
     * 지켜야 하므로, 유예 키의 기록·조회·삭제를 모두 같은 스크립트 안에 둔다 —
     * 두 키를 애플리케이션에서 따로 만지면 "회전은 됐는데 유예 키는 안 남은" 상태가
     * 생겨 CAS로 막으려던 창이 그대로 되살아난다.
     *
     * <p>저장값과도 유예값과도 다르면(= 유예 창 밖의 옛 토큰이거나 탈취된 토큰) 두 키를
     * 모두 지워 해당 유저의 토큰 체인 전체를 무효화한다 — 기존 탈취 대응 정책 그대로다.
     *
     * <p>두 키를 한 스크립트에서 다루므로 Redis Cluster로 옮기게 되면 같은 슬롯에 놓아야 한다
     * ({@code refresh:{userId}} 형태의 해시 태그). 지금은 단일 노드라 그대로 둔다 —
     * 키 이름을 미리 바꾸면 배포 순간 살아 있는 모든 세션이 끊긴다.
     *
     * @return 앞으로 쓸 토큰(회전 성공 시 새 토큰, 유예 창 안이면 현재 유효한 토큰),
     *         거부면 nil
     */
    private static final DefaultRedisScript<String> ROTATE_SCRIPT = new DefaultRedisScript<>(
            """
            local current = redis.call('GET', KEYS[1])
            if current == ARGV[1] then
              redis.call('SET', KEYS[2], ARGV[1], 'PX', ARGV[4])
              redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
              return ARGV[2]
            end
            if current and redis.call('GET', KEYS[2]) == ARGV[1] then
              return current
            end
            redis.call('DEL', KEYS[1], KEYS[2])
            return false
            """,
            String.class
    );

    /**
     * 새 체인을 심으면서 유예 키를 함께 버린다.
     *
     * <p>둘을 나눠 실행하면 그 사이에 옛 토큰이 유예 창으로 들어와 방금 발급한 토큰을
     * 그대로 받아 간다. 비밀번호 재설정처럼 "다른 세션을 반드시 끊어야 하는" 흐름이
     * 이 경로를 쓰므로, 남은 유예 값 하나로 목적이 통째로 무너진다.
     */
    private static final DefaultRedisScript<Long> SAVE_SCRIPT = new DefaultRedisScript<>(
            """
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            redis.call('DEL', KEYS[2])
            return 1
            """,
            Long.class
    );

    private final StringRedisTemplate redisTemplate;

    /** 신규 로그인 시 Refresh Token을 저장한다(기존 체인은 유예 값까지 폐기). */
    public void save(Long userId, String refreshToken, long ttlMillis) {
        execute(() -> redisTemplate.execute(
                SAVE_SCRIPT,
                List.of(key(userId), graceKey(userId)),
                refreshToken, String.valueOf(ttlMillis)));
    }

    /**
     * 제시된 토큰을 검증하고 체인을 한 칸 굴린다.
     *
     * @return 클라이언트가 앞으로 써야 할 Refresh Token. 비어 있으면 거부이며,
     *         이때 저장된 체인은 삭제된 상태다.
     */
    public Optional<String> rotate(Long userId, String presentedToken, String newToken, long ttlMillis) {
        String issued = execute(() -> redisTemplate.execute(
                ROTATE_SCRIPT,
                List.of(key(userId), graceKey(userId)),
                presentedToken, newToken, String.valueOf(ttlMillis), String.valueOf(GRACE_MILLIS)));
        return Optional.ofNullable(issued);
    }

    public void delete(Long userId) {
        execute(() -> redisTemplate.delete(List.of(key(userId), graceKey(userId))));
    }

    /**
     * Redis 장애(연결 실패·타임아웃 등)를 503으로 변환한다.
     * 그대로 두면 DataAccessException이 전역 핸들러의 Exception 분기로 떨어져 500이 된다.
     */
    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.error("Refresh Token Redis 작업 실패: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.REDIS_UNAVAILABLE);
        }
    }

    private String key(Long userId) {
        return KEY_PREFIX + userId;
    }

    private String graceKey(Long userId) {
        return key(userId) + GRACE_SUFFIX;
    }
}
