package com.nameless0422.MenuPick.domain.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WithdrawnUserCleanupSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** KST 자정 직후 — UTC로 계산하면 하루 어긋나는 시각. */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 0, 30, 0, 0, KST).toInstant(), KST);

    @Mock private UserRepository userRepository;
    @Mock private UserHardDeleteService userHardDeleteService;

    private WithdrawnUserCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new WithdrawnUserCleanupScheduler(userRepository, userHardDeleteService, FIXED_CLOCK);
    }

    @Test
    @DisplayName("유예기간 컷오프를 주입된 Clock(KST) 기준으로 계산한다")
    void cutoffIsBasedOnClock() {
        given(userRepository.findAllByDeletedAtBefore(LocalDateTime.of(2025, 12, 16, 0, 30)))
                .willReturn(List.of());

        scheduler.purgeExpiredWithdrawnUsers();

        var captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).findAllByDeletedAtBefore(captor.capture());
        // 2026-01-15 00:30 KST - 30일
        assertThat(captor.getValue()).isEqualTo(LocalDateTime.of(2025, 12, 16, 0, 30));
    }

    @Test
    @DisplayName("대상 유저를 각각 하드 삭제하고, 일부가 실패해도 나머지를 계속 처리한다")
    void purgesEachUserAndContinuesOnFailure() {
        User failing = user(1L);
        User ok = user(2L);
        given(userRepository.findAllByDeletedAtBefore(LocalDateTime.of(2025, 12, 16, 0, 30)))
                .willReturn(List.of(failing, ok));
        willThrow(new RuntimeException("boom")).given(userHardDeleteService).purge(1L);

        scheduler.purgeExpiredWithdrawnUsers();

        verify(userHardDeleteService).purge(1L);
        verify(userHardDeleteService).purge(2L);
    }

    private User user(Long id) {
        User user = User.builder().email(id + "@test.com").nickname("탈퇴자" + id).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
