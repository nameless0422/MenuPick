package com.nameless0422.MenuPick.domain.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnUserCleanupScheduler {

    private final UserRepository userRepository;
    private final UserHardDeleteService userHardDeleteService;
    private final Clock clock;

    /** cron은 KST 기준으로 해석한다 — Clock과 기준 시간대를 일치시킨다. */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
    public void purgeExpiredWithdrawnUsers() {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(User.WITHDRAW_GRACE_PERIOD_DAYS);
        List<User> expired = userRepository.findAllByDeletedAtBefore(cutoff);

        int purged = 0;
        for (User user : expired) {
            try {
                userHardDeleteService.purge(user.getId());
                purged++;
            } catch (Exception e) {
                log.error("탈퇴 유저 하드 삭제 실패: userId={}", user.getId(), e);
            }
        }

        if (!expired.isEmpty()) {
            log.info("유예기간 경과 탈퇴 유저 하드 삭제: 대상 {}명, 성공 {}명", expired.size(), purged);
        }
    }
}
