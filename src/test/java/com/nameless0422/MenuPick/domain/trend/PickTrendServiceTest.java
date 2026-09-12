package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.domain.trend.dto.PickTrendResponse;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

class PickTrendServiceTest {
    private static final LocalDateTime NOW=LocalDateTime.of(2026,9,20,12,0);
    private static final Clock CLOCK=Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(),ZoneId.of("Asia/Seoul"));
    private final PickTrendRepository trends=mock(PickTrendRepository.class);
    private final PickTrendSnapshotRepository snapshots=mock(PickTrendSnapshotRepository.class);
    PickTrendService service(PickTrendProperties p) { return new PickTrendService(trends,snapshots,p,CLOCK); }
    PickTrendSnapshot snapshot(LocalDateTime at, PickTrendProperties p) {
        PickTrendSnapshot s=new PickTrendSnapshot(); s.complete(at,p); return s;
    }

    @Test void disabledDoesNotReadDatabase() {
        assertThat(service(new PickTrendProperties(false,7,5,10)).currentTrends().status())
                .isEqualTo(PickTrendResponse.Status.DISABLED);
        verifyNoInteractions(trends,snapshots);
    }
    @Test void missingComputationIsNotReady() {
        given(snapshots.findById(1L)).willReturn(Optional.of(new PickTrendSnapshot()));
        assertThat(service(new PickTrendProperties(true,7,5,10)).currentTrends().status())
                .isEqualTo(PickTrendResponse.Status.NOT_READY);
        verifyNoInteractions(trends);
    }
    @Test void configMismatchOldAndFutureSnapshotsAreStaleAndHidden() {
        PickTrendProperties current=new PickTrendProperties(true,7,5,10);
        for (PickTrendSnapshot s: List.of(snapshot(NOW.minusHours(37),current),
                snapshot(NOW.plusSeconds(1),current),
                snapshot(NOW,new PickTrendProperties(true,6,5,10)))) {
            reset(snapshots,trends); given(snapshots.findById(1L)).willReturn(Optional.of(s));
            PickTrendResponse response=service(current).currentTrends();
            assertThat(response.status()).isEqualTo(PickTrendResponse.Status.STALE);
            assertThat(response.categories()).isEmpty(); verifyNoInteractions(trends);
        }
    }
    @Test void readyEmptyKeepsSuccessfulSnapshotMetadata() {
        PickTrendProperties p=new PickTrendProperties(true,7,5,10);
        given(snapshots.findById(1L)).willReturn(Optional.of(snapshot(NOW,p)));
        given(trends.findByDimensionOrderByRankOrderAsc(any())).willReturn(List.of());
        PickTrendResponse r=service(p).currentTrends();
        assertThat(r.status()).isEqualTo(PickTrendResponse.Status.READY);
        assertThat(r.computedAt()).isEqualTo(NOW);
    }
    @Test void exactlyThirtySixHoursOldIsStillReady() {
        PickTrendProperties p=new PickTrendProperties(true,7,5,10);
        given(snapshots.findById(1L)).willReturn(Optional.of(snapshot(NOW.minusHours(36),p)));
        given(trends.findByDimensionOrderByRankOrderAsc(any())).willReturn(List.of());
        assertThat(service(p).currentTrends().status()).isEqualTo(PickTrendResponse.Status.READY);
    }
    @Test void everyAggregationSettingMismatchIsStale() {
        PickTrendProperties current=new PickTrendProperties(true,7,5,10);
        for (PickTrendProperties old: List.of(new PickTrendProperties(true,6,5,10),
                new PickTrendProperties(true,7,6,10), new PickTrendProperties(true,7,5,9))) {
            reset(snapshots,trends); given(snapshots.findById(1L)).willReturn(Optional.of(snapshot(NOW,old)));
            assertThat(service(current).currentTrends().status()).isEqualTo(PickTrendResponse.Status.STALE);
        }
    }
    @Test void readDefenseDropsLowCountAndUnknownLabels() {
        PickTrendProperties p=new PickTrendProperties(true,7,5,10);
        PickTrendSnapshot s=snapshot(NOW,p);
        PickTrend low=PickTrend.builder().dimension(TrendDimension.CATEGORY).label("한식").userCount(4).rankOrder(1).snapshot(s).build();
        PickTrend custom=PickTrend.builder().dimension(TrendDimension.CATEGORY).label("비밀").userCount(99).rankOrder(2).snapshot(s).build();
        given(snapshots.findById(1L)).willReturn(Optional.of(s));
        given(trends.findByDimensionOrderByRankOrderAsc(TrendDimension.CATEGORY)).willReturn(List.of(low,custom));
        given(trends.findByDimensionOrderByRankOrderAsc(TrendDimension.MENU)).willReturn(List.of());
        assertThat(service(p).currentTrends().categories()).isEmpty();
    }
}
