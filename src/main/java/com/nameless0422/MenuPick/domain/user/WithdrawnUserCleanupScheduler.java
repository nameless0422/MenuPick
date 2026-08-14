package com.nameless0422.MenuPick.domain.user;

import com.nameless0422.MenuPick.common.logging.TraceIdFilter;
import com.nameless0422.MenuPick.common.logging.TraceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnUserCleanupScheduler {

    private final UserRepository userRepository;
    private final UserHardDeleteService userHardDeleteService;
    private final Clock clock;

    /**
     * 유예기간이 지난 탈퇴 유저를 하드 삭제한다. cron은 KST 기준으로 해석한다 — Clock과 기준 시간대를 맞춘다.
     *
     * <p><b>대상이 0명이어도 로그를 남긴다.</b> 조용한 날이 "돌았는데 지울 게 없었다"인지 "아예 안 돌았다"인지
     * 구분되지 않으면 관측이 아니다. 안 도는 경우는 실제로 생긴다 — 04:00에 컨테이너가 떠 있지 않았거나,
     * 재배포가 그 시각에 겹쳤거나, cron/timezone이 어긋났거나. 그 상태는 유예 30일이 지난 개인정보가
     * 계속 남아 있다는 뜻이라([PrivacyReview.md]) 조용히 넘어가면 안 된다.
     *
     * <p>시작·완료 두 줄로 남기는 이유는 완료 줄만 있으면 실행 도중 프로세스가 죽었을 때 흔적이 아예
     * 없기 때문이다. 하루 두 줄은 로그 상한(컨테이너당 20MB×5)에 영향이 없다.
     *
     * <p>회차마다 MDC에 상관관계 식별자를 심는다. 개별 삭제 실패는 유저별로 따로 찍히는데, 식별자가 없으면
     * 그 줄들이 어느 회차의 것인지 시간대로 추정해야 한다. 요청 경로와 같은 형식({@link TraceIds})이라
     * 로그를 한 눈금으로 훑을 수 있다.
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void purgeExpiredWithdrawnUsers() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, TraceIds.newId());
        try {
            runPurge();
        } finally {
            // 스케줄러 스레드는 재사용된다. 지우지 않으면 다음 작업 로그에 남의 식별자가 붙는다.
            MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
        }
    }

    private void runPurge() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(User.WITHDRAW_GRACE_PERIOD_DAYS);
        long startedAt = System.nanoTime();
        log.info("탈퇴 유저 하드 삭제 시작 — {} 이전에 탈퇴한 유저가 대상이다.", cutoff);

        int targets;
        int purged = 0;
        try {
            List<User> expired = userRepository.findAllByDeletedAtBefore(cutoff);
            targets = expired.size();

            for (User user : expired) {
                try {
                    userHardDeleteService.purge(user.getId());
                    purged++;
                    // 오삭제 신고가 들어왔을 때 "언제 지워졌나"를 찾는 유일한 단서다 — 행이 이미 없어
                    // DB로는 확인할 수 없고, 이 줄의 시각을 기준으로 그 이전 백업에서 복원한다
                    // (Planning.md 7.5 오삭제 복구 절차). 하루 대상 수가 적어 줄당 로그로 남겨도 부담이 없다.
                    log.info("탈퇴 유저 하드 삭제: userId={}", user.getId());
                } catch (Exception e) {
                    log.error("탈퇴 유저 하드 삭제 실패: userId={}", user.getId(), e);
                }
            }
        } catch (Exception e) {
            // 개별 삭제 실패는 위에서 잡히므로, 여기까지 오는 건 대상 조회 실패처럼 회차 전체가 무너진 경우다.
            // 다시 던지지 않는다 — 스프링이 같은 예외를 한 번 더 남길 뿐이고, 다음 회차 스케줄은 어느 쪽이든 유지된다.
            log.error("탈퇴 유저 하드 삭제 중단 — {}ms 지점에서 실패했다. 이번 회차는 정리를 마치지 못했다.",
                    elapsedMs(startedAt), e);
            return;
        }

        int failed = targets - purged;
        if (failed > 0) {
            log.warn("탈퇴 유저 하드 삭제 완료 — 대상 {}명, 성공 {}명, 실패 {}명, {}ms. "
                            + "실패분은 유예기간이 지난 상태 그대로라 다음 회차에서 다시 시도된다.",
                    targets, purged, failed, elapsedMs(startedAt));
            return;
        }
        log.info("탈퇴 유저 하드 삭제 완료 — 대상 {}명, 성공 {}명, {}ms", targets, purged, elapsedMs(startedAt));
    }

    private long elapsedMs(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
