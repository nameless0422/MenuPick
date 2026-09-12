package com.nameless0422.MenuPick.domain.trend;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PickTrendSchedulerTest {

    @Mock private PickTrendAggregationService aggregationService;
    @InjectMocks private PickTrendScheduler scheduler;

    @Test
    @DisplayName("회차마다 집계를 한 번 돌린다")
    void runsAggregation() {
        given(aggregationService.recompute()).willReturn(
                new PickTrendAggregationService.Result(true, 3, 2, 7, 5, LocalDateTime.now()));

        scheduler.recomputeTrends();

        verify(aggregationService).recompute();
    }

    /**
     * 집계가 터져도 스케줄러 스레드로 예외를 올리지 않는다. 올리면 스프링이 같은 예외를 한 번 더
     * 남길 뿐이고, 실패한 회차는 어제 값이 그대로 남는다 — 하루 묵은 주간 순위는 화면을 비우는
     * 것보다 낫다.
     */
    @Test
    @DisplayName("집계가 실패해도 예외를 밖으로 내보내지 않는다")
    void swallowsFailures() {
        willThrow(new RuntimeException("DB 끊김")).given(aggregationService).recompute();

        assertThatCode(() -> scheduler.recomputeTrends()).doesNotThrowAnyException();
    }

    /**
     * 스케줄러 스레드는 재사용된다. 지우지 않으면 다음 작업의 로그에 이 회차의 식별자가 붙어,
     * 로그를 식별자로 훑을 때 남의 줄이 섞인다. 실패 경로에서도 지워져야 한다 — 거기가
     * 빠뜨리기 쉬운 쪽이다.
     */
    @Test
    @DisplayName("실패해도 MDC의 상관관계 식별자를 남기지 않는다")
    void clearsMdcEvenOnFailure() {
        willThrow(new RuntimeException("DB 끊김")).given(aggregationService).recompute();

        scheduler.recomputeTrends();

        assertThat(MDC.get("traceId")).isNull();
    }

    /**
     * 04:10인 것은 04:00의 탈퇴 유저 하드 삭제가 끝난 뒤여야 하기 때문이다. 순서가 뒤집히면
     * 곧 지워질 사용자의 이력이 그날 통계에 한 번 더 섞인다. cron을 손대면 여기서 걸린다.
     */
    @Test
    @DisplayName("탈퇴 정리(04:00) 뒤인 KST 04:10에 돈다")
    void runsAfterWithdrawnUserCleanup() throws Exception {
        Method method = PickTrendScheduler.class.getMethod("recomputeTrends");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.cron()).isEqualTo("0 10 4 * * *");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
