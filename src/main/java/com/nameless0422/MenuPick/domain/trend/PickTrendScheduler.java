package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.common.logging.TraceIdFilter;
import com.nameless0422.MenuPick.common.logging.TraceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 집단 통계를 하루 한 번 다시 계산한다.
 *
 * <h2>왜 04:10인가</h2>
 *
 * <p>트래픽이 가장 적은 시간대이면서, <b>04:00의 탈퇴 유저 하드 삭제가 끝난 뒤</b>다
 * ({@code WithdrawnUserCleanupScheduler}). 순서가 뒤집히면 유예기간이 지나 곧 지워질 사용자의
 * 이력이 그날 통계에 한 번 더 섞인다. 10분이면 지금 규모에서는 충분히 여유롭고, 혹시 겹치더라도
 * 두 작업이 같은 행을 건드리지는 않으므로 깨지지는 않는다(집계 숫자가 하루치 어긋날 뿐이다).
 *
 * <h2>결과가 0건이어도 로그를 남긴다</h2>
 *
 * <p>{@code WithdrawnUserCleanupScheduler}와 같은 이유다. 조용한 날이 "돌았는데 문턱을 넘은 게
 * 없었다"인지 "아예 안 돌았다"인지 구분되지 않으면 관측이 아니다. 이 기능은 특히 그렇다 —
 * 화면에는 두 경우가 <b>똑같이</b> "표본이 모이면 보여드려요"로 보이기 때문에, 로그가 아니면
 * 스케줄러가 몇 주째 죽어 있어도 아무도 모른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PickTrendScheduler {

    private final PickTrendAggregationService aggregationService;

    @Scheduled(cron = "0 10 4 * * *", zone = "Asia/Seoul")
    public void recomputeTrends() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, TraceIds.newId());
        try {
            runRecompute();
        } finally {
            // 스케줄러 스레드는 재사용된다. 지우지 않으면 다음 작업 로그에 남의 식별자가 붙는다.
            MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
        }
    }

    private void runRecompute() {
        long startedAt = System.nanoTime();
        log.info("집단 통계 집계 시작");
        try {
            PickTrendAggregationService.Result result = aggregationService.recompute();
            if (!result.enabled()) {
                log.info("집단 통계 비활성 — 집계하지 않는다. {}ms", elapsedMs(startedAt));
                return;
            }
            if (result.categoryCount() == 0 && result.menuCount() == 0) {
                // 실패가 아니다. 다만 화면에서는 장애와 구분되지 않으므로 이유를 함께 적는다.
                log.info("집단 통계 집계 완료 — 최근 {}일 동안 {}명 이상이 선택·방문한 항목이 없어 저장할 것이 없다. "
                                + "화면에는 아무것도 표시되지 않는다. {}ms",
                        result.windowDays(), result.minUsers(), elapsedMs(startedAt));
                return;
            }
            log.info("집단 통계 집계 완료 — 카테고리 {}건, 메뉴 {}건 (최근 {}일, 최소 {}명), {}ms",
                    result.categoryCount(), result.menuCount(), result.windowDays(),
                    result.minUsers(), elapsedMs(startedAt));
        } catch (Exception e) {
            // 다시 던지지 않는다 — 스프링이 같은 예외를 한 번 더 남길 뿐이고, 다음 회차 스케줄은
            // 어느 쪽이든 유지된다. 실패하면 어제 값이 그대로 남는데, 하루 묵은 주간 순위는
            // 틀렸다고 할 만한 값이 아니라 그냥 두는 편이 화면을 비우는 것보다 낫다.
            log.error("집단 통계 집계 실패 — {}ms 지점에서 멈췄다. 트랜잭션이 직전 스냅샷을 보존한다.",
                    elapsedMs(startedAt), e);
        }
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
