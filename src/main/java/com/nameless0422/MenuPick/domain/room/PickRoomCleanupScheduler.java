package com.nameless0422.MenuPick.domain.room;

import com.nameless0422.MenuPick.common.logging.TraceIdFilter;
import com.nameless0422.MenuPick.common.logging.TraceIds;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 만료된 방을 지운다.
 *
 * <p>만료된 방은 조회에서 이미 404다. 그래도 지우는 이유는 <b>참가자의 제외 기록</b> 때문이다.
 * 계정과 무관한 랜덤 값이라 개인정보는 아니지만, 쓸모가 끝난 뒤에도 남겨 둘 이유가 없다
 * ({@code docs/PrivacyReview.md}의 "필요한 동안만 둔다"). 방을 지우면 메뉴 사본과 제외가
 * FK cascade로 함께 사라진다.
 *
 * <p>04:20에 도는 것은 앞선 두 작업(04:00 탈퇴 정리, 04:10 집단 통계) 뒤에 두기 위해서다.
 * 셋이 같은 DB를 건드리므로 한 시각에 몰아 두지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PickRoomCleanupScheduler {

    /** 만료 뒤에도 잠시 남겨 둔다 — 방금 끝난 방을 새로고침했을 때 404 대신 결과가 보이게. */
    private static final int GRACE_HOURS = 24;

    private final PickRoomRepository roomRepository;
    private final Clock clock;

    /**
     * {@code @Transactional}이 여기 붙어 있다. 아래 {@code runPurge}에 붙이면 같은 빈 안에서
     * 부르는 호출이라 프록시를 거치지 않아 트랜잭션이 아예 시작되지 않는다 — 조용히 무효가 되는
     * 종류의 실수다. 스케줄러가 부르는 이 메서드는 프록시를 거친다.
     */
    @Scheduled(cron = "0 20 4 * * *", zone = "Asia/Seoul")
    @Transactional
    public void purgeExpiredRooms() {
        MDC.put(TraceIdFilter.TRACE_ID_MDC_KEY, TraceIds.newId());
        try {
            runPurge();
        } finally {
            // 스케줄러 스레드는 재사용된다. 지우지 않으면 다음 작업 로그에 남의 식별자가 붙는다.
            MDC.remove(TraceIdFilter.TRACE_ID_MDC_KEY);
        }
    }

    private void runPurge() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusHours(GRACE_HOURS);
        try {
            List<PickRoom> expired = roomRepository.findAllByExpiresAtBefore(cutoff);
            roomRepository.deleteAll(expired);
            // 0건도 남긴다 — 조용한 날이 "지울 게 없었다"인지 "안 돌았다"인지 구분되어야 한다.
            log.info("만료된 같이 뽑기 방 정리 — {}건 삭제 (기준 {})", expired.size(), cutoff);
        } catch (Exception e) {
            // 다시 던지지 않는다. 다음 회차가 같은 대상을 다시 지운다.
            log.error("만료된 같이 뽑기 방 정리 실패 — 이번 회차는 건너뛴다.", e);
        }
    }
}
