package com.nameless0422.MenuPick.common.mail;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 메일 발송을 요청 스레드에서 떼어내기 위한 전용 실행기.
 *
 * <p>SMTP 왕복은 메일 서버 사정에 따라 수 초까지 늘어나고, 그동안 가입·비밀번호 재설정 응답이
 * 그대로 묶인다. 발송 자체는 응답에 담을 결과가 없으므로 이 풀로 넘긴다.
 *
 * <p><b>전용 풀을 두는 이유</b>: {@code @Async}에 실행기를 지정하지 않으면 스레드를 작업마다
 * 새로 만드는 실행기가 쓰인다. 메일이 몰리는 순간(대량 가입, 재발송 폭주) 스레드 수에 상한이 없어
 * 애플리케이션 전체가 같이 무너진다. 코어 2 / 최대 4 / 큐 100은 인증 메일 정도의 트래픽에
 * 맞춘 값으로, 메일이 밀려도 다른 요청 처리에서 뺏어가는 자원을 이 범위로 묶어둔다.
 *
 * <p><b>주의</b>: Boot의 {@code applicationTaskExecutor} 자동설정은 컨텍스트에 {@code Executor}
 * 빈이 하나도 없을 때만 동작한다. 이 빈이 생기면 자동설정이 꺼지고, 그러면 실행기를 지정하지 않은
 * {@code @Async}가 전부 이 메일 풀로 흘러든다(실측 확인).
 * 그래서 {@code application.yml}에 {@code spring.task.execution.mode=force}를 둬 공용 실행기를
 * 되살려 두 풀을 분리했다. {@code MailExecutorIsolationTest}가 그 설정이 사라지면 알려준다.
 * 그래도 메일 외의 비동기 작업을 추가할 때는 실행기 이름을 명시하는 편이 안전하다.
 *
 * @see #EXECUTOR
 */
@Configuration
@EnableAsync
public class MailAsyncConfig {

    /**
     * {@code @Async} 대상이 이 실행기를 지목할 때 쓴다.
     *
     * <p>문자열을 양쪽에 따로 적으면 이름이 바뀔 때 조용히 어긋난다. 게다가 어긋난 이름은 기동을
     * 막지 않는다 — Spring은 경고 한 줄만 남기고 기본 실행기로 넘어가므로, 바운드 풀을 쓰고 있다고
     * 착각한 채 운영에 나가고 첫 발송 시점에야 드러난다.
     */
    public static final String EXECUTOR = "mailTaskExecutor";

    @Bean(EXECUTOR)
    public ThreadPoolTaskExecutor mailTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("mail-");

        // 큐와 풀이 모두 차면 호출 스레드가 직접 발송한다.
        // 기본값인 AbortPolicy는 작업을 버린다 — 인증 메일이 사라지면 사용자는 링크를 영영 못 받고
        // 가입을 끝낼 수 없는데, 서버 쪽에는 예외 한 줄 말고 남는 게 없다.
        // CallerRunsPolicy면 최악의 경우라도 지금과 같은 동기 발송으로 되돌아갈 뿐이고,
        // 호출 스레드가 발송에 묶이는 동안 유입 속도가 자연히 눌리는 효과도 있다.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 종료 시 큐에 남은 발송을 마치고 내려간다. 기본값(false)이면 배포할 때마다 대기 중이던
        // 인증 메일이 조용히 사라지고, 그 직전에 가입한 사용자는 아무 안내 없이 링크를 못 받는다.
        // 버리지 않겠다는 판단은 위 CallerRunsPolicy와 같은 것이다.
        // 20초 상한을 두는 이유는 무한정 기다리면 배포가 멎기 때문이다 — 한 건당 SMTP 타임아웃
        // (연결 5초 + 응답 10초)을 감안하면 진행 중인 발송을 끝낼 만한 여유다.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);

        executor.setTaskDecorator(mdcPropagatingDecorator());

        // initialize()는 호출하지 않는다. ThreadPoolTaskExecutor는 InitializingBean이라
        // 컨테이너가 afterPropertiesSet()에서 부른다. 여기서 미리 부르면 실제 풀이 두 번 만들어진다.
        return executor;
    }

    /**
     * 호출 스레드의 MDC를 풀 스레드로 옮기는 데코레이터.
     *
     * <p>{@code TraceIdFilter}가 심는 traceId/userId는 ThreadLocal이라 풀 스레드로 넘어가지 않는다.
     * 그대로 두면 메일 발송 실패 로그에 traceId가 비어, 어느 요청에서 난 실패인지 대조할 수 없다.
     * 원인 추적이 필요한 순간에만 정확히 없어지는 셈이라 비동기로 옮기는 대가로는 너무 크다.
     */
    private TaskDecorator mdcPropagatingDecorator() {
        return runnable -> {
            // 데코레이터 자체는 작업을 제출하는 시점, 즉 아직 호출 스레드 위에서 실행된다.
            // 스냅샷을 여기서 떠야 요청의 MDC가 잡힌다.
            Map<String, String> submitterContext = MDC.getCopyOfContextMap();

            return () -> {
                // 실행 직전 컨텍스트를 보관했다가 끝나면 되돌린다. 단순히 clear()로 끝내지 않는 이유는
                // CallerRunsPolicy 때문이다. 포화 시 이 Runnable은 풀 스레드가 아니라 요청 스레드에서
                // 그대로 실행되므로, clear()하면 남은 요청 처리 구간의 로그에서 traceId가 통째로 날아간다.
                // 되돌리기 방식이면 풀 스레드(보관값 없음)와 요청 스레드(보관값 있음) 양쪽에서 옳게 동작한다.
                Map<String, String> previousContext = MDC.getCopyOfContextMap();
                replaceContext(submitterContext);
                try {
                    runnable.run();
                } finally {
                    // 풀 스레드는 재사용된다. 정리를 빠뜨리면 다음 메일 작업 로그에 남의 traceId가 붙는다.
                    replaceContext(previousContext);
                }
            };
        };
    }

    /** {@code MDC.setContextMap()}은 null을 받지 않으므로 비어 있는 경우를 갈라 준다. */
    private static void replaceContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
