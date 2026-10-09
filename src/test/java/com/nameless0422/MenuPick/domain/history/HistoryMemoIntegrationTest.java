package com.nameless0422.MenuPick.domain.history;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.dto.HistoryRequest;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, HistoryService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class HistoryMemoIntegrationTest extends AbstractIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-10-09T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    @MockitoBean private Clock clock;
    @Autowired private HistoryService service;
    @Autowired private HistoryRepository histories;
    @Autowired private UserRepository users;
    @Autowired private EntityManagerFactory emf;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> fixtureUsers = new ArrayList<>();

    @BeforeEach void fixedClock() {
        given(clock.instant()).willReturn(CLOCK.instant());
        given(clock.getZone()).willReturn(CLOCK.getZone());
    }
    @AfterEach void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Long id : fixtureUsers) {
                histories.deleteFilterConditionsByUserId(id);
                histories.deleteAllByUserId(id);
                users.deleteById(id);
            }
        });
    }
    private User user(String suffix) {
        User user = users.save(User.builder().email("history-memo-" + suffix + "@test.com")
                .nickname("기록메모-" + suffix).build());
        fixtureUsers.add(user.getId());
        return user;
    }
    private History history(User user) {
        History history = History.builder().user(user).recommendedAt(NOW).build();
        history.addFilterCondition("CATEGORY", "한식");
        return histories.save(history);
    }

    @Test void saveReturnsCommittedVersionAndListIncludesMemo() {
        User user = user("save");
        History history = history(user);
        var initial = service.getMemo(user.getId(), history.getId());
        assertThat(initial.memo()).isNull();
        assertThat(initial.version()).isZero();
        var saved = service.updateMemo(user.getId(), history.getId(), new HistoryRequest.MemoRequest("  덜 맵게\n주문  ", 0L));
        assertThat(saved.memo()).isEqualTo("덜 맵게\n주문");
        assertThat(saved.version()).isEqualTo(1);
        assertThat(service.getMemo(user.getId(), history.getId())).isEqualTo(saved);
        var summary = service.getHistories(user.getId(), null, 7, null, 20).histories().get(0);
        assertThat(summary.memo()).isEqualTo(saved.memo());
        assertThat(summary.filterConditions()).hasSize(1);
        assertThat(summary.isVisited()).isFalse();
        assertThat(summary.recommendedAt()).isEqualTo(NOW);
    }

    @Test void clearsMemoWithNullOrWhitespaceAndKeepsVisitAndFeedback() {
        User user = user("clear");
        History history = history(user);
        service.markVisited(user.getId(), history.getId(), null);
        service.recordFeedback(user.getId(), history.getId(), RecommendationFeedback.ACCEPTED);
        var current = service.getMemo(user.getId(), history.getId());
        var saved = service.updateMemo(user.getId(), history.getId(), new HistoryRequest.MemoRequest("맛있음", current.version()));
        var cleared = service.updateMemo(user.getId(), history.getId(), new HistoryRequest.MemoRequest(" \n ", saved.version()));
        assertThat(cleared.memo()).isNull();
        var again = service.updateMemo(user.getId(), history.getId(), new HistoryRequest.MemoRequest(null, cleared.version()));
        assertThat(again.memo()).isNull();
        var row = histories.findById(history.getId()).orElseThrow();
        assertThat(row.isVisited()).isTrue();
        assertThat(row.getVisitedAt()).isEqualTo(NOW);
        assertThat(row.getRecommendationFeedback()).isEqualTo(RecommendationFeedback.ACCEPTED);
    }

    @Test void oldMemoVersionDoesNotOverwriteNewMemoOrVisitChanges() {
        User user = user("conflict");
        History history = history(user);
        service.updateMemo(user.getId(), history.getId(), new HistoryRequest.MemoRequest("첫 메모", 0L));
        assertThatThrownBy(() -> service.updateMemo(user.getId(), history.getId(), new HistoryRequest.MemoRequest("옛 화면", 0L)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION);
        var beforeVisit = service.getMemo(user.getId(), history.getId());
        service.markVisited(user.getId(), history.getId(), null);
        assertThatThrownBy(() -> service.updateMemo(user.getId(), history.getId(), new HistoryRequest.MemoRequest("새 메모", beforeVisit.version())))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(service.getMemo(user.getId(), history.getId()).memo()).isEqualTo("첫 메모");
    }

    @Test void ownerScopeAndDeletedRecordApplyToReadAndWrite() {
        User owner = user("owner");
        User other = user("other");
        History history = history(owner);
        assertThatThrownBy(() -> service.getMemo(other.getId(), history.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.HISTORY_NOT_FOUND);
        assertThatThrownBy(() -> service.updateMemo(other.getId(), history.getId(), new HistoryRequest.MemoRequest("타인", 0L)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.HISTORY_NOT_FOUND);
        service.deleteHistory(owner.getId(), history.getId());
        assertThatThrownBy(() -> service.getMemo(owner.getId(), history.getId()))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.HISTORY_NOT_FOUND);
        assertThatThrownBy(() -> service.updateMemo(owner.getId(), history.getId(), new HistoryRequest.MemoRequest("삭제됨", 0L)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.HISTORY_NOT_FOUND);
    }

    @Test void databaseVersionRejectsConcurrentVisitUpdateAndPreservesSavedMemo() {
        User user = user("race");
        History history = history(user);
        var first = emf.createEntityManager();
        var second = emf.createEntityManager();
        try {
            first.getTransaction().begin();
            second.getTransaction().begin();
            var firstRow = first.find(History.class, history.getId());
            var secondRow = second.find(History.class, history.getId());
            firstRow.updateMemo("보존할 메모");
            first.getTransaction().commit();
            secondRow.markVisited(NOW);
            assertThatThrownBy(second::flush).isInstanceOf(OptimisticLockException.class);
        } finally {
            if (first.getTransaction().isActive()) first.getTransaction().rollback();
            if (second.getTransaction().isActive()) second.getTransaction().rollback();
            first.close();
            second.close();
        }
        assertThat(service.getMemo(user.getId(), history.getId()).memo()).isEqualTo("보존할 메모");
        assertThat(histories.findById(history.getId()).orElseThrow().isVisited()).isFalse();
        service.markVisited(user.getId(), history.getId(), null);
        assertThat(histories.findById(history.getId()).orElseThrow().isVisited()).isTrue();
        assertThat(service.getMemo(user.getId(), history.getId()).memo()).isEqualTo("보존할 메모");
    }

    @Test void memoReadUsesOneStatementWithoutLoadingRelatedCollections() {
        User user = user("query");
        History history = history(user);
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            assertThat(service.getMemo(user.getId(), history.getId()).memo()).isNull();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }
}
