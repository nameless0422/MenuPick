package com.nameless0422.MenuPick.common.mail;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.nameless0422.MenuPick.common.logging.TraceIdFilter.TRACE_ID_MDC_KEY;
import static com.nameless0422.MenuPick.common.logging.TraceIdFilter.USER_ID_MDC_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * 스프링 컨텍스트를 띄우지 않고 설정 클래스를 직접 생성해 실행기만 꺼내 쓴다.
 * 검증 대상이 풀 설정과 데코레이터뿐이라 컨텍스트가 필요 없고, 그만큼 빠르고 흔들리지 않는다.
 */
class MailAsyncConfigTest {

    /** 포화 테스트에서 작업이 영영 안 풀리는 일이 없도록 두는 상한. */
    private static final long BLOCK_TIMEOUT_SECONDS = 10;

    private ThreadPoolTaskExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new MailAsyncConfig().mailTaskExecutor();
        // 컨테이너 밖이라 afterPropertiesSet()이 불리지 않는다. 실제 풀은 여기서 만들어진다.
        executor.initialize();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
        MDC.clear();
    }

    @Test
    @DisplayName("호출 스레드의 MDC가 풀 스레드로 전파된다 - 메일 실패 로그를 요청과 대조할 수 있어야 한다")
    void mdcIsPropagatedToPoolThread() throws Exception {
        MDC.put(TRACE_ID_MDC_KEY, "trace-abc123");
        MDC.put(USER_ID_MDC_KEY, "42");

        Snapshot snapshot = runAndCapture();

        assertThat(snapshot.threadName()).startsWith("mail-");
        assertThat(snapshot.threadName()).isNotEqualTo(Thread.currentThread().getName());
        assertThat(snapshot.traceId()).isEqualTo("trace-abc123");
        assertThat(snapshot.userId()).isEqualTo("42");
    }

    @Test
    @DisplayName("작업이 끝난 풀 스레드에 MDC 잔여물이 남지 않는다 - 다음 작업에 남의 traceId가 붙으면 안 된다")
    void poolThreadMdcIsCleanedAfterTask() throws Exception {
        MDC.put(TRACE_ID_MDC_KEY, "trace-first");
        String contaminatedThread = runAndCapture().threadName();
        MDC.clear();

        // 같은 실행기에 MDC 없이 작업을 계속 던져, 앞선 작업이 돌았던 그 스레드가 재사용될 때까지 본다.
        // 코어 스레드가 2개라 두 번째 작업은 새 스레드로 갈 수 있어, 한 번만 던지면 재사용 상황을
        // 지나칠 수 있다(= 통과해도 아무것도 증명하지 못한다).
        boolean reused = false;
        for (int i = 0; i < 50 && !reused; i++) {
            Snapshot probe = runAndCapture();

            assertThat(probe.traceId()).isNull();
            assertThat(probe.userId()).isNull();
            assertThat(probe.contextMap()).isNullOrEmpty();

            reused = contaminatedThread.equals(probe.threadName());
        }

        assertThat(reused)
                .as("앞선 작업이 돌았던 스레드(%s)가 한 번도 재사용되지 않아 잔여물 여부를 확인하지 못했다",
                        contaminatedThread)
                .isTrue();
    }

    @Test
    @DisplayName("풀과 큐가 모두 차면 호출 스레드가 직접 실행한다 (CallerRunsPolicy) - 인증 메일을 버리지 않는다")
    void saturatedExecutor_runsTaskOnCallerThread() {
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> executingThread = new AtomicReference<>();

        try {
            // ThreadPoolExecutor는 코어(2)를 채우고 → 큐(100)를 채우고 → 최대(4)까지 늘린 뒤에야 거절한다.
            // 먼저 들어간 작업들이 래치에 걸려 큐를 비우지 않으므로 106번째가 아니라 105번째부터 거절된다.
            for (int i = 0; i < 104; i++) {
                executor.execute(() -> awaitQuietly(release));
            }

            executor.execute(() -> executingThread.set(Thread.currentThread().getName()));

            assertThat(executingThread.get())
                    .as("포화 시 작업이 버려지지 않고 호출 스레드에서 실행되어야 한다")
                    .isEqualTo(Thread.currentThread().getName());
            assertThat(executingThread.get()).doesNotStartWith("mail-");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("호출 스레드에서 대신 실행돼도(CallerRunsPolicy) 호출 스레드의 MDC는 그대로 남는다")
    void callerRunsPolicy_doesNotWipeCallerMdc() {
        MDC.put(TRACE_ID_MDC_KEY, "trace-caller");
        CountDownLatch release = new CountDownLatch(1);

        try {
            for (int i = 0; i < 104; i++) {
                executor.execute(() -> awaitQuietly(release));
            }

            executor.execute(() -> MDC.put(USER_ID_MDC_KEY, "작업이-심은-값"));

            // 데코레이터가 무조건 clear()로 끝냈다면 여기서 traceId가 날아가고,
            // 발송 이후 남은 요청 처리 구간의 로그가 전부 상관관계를 잃는다.
            assertThat(MDC.get(TRACE_ID_MDC_KEY)).isEqualTo("trace-caller");
            // 거절된 작업에도 데코레이터가 걸린다는 확인이기도 하다. 안 걸렸다면 작업이 심은 값이
            // 호출 스레드에 그대로 남는다 — 즉 포화 구간에서는 MDC 전파도 함께 빠진다는 뜻이 된다.
            assertThat(MDC.get(USER_ID_MDC_KEY)).isNull();
        } finally {
            release.countDown();
        }
    }

    // --- 헬퍼 ---

    /** 풀 스레드에서 관측한 실행 컨텍스트. */
    private record Snapshot(String threadName, String traceId, String userId, Map<String, String> contextMap) {}

    /** 작업 하나를 실행기에 넘기고 끝날 때까지 기다린 뒤, 그 안에서 본 MDC와 스레드를 돌려준다. */
    private Snapshot runAndCapture() throws Exception {
        Future<Snapshot> future = executor.submit(() -> new Snapshot(
                Thread.currentThread().getName(),
                MDC.get(TRACE_ID_MDC_KEY),
                MDC.get(USER_ID_MDC_KEY),
                MDC.getCopyOfContextMap()));
        return future.get(BLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(BLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                fail("포화 상태를 유지하던 작업이 제때 풀리지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
