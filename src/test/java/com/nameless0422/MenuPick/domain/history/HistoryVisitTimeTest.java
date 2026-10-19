package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryRequest;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoryVisitTimeTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    private final HistoryRepository repository = mock(HistoryRepository.class);
    private final HistoryService service = new HistoryService(repository, mock(RestaurantRepository.class), CLOCK);
    private History row;
    @BeforeEach void setup() {
        row = History.builder().recommendedAt(NOW.minusDays(2).withHour(14)).build();
        row.markVisited(NOW); row.updateMemo("유지");
        when(repository.findByIdAndUserId(5L, 1L)).thenReturn(Optional.of(row));
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-18T00:00:00", "2026-10-20T12:00:00", "2026-10-19T15:30:10.123456"})
    void acceptsInclusiveKstBoundsAndStoresSeconds(String input) {
        var at = LocalDateTime.parse(input);
        var result = service.updateVisitTime(1L, 5L, new HistoryRequest.VisitTimeRequest(at, 0L));
        assertThat(result.visitedAt()).isEqualTo(at.withNano(0));
        assertThat(result.earliestVisitAt()).isEqualTo(LocalDateTime.of(2026,10,18,0,0));
        assertThat(result.latestVisitAt()).isEqualTo(NOW);
        assertThat(row.getMemo()).isEqualTo("유지");
        verify(repository).flush();
    }
    @ParameterizedTest @ValueSource(strings={"2026-10-17T23:59:59", "2026-10-20T12:00:01"})
    void rejectsOutsideBoundsWithoutChangingState(String input) {
        assertThatThrownBy(() -> service.updateVisitTime(1L, 5L, new HistoryRequest.VisitTimeRequest(LocalDateTime.parse(input),0L)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
        assertThat(row.getVisitedAt()).isEqualTo(NOW);
        verify(repository, never()).flush();
    }
    @Test void doesNotTurnUnvisitedRecordIntoVisit() {
        row.unmarkVisited();
        assertThatThrownBy(() -> service.updateVisitTime(1L,5L,new HistoryRequest.VisitTimeRequest(NOW,0L)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.HISTORY_NOT_VISITED);
        assertThat(row.isVisited()).isFalse(); assertThat(row.getVisitedAt()).isNull();
    }
}
