package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, RestaurantService.class, RestaurantCreation.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RestaurantCreationIntegrationTest extends AbstractIntegrationTest {
    @MockitoSpyBean private RestaurantRepository restaurants;
    @Autowired private RestaurantService service;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager em;
    private Long fixtureUserId;

    private User user() {
        User user = users.save(User.builder().email("restaurant-create-race@test.com").nickname("식당저장경합").build());
        fixtureUserId = user.getId();
        return user;
    }
    @AfterEach void cleanup() {
        reset(restaurants);
        if (fixtureUserId != null) new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            restaurants.deleteAllByUserId(fixtureUserId);
            users.deleteById(fixtureUserId);
        });
    }
    private RestaurantRequest.Create request(String name, String placeId) {
        return new RestaurantRequest.Create(name, "서울", "02-1234", new BigDecimal("37.5"), new BigDecimal("127.0"),
                "https://place.map.kakao.com/123", placeId);
    }

    @Test void overlappingCreatesBothSucceedAndStoreOnlyOneRestaurant() throws Exception {
        User user = user();
        CyclicBarrier bothReadEmpty = new CyclicBarrier(2);
        AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
            var result = em.createQuery("select r from Restaurant r where r.user.id = :userId and r.kakaoPlaceId = :placeId", Restaurant.class)
                    .setParameter("userId", user.getId()).setParameter("placeId", "race-place")
                    .getResultStream().findFirst();
            if (reads.incrementAndGet() <= 2) bothReadEmpty.await(10, TimeUnit.SECONDS);
            return result;
        }).when(restaurants).findByUserIdAndKakaoPlaceId(eq(user.getId()), eq("race-place"));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<RestaurantService.CreateResult> first = executor.submit(() -> service.createRestaurant(user.getId(), request("첫 요청", "race-place")));
            Future<RestaurantService.CreateResult> second = executor.submit(() -> service.createRestaurant(user.getId(), request("둘째 요청", "race-place")));
            var a = first.get(30, TimeUnit.SECONDS);
            var b = second.get(30, TimeUnit.SECONDS);
            assertThat(a.restaurant().id()).isEqualTo(b.restaurant().id());
            assertThat(a.restaurant().name()).isEqualTo(b.restaurant().name());
            assertThat(java.util.List.of(a.created(), b.created())).containsExactlyInAnyOrder(true, false);
            assertThat(restaurants.findAllByUserIdAndDeletedAtIsNull(user.getId())).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void reSavingDeletedPlaceReturnsCommittedVersionForImmediateEdit() {
        User user = user();
        var first = service.createRestaurant(user.getId(), request("처음 이름", "restore-place"));
        service.deleteRestaurant(user.getId(), first.restaurant().id());
        long deletedVersion = restaurants.findById(first.restaurant().id()).orElseThrow().getVersion();
        var restored = service.createRestaurant(user.getId(), request("검색 결과 이름", "restore-place"));
        assertThat(restored.created()).isTrue();
        assertThat(restored.restaurant().id()).isEqualTo(first.restaurant().id());
        assertThat(restored.restaurant().version()).isEqualTo(deletedVersion + 1);
        assertThat(restored.restaurant().version()).isEqualTo(service.getRestaurant(user.getId(), restored.restaurant().id()).version());
        var edited = service.updateRestaurant(user.getId(), restored.restaurant().id(), new RestaurantRequest.Update(
                "바로 수정한 이름", "서울", null, new BigDecimal("37.5"), new BigDecimal("127.0"), null, restored.restaurant().version()));
        assertThat(edited.name()).isEqualTo("바로 수정한 이름");
    }

    @Test void enclosingTransactionRollbackDoesNotLeaveSavedRestaurant() {
        User user = user();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.createRestaurant(user.getId(), request("함께 롤백", "rollback-place"));
            throw new IllegalStateException("다음 작업 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(restaurants.findAllByUserIdAndDeletedAtIsNull(user.getId())).isEmpty();
    }

    @Test void enclosingTransactionRollbackAlsoUndoesDeletedPlaceRestoration() {
        User user = user();
        var first = service.createRestaurant(user.getId(), request("삭제 전 정보", "rollback-restore"));
        service.deleteRestaurant(user.getId(), first.restaurant().id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.createRestaurant(user.getId(), request("재검색 정보", "rollback-restore"));
            throw new IllegalStateException("연결 실패");
        })).isInstanceOf(IllegalStateException.class);
        Restaurant row = restaurants.findById(first.restaurant().id()).orElseThrow();
        assertThat(row.isDeleted()).isTrue();
        assertThat(row.getName()).isEqualTo("삭제 전 정보");
    }

    @Test void invalidForeignKeyIsNotRetriedAsDuplicatePlace() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.createRestaurant(Long.MAX_VALUE, request("없는 사용자", "invalid-owner")))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        org.mockito.Mockito.verify(restaurants, org.mockito.Mockito.times(1)).findByUserIdAndKakaoPlaceId(Long.MAX_VALUE, "invalid-owner");
    }
    @Test void duplicateInEnclosingTransactionPropagatesAndRollsBackAllWrites() {
        User user = user();
        var winner = service.createRestaurant(user.getId(), request("먼저 저장됨", "outer-duplicate"));
        org.mockito.Mockito.clearInvocations(restaurants);
        org.mockito.Mockito.doReturn(java.util.Optional.empty()).when(restaurants)
                .findByUserIdAndKakaoPlaceId(user.getId(), "outer-duplicate");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.createRestaurant(user.getId(), request("함께 롤백될 식당", "outer-new"));
            service.createRestaurant(user.getId(), request("충돌 요청", "outer-duplicate"));
        })).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(restaurants.findAllByUserIdAndDeletedAtIsNull(user.getId())).extracting(Restaurant::getId)
                .containsExactly(winner.restaurant().id());
        org.mockito.Mockito.verify(restaurants, org.mockito.Mockito.times(1))
                .findByUserIdAndKakaoPlaceId(user.getId(), "outer-duplicate");
    }
}
