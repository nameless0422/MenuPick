package com.nameless0422.MenuPick.domain.trend;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.history.*;
import com.nameless0422.MenuPick.domain.menu.*;
import com.nameless0422.MenuPick.domain.user.*;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickTrendAggregationServiceTest extends AbstractIntegrationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 20, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.atZone(ZoneId.of("Asia/Seoul")).toInstant(), ZoneId.of("Asia/Seoul"));
    private static final PickTrendProperties ON = new PickTrendProperties(true, 7, 5, 10);
    @Autowired HistoryRepository histories;
    @Autowired MenuRepository menus;
    @Autowired UserRepository users;
    @Autowired PickTrendRepository trends;
    @Autowired PickTrendSnapshotRepository snapshots;
    int seq;

    PickTrendAggregationService service(PickTrendProperties p) {
        return new PickTrendAggregationService(histories, trends, snapshots, p, CLOCK);
    }
    User user() { seq++; return users.save(User.builder().email("t"+seq+"@e.test").nickname("t"+seq).build()); }
    History signal(User u, String name, String category, LocalDateTime at, boolean accepted) {
        Menu m = Menu.builder().user(u).name(name).build(); if (category != null) m.addCategory(category); menus.save(m);
        History h = History.builder().user(u).menu(m).recommendedAt(at).build();
        if (accepted) h.recordFeedback(RecommendationFeedback.ACCEPTED); else h.markVisited(at.plusHours(1));
        return histories.save(h);
    }
    void five(String name, String category) { for (int i=0;i<5;i++) signal(user(),name,category,NOW.minusDays(1), i%2==0); }
    List<PickTrend> rows(TrendDimension d) { return trends.findByDimensionOrderByRankOrderAsc(d); }

    @Test void storesCanonicalDistinctUsersAndBothSignalsOnce() {
        User repeat = user();
        for (int i=0;i<3;i++) signal(repeat,"김치찌개","한식",NOW.minusDays(1).plusMinutes(i), i%2==0);
        for (int i=0;i<4;i++) signal(user(),"김치찌개","한식",NOW.minusDays(1), i%2==0);
        service(ON).recompute();
        assertThat(rows(TrendDimension.CATEGORY)).singleElement().extracting(PickTrend::getUserCount).isEqualTo(5);
        assertThat(rows(TrendDimension.MENU)).singleElement().extracting(PickTrend::getUserCount).isEqualTo(5);
    }
    @Test void neverStoresCustomLabelsEvenAboveThreshold() {
        // MySQL의 기본 CI collation은 trailing space 등을 같게 볼 수 있다. 공개 어휘는 정확한
        // UTF 문자열만 허용하므로, 닮은 자유 문자열도 canonical 항목으로 합쳐지면 안 된다.
        five("김치찌개 ", "한식 "); service(ON).recompute();
        assertThat(trends.findAll()).isEmpty();
    }
    @Test void excludesWithdrawnFutureAndWindowBoundary() {
        for (int i=0;i<4;i++) signal(user(),"김치찌개","한식",NOW.minusDays(1),true);
        User withdrawn=user(); signal(withdrawn,"김치찌개","한식",NOW.minusDays(1),true);
        withdrawn.softDelete(NOW.minusHours(1)); users.save(withdrawn);
        signal(user(),"김치찌개","한식",NOW.plusSeconds(1),true);
        signal(user(),"김치찌개","한식",NOW.minusDays(7).minusSeconds(1),true);
        service(ON).recompute();
        assertThat(trends.findAll()).isEmpty();
    }
    @Test void disabledDoesNotChangeSnapshot() {
        LocalDateTime before=snapshots.findById(1L).orElseThrow().getComputedAt();
        var result=service(new PickTrendProperties(false,7,5,10)).recompute();
        assertThat(result.enabled()).isFalse();
        assertThat(snapshots.findById(1L).orElseThrow().getComputedAt()).isEqualTo(before);
    }
    @Test void successfulEmptyUpdatesSnapshotMetadata() {
        service(ON).recompute(); PickTrendSnapshot s=snapshots.findById(1L).orElseThrow();
        assertThat(s.getComputedAt()).isEqualTo(NOW); assertThat(s.getWindowDays()).isEqualTo(7);
        assertThat(trends.findAll()).isEmpty();
    }
    @Test void replacesPreviousRows() {
        five("김치찌개","한식"); service(ON).recompute(); assertThat(trends.findAll()).hasSize(2);
        histories.deleteAll();
        service(ON).recompute(); assertThat(trends.findAll()).isEmpty();
    }
}
