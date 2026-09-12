package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "trends.enabled=true")
@ActiveProfiles("integration")
class PickTrendRecomputeTransactionIntegrationTest extends AbstractIntegrationTest {

    private static final PickTrendProperties SNAPSHOT_CONFIG =
            new PickTrendProperties(true, 7, 5, 10);

    @Autowired PickTrendAggregationService service;
    @MockitoSpyBean HistoryRepository histories;
    @MockitoSpyBean PickTrendRepository trends;
    @Autowired PickTrendSnapshotRepository snapshots;

    @BeforeEach
    void clearRowsAndSpies() {
        reset(histories, trends);
        trends.deleteAll();
    }

    @AfterEach
    void restoreSpies() {
        reset(histories, trends);
    }

    @Test
    void failureAfterDeletingRowsRollsBackRowsAndSnapshotMetadata() {
        LocalDateTime previousTime = LocalDateTime.of(2026, 9, 10, 4, 10);
        PickTrendSnapshot snapshot = snapshots.findById(PickTrendSnapshot.ID).orElseThrow();
        snapshot.complete(previousTime, SNAPSHOT_CONFIG);
        snapshots.saveAndFlush(snapshot);
        trends.saveAndFlush(PickTrend.builder()
                .dimension(TrendDimension.MENU)
                .label("김치찌개")
                .userCount(5)
                .rankOrder(1)
                .snapshot(snapshot)
                .build());

        // deleteAllTrends() has already run when saveAll() is reached. Throwing here proves that
        // both the bulk delete and the mutated singleton metadata belong to the same transaction.
        doThrow(new IllegalStateException("synthetic write failure"))
                .when(trends).saveAll(any());

        assertThatThrownBy(service::recompute)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("synthetic write failure");

        assertThat(trends.findByDimensionOrderByRankOrderAsc(TrendDimension.MENU))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getLabel()).isEqualTo("김치찌개");
                    assertThat(row.getUserCount()).isEqualTo(5);
                });
        assertThat(snapshots.findById(PickTrendSnapshot.ID).orElseThrow().getComputedAt())
                .isEqualTo(previousTime);
    }

    @Test
    void concurrentRecomputesAreSerializedByTheSingletonDatabaseLock() throws Exception {
        CountDownLatch firstReachedAggregation = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger categoryQueries = new AtomicInteger();

        doAnswer(invocation -> {
            if (categoryQueries.incrementAndGet() == 1) {
                firstReachedAggregation.countDown();
                assertThat(releaseFirst.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return List.of();
        }).when(histories).countUsersByCategorySince(any(), any(), any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PickTrendAggregationService.Result> first = executor.submit(service::recompute);
            assertThat(firstReachedAggregation.await(10, TimeUnit.SECONDS)).isTrue();

            Future<PickTrendAggregationService.Result> second = executor.submit(service::recompute);
            Thread.sleep(300);

            assertThat(second.isDone()).isFalse();
            assertThat(categoryQueries.get())
                    .as("the second transaction must wait at lockSingleton before scanning histories")
                    .isEqualTo(1);

            releaseFirst.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).enabled()).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS).enabled()).isTrue();
            assertThat(categoryQueries.get()).isEqualTo(2);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }
}
