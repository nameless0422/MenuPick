package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.domain.pick.dto.PickPresetRequest;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빠른 픽 10개 상한이 <b>동시 생성에서도</b> 지켜지는가({@code docs/PickPresetDesign.md} 4절).
 *
 * <h2>왜 별도 테스트인가</h2>
 *
 * <p>개수 제한은 DB가 표현할 수 없다. UNIQUE는 이름에만 걸려 있고 "행이 10개를 넘지 않는다"는
 * 제약은 스키마에 없다. 그래서 애플리케이션이 세는데, <b>count-then-insert는 그대로 두면
 * 경쟁 상태다</b> — 9개인 상태에서 두 요청이 동시에 세면 둘 다 "9개"를 보고 둘 다 넣어 11개가
 * 된다. 단일 스레드 테스트로는 절대 드러나지 않는다.
 *
 * <p>직렬화는 {@code UserRepository.findByIdForUpdate}가 <b>users 행</b>을 잠가서 한다.
 * 자식을 세면서 잠그는 방식({@code SELECT COUNT(*) ... FOR UPDATE})으로 먼저 짰다가
 * 이 테스트에서 12건이 저장되는 것을 보고 바꿨다 — 그건 이미 있는 행만 잠그고
 * 새 INSERT를 막지 못한다.
 *
 * <p>진짜 동시 트랜잭션이 필요해 {@code @DataJpaTest}(테스트 트랜잭션 하나로 감싸고 롤백)가
 * 아니라 전체 컨텍스트를 띄운다. 각 스레드가 자기 트랜잭션을 연다.
 */
@SpringBootTest
@ActiveProfiles("integration")
class PickPresetConcurrencyTest extends AbstractIntegrationTest {

    @Autowired private PickPresetService presetService;
    @Autowired private PickPresetRepository presetRepository;
    @Autowired private UserRepository userRepository;

    /**
     * 여러 판을 돈다. 경쟁은 타이밍에 의존해서 <b>한 판으로는 놓칠 수 있다</b> —
     * 실제로 잠금을 뺀 채 한 판만 돌렸을 때 통과한 적이 있다. 판마다 새 사용자를 쓰고
     * 한 판이라도 상한을 넘으면 실패로 본다.
     */
    private static final int ROUNDS = 5;
    private static final int ATTEMPTS_PER_ROUND = 24;

    @Test
    @DisplayName("동시에 여러 판을 돌려도 10개를 넘지 않는다")
    void concurrentCreate_respectsLimit() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(ATTEMPTS_PER_ROUND);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                User subject = userRepository.save(User.builder()
                        .email("race" + System.nanoTime() + "@example.com")
                        .nickname("경쟁" + System.nanoTime())
                        .build());

                CountDownLatch startLine = new CountDownLatch(1);
                List<Future<?>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < ATTEMPTS_PER_ROUND; i++) {
                    final int index = i;
                    futures.add(pool.submit(() -> {
                        // 모두 같은 순간에 출발해야 경쟁이 실제로 일어난다.
                        startLine.await(5, TimeUnit.SECONDS);
                        try {
                            presetService.create(subject.getId(), new PickPresetRequest.Create(
                                    "프리셋" + index, Set.of(), Set.of(), Set.of(), null));
                        } catch (RuntimeException expected) {
                            // 상한 초과(409)거나 잠금 경합으로 밀린 요청. 둘 다 정상이다.
                        }
                        return null;
                    }));
                }
                startLine.countDown();
                for (Future<?> future : futures) {
                    future.get(30, TimeUnit.SECONDS);
                }

                long stored = presetRepository.findAllByUserIdWithConditions(subject.getId()).size();
                assertThat(stored)
                        .as("%d번째 판에서 %d건이 저장됐다 — 잠금이 없으면 여기가 10을 넘는다",
                                round + 1, stored)
                        .isLessThanOrEqualTo(PickPreset.MAX_PER_USER);
                assertThat(stored)
                        .as("전부 실패해도 안 된다 — 잠금이 과하게 막고 있다는 뜻이다")
                        .isPositive();
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
