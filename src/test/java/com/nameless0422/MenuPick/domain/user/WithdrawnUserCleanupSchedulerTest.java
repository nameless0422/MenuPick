package com.nameless0422.MenuPick.domain.user;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WithdrawnUserCleanupSchedulerTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** KST 자정 직후 — UTC로 계산하면 하루 어긋나는 시각. */
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 0, 30, 0, 0, KST).toInstant(), KST);

    private static final LocalDateTime CUTOFF = LocalDateTime.of(2025, 12, 16, 0, 30);

    @Mock private UserRepository userRepository;
    @Mock private UserHardDeleteService userHardDeleteService;

    private WithdrawnUserCleanupScheduler scheduler;
    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void setUp() {
        scheduler = new WithdrawnUserCleanupScheduler(userRepository, userHardDeleteService, FIXED_CLOCK);

        logger = (Logger) LoggerFactory.getLogger(WithdrawnUserCleanupScheduler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    private List<String> loggedLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private ILoggingEvent lastEvent() {
        assertThat(appender.list).isNotEmpty();
        return appender.list.get(appender.list.size() - 1);
    }

    @Test
    @DisplayName("유예기간 컷오프를 주입된 Clock(KST) 기준으로 계산한다")
    void cutoffIsBasedOnClock() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willReturn(List.of());

        scheduler.purgeExpiredWithdrawnUsers();

        var captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(userRepository).findAllByDeletedAtBefore(captor.capture());
        // 2026-01-15 00:30 KST - 30일
        assertThat(captor.getValue()).isEqualTo(CUTOFF);
    }

    @Test
    @DisplayName("대상 유저를 각각 하드 삭제하고, 일부가 실패해도 나머지를 계속 처리한다")
    void purgesEachUserAndContinuesOnFailure() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willReturn(List.of(user(1L), user(2L)));
        willThrow(new RuntimeException("boom")).given(userHardDeleteService).purge(1L);

        scheduler.purgeExpiredWithdrawnUsers();

        verify(userHardDeleteService).purge(1L);
        verify(userHardDeleteService).purge(2L);
    }

    @Test
    @DisplayName("지울 게 없어도 시작·완료를 남긴다 — 조용한 날이 '안 돈 날'과 구분돼야 한다")
    void logsBothLinesEvenWithNothingToPurge() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willReturn(List.of());

        scheduler.purgeExpiredWithdrawnUsers();

        assertThat(loggedLines())
                .as("한 줄도 안 남으면 배치가 안 돈 날과 구분할 수 없다")
                .hasSize(2);
        assertThat(loggedLines().get(0)).contains("시작", "2025-12-16T00:30");
        assertThat(loggedLines().get(1)).contains("완료", "대상 0명", "성공 0명");
        assertThat(lastEvent().getLevel()).isEqualTo(Level.INFO);
    }

    @Test
    @DisplayName("완료 줄에 대상·성공 수와 소요 시간이 들어간다")
    void completionLineCarriesCounts() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willReturn(List.of(user(1L), user(2L)));

        scheduler.purgeExpiredWithdrawnUsers();

        assertThat(lastEvent().getLevel()).isEqualTo(Level.INFO);
        assertThat(lastEvent().getFormattedMessage())
                .contains("대상 2명", "성공 2명")
                .containsPattern("\\d+ms");
    }

    @Test
    @DisplayName("삭제한 userId를 남긴다 — 오삭제 복구 절차가 이 줄에 의존한다")
    void logsEachDeletedUserId() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willReturn(List.of(user(7L), user(9L)));

        scheduler.purgeExpiredWithdrawnUsers();

        // 행이 이미 없어 DB로는 "언제 지워졌나"를 확인할 수 없다 — 백업 시점을 고르는 근거가 이 줄뿐이다.
        assertThat(loggedLines())
                .as("Planning.md 7.5가 약속한 복구 절차가 이 줄 없이는 성립하지 않는다")
                .anyMatch(line -> line.contains("userId=7"))
                .anyMatch(line -> line.contains("userId=9"));
    }

    @Test
    @DisplayName("일부가 실패하면 완료 줄을 WARN으로 올리고 실패 수를 적는다")
    void warnsWhenSomePurgesFail() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willReturn(List.of(user(1L), user(2L)));
        willThrow(new RuntimeException("boom")).given(userHardDeleteService).purge(1L);

        scheduler.purgeExpiredWithdrawnUsers();

        assertThat(lastEvent().getLevel())
                .as("INFO로 남으면 '전부 잘 지웠다'와 섞여 실패가 묻힌다")
                .isEqualTo(Level.WARN);
        assertThat(lastEvent().getFormattedMessage()).contains("대상 2명", "성공 1명", "실패 1명");
    }

    @Test
    @DisplayName("대상 조회가 실패하면 중단을 남기되 예외를 밖으로 던지지 않는다")
    void logsAbortWhenLookupFails() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willThrow(new RuntimeException("DB 다운"));

        assertThatCode(scheduler::purgeExpiredWithdrawnUsers).doesNotThrowAnyException();

        assertThat(lastEvent().getLevel()).isEqualTo(Level.ERROR);
        assertThat(lastEvent().getFormattedMessage()).contains("중단");
        assertThat(loggedLines())
                .as("정리를 못 했는데 '완료'가 남으면 로그가 거짓말을 한다")
                .noneMatch(line -> line.contains("완료"));
    }

    @Test
    @DisplayName("한 회차의 로그가 같은 traceId로 묶이고, 끝나면 MDC를 비운다")
    void runIsCorrelatedByTraceId() {
        given(userRepository.findAllByDeletedAtBefore(CUTOFF)).willReturn(List.of(user(1L)));
        willThrow(new RuntimeException("boom")).given(userHardDeleteService).purge(1L);

        scheduler.purgeExpiredWithdrawnUsers();

        List<String> traceIds = appender.list.stream()
                .map(event -> event.getMDCPropertyMap().get("traceId"))
                .toList();
        assertThat(traceIds)
                .as("유저별 실패 줄이 어느 회차의 것인지 시간대로 추정하게 두지 않는다")
                .doesNotContainNull()
                .hasSize(3)
                .containsOnly(traceIds.get(0));

        assertThat(MDC.get("traceId"))
                .as("스케줄러 스레드는 재사용된다 — 남겨두면 다음 작업 로그에 남의 식별자가 붙는다")
                .isNull();
    }

    private User user(Long id) {
        User user = User.builder().email(id + "@test.com").nickname("탈퇴자" + id).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
