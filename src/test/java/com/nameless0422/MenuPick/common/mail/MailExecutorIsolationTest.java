package com.nameless0422.MenuPick.common.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.task.TaskExecutor;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메일 풀이 애플리케이션 공용 실행기와 분리돼 있는지 지킨다.
 *
 * <p>Boot의 {@code applicationTaskExecutor} 자동설정은 컨텍스트에 {@code Executor} 빈이 없을 때만
 * 동작한다. 메일 풀을 추가하면 그 조건이 깨져 자동설정이 꺼지고, 그러면 실행기를 지정하지 않은
 * {@code @Async}가 전부 메일 풀(최대 4스레드)로 흘러든다. 메일과 무관한 작업이 인증 메일 발송을
 * 굶기는 상황인데, 코드 어디에도 그렇게 하겠다고 적힌 곳이 없어 알아채기 어렵다.
 *
 * <p>{@code spring.task.execution.mode=force}가 이를 막는다. 이 테스트는 그 설정이 조용히
 * 사라졌을 때 알려주는 역할을 한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MailExecutorIsolationTest {

    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("메일 풀과 별개로 Boot의 공용 실행기가 살아 있다")
    void applicationTaskExecutorSurvives() {
        assertThat(context.containsBean("applicationTaskExecutor"))
                .as("공용 실행기가 없으면 실행기를 지정하지 않은 @Async가 메일 풀을 잠식한다")
                .isTrue();
    }

    @Test
    @DisplayName("실행기 이름을 지정하지 않은 @Async는 메일 풀이 아닌 공용 실행기로 간다")
    void unqualifiedAsyncDoesNotUseMailPool() {
        // @Async가 이름 없이 고르는 기본 실행기는 "taskExecutor"라는 이름으로 등록된 빈이다.
        Executor applicationExecutor = context.getBean("applicationTaskExecutor", Executor.class);
        Executor mailExecutor = context.getBean(MailAsyncConfig.EXECUTOR, TaskExecutor.class);

        assertThat(applicationExecutor).isNotSameAs(mailExecutor);
    }
}
