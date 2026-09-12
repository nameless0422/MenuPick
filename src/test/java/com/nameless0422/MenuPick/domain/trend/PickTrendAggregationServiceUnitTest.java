package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PickTrendAggregationServiceUnitTest {

    @Test
    void disabledRecomputeDoesNotScanOrLockTheDatabase() {
        HistoryRepository histories = mock(HistoryRepository.class);
        PickTrendRepository trends = mock(PickTrendRepository.class);
        PickTrendSnapshotRepository snapshots = mock(PickTrendSnapshotRepository.class);
        PickTrendAggregationService service = new PickTrendAggregationService(
                histories, trends, snapshots, new PickTrendProperties(false, 7, 5, 10), Clock.systemUTC());

        assertThat(service.recompute().enabled()).isFalse();
        verifyNoInteractions(histories, trends, snapshots);
    }
}
