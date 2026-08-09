package com.nameless0422.MenuPick.domain.user;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class WithdrawnUserCleanupScheduler {

    private final UserRepository userRepository;
    private final UserHardDeleteService userHardDeleteService;

    @Scheduled(cron = "0 0 4 * * *")
    public void purgeExpiredWithdrawnUsers() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(User.WITHDRAW_GRACE_PERIOD_DAYS);
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
