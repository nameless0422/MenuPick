package com.nameless0422.MenuPick.domain.restaurant;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.*;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.user.*;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.OptimisticLockException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaConfig.class, RestaurantService.class})
@ActiveProfiles("integration")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RestaurantTrashIntegrationTest extends AbstractIntegrationTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-10T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);
    @MockitoBean private Clock clock;
    @Autowired private RestaurantService service;
    @Autowired private RestaurantRepository restaurants;
    @Autowired private UserRepository users;
    @Autowired private MenuRepository menus;
    @Autowired private MenuRestaurantRepository links;
    @Autowired private EntityManagerFactory emf;
    @Autowired private PlatformTransactionManager transactionManager;
    private final List<Long> fixtureUsers = new ArrayList<>();

    @BeforeEach void setupClock() {
        given(clock.instant()).willReturn(CLOCK.instant());
        given(clock.getZone()).willReturn(CLOCK.getZone());
    }
    @AfterEach void cleanup() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (Long id : fixtureUsers) {
                links.deleteAllByRestaurantOwnerId(id);
                menus.deleteAllByUserId(id);
                restaurants.deleteAllByUserId(id);
                users.deleteById(id);
            }
        });
    }
    private User user(String suffix) {
        User user = users.save(User.builder().email("restaurant-trash-" + suffix + "@test.com").nickname("식당휴지통" + suffix).build());
        fixtureUsers.add(user.getId());
        return user;
    }
    private Restaurant restaurant(User user, String name, LocalDateTime deletedAt) {
        Restaurant row = Restaurant.builder().user(user).name(name).address("수정한 주소")
                .phone("02-1234").latitude(new BigDecimal("37.5")).longitude(new BigDecimal("127.0"))
                .naverUrl("https://place.map.kakao.com/123").kakaoPlaceId(name).build();
        if (deletedAt != null) row.softDelete(deletedAt);
        return restaurants.save(row);
    }
    private RestaurantRequest.Restore request(long version) { return new RestaurantRequest.Restore(version); }

    @Test void restoresOriginalInformationWithoutDeletedLinks() {
        User user = user("restore");
        Restaurant row = restaurant(user, "직접 수정한 지점명", null);
        Menu menu = menus.save(Menu.builder().user(user).name("국수").build());
        links.save(MenuRestaurant.builder().menu(menu).restaurant(row).rating(5).memo("곱빼기").build());
        service.deleteRestaurant(user.getId(), row.getId());
        assertThat(links.existsByMenuIdAndRestaurantId(menu.getId(), row.getId())).isFalse();
        var deleted = service.getDeletedRestaurants(user.getId(), null, 20).restaurants().get(0);
        assertThat(deleted.deletedAt()).isEqualTo(NOW);
        assertThat(service.getRestaurants(user.getId())).isEmpty();
        service.restoreRestaurant(user.getId(), row.getId(), request(deleted.version()));
        var restored = service.getRestaurant(user.getId(), row.getId());
        assertThat(restored.id()).isEqualTo(row.getId());
        assertThat(restored.name()).isEqualTo(row.getName());
        assertThat(restored.address()).isEqualTo(row.getAddress());
        assertThat(restored.phone()).isEqualTo(row.getPhone());
        assertThat(restored.latitude()).isEqualByComparingTo(row.getLatitude());
        assertThat(restored.longitude()).isEqualByComparingTo(row.getLongitude());
        assertThat(restored.naverUrl()).isEqualTo(row.getNaverUrl());
        assertThat(restored.kakaoPlaceId()).isEqualTo(row.getKakaoPlaceId());
        assertThat(restored.createdAt()).isEqualTo(restaurants.findById(row.getId()).orElseThrow().getCreatedAt());
        assertThat(restored.version()).isGreaterThan(deleted.version());
        assertThat(links.existsByMenuIdAndRestaurantId(menu.getId(), row.getId())).isFalse();
        assertThat(service.getDeletedRestaurants(user.getId(), null, 20).restaurants()).isEmpty();
    }

    @Test void cursorSurvivesAnchorRestorationAndSeparatesUsersAndActiveRows() {
        User owner = user("pages"), other = user("other");
        Restaurant oldest = restaurant(owner, "오래된 식당", NOW.minusDays(1));
        Restaurant second = restaurant(owner, "같은 시각1", NOW);
        Restaurant newest = restaurant(owner, "같은 시각2", NOW);
        restaurant(owner, "활성", null);
        restaurant(other, "타인", NOW.plusDays(1));
        var first = service.getDeletedRestaurants(owner.getId(), null, 1);
        assertThat(first.restaurants()).extracting(r -> r.id()).containsExactly(newest.getId());
        assertThat(first.hasNext()).isTrue();
        service.restoreRestaurant(owner.getId(), newest.getId(), request(newest.getVersion()));
        var next = service.getDeletedRestaurants(owner.getId(), first.nextCursor(), 1);
        assertThat(next.restaurants()).extracting(r -> r.id()).containsExactly(second.getId());
        var last = service.getDeletedRestaurants(owner.getId(), next.nextCursor(), 1);
        assertThat(last.restaurants()).extracting(r -> r.id()).containsExactly(oldest.getId());
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test void rejectsForeignMissingAndOldRequestsAfterDeleteAgain() {
        User owner = user("versions"), other = user("foreign");
        Restaurant row = restaurant(owner, "버전", NOW);
        assertThatThrownBy(() -> service.restoreRestaurant(other.getId(), row.getId(), request(0)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESTAURANT_NOT_FOUND);
        assertThatThrownBy(() -> service.restoreRestaurant(owner.getId(), Long.MAX_VALUE, request(0)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.RESTAURANT_NOT_FOUND);
        service.restoreRestaurant(owner.getId(), row.getId(), request(0));
        long version = service.getRestaurant(owner.getId(), row.getId()).version();
        service.restoreRestaurant(owner.getId(), row.getId(), request(0));
        assertThat(service.getRestaurant(owner.getId(), row.getId()).version()).isEqualTo(version);
        service.deleteRestaurant(owner.getId(), row.getId());
        assertThatThrownBy(() -> service.restoreRestaurant(owner.getId(), row.getId(), request(0)))
                .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONCURRENT_MODIFICATION);
        assertThat(restaurants.findById(row.getId()).orElseThrow().isDeleted()).isTrue();
    }

    @Test void samePlaceSaveAfterRestoreKeepsEditedFieldsAndDoesNotDuplicate() {
        User user = user("place");
        Restaurant row = restaurant(user, "저장된 지점명", NOW);
        service.restoreRestaurant(user.getId(), row.getId(), request(0));
        var result = service.createRestaurant(user.getId(), new RestaurantRequest.Create("검색 결과명", "다른 주소", null,
                new BigDecimal("38"), new BigDecimal("128"), null, row.getKakaoPlaceId()));
        assertThat(result.created()).isFalse();
        assertThat(result.restaurant().id()).isEqualTo(row.getId());
        assertThat(result.restaurant().name()).isEqualTo("저장된 지점명");
        assertThat(service.getRestaurants(user.getId())).hasSize(1);
    }

    @Test void realConcurrentRestoreCannotOverwriteOtherRestoreAndEdit() {
        User user = user("race");
        Restaurant row = restaurant(user, "경합", NOW);
        var stale = emf.createEntityManager();
        try {
            stale.getTransaction().begin();
            Restaurant old = stale.find(Restaurant.class, row.getId());
            service.restoreRestaurant(user.getId(), row.getId(), request(0));
            var fresh = service.getRestaurant(user.getId(), row.getId());
            service.updateRestaurant(user.getId(), row.getId(), new RestaurantRequest.Update("최신 이름", fresh.address(), fresh.phone(),
                    fresh.latitude(), fresh.longitude(), fresh.naverUrl(), fresh.version()));
            old.restore();
            assertThatThrownBy(stale::flush).isInstanceOf(OptimisticLockException.class);
            stale.getTransaction().rollback();
        } finally {
            if (stale.getTransaction().isActive()) stale.getTransaction().rollback();
            stale.close();
        }
        assertThat(service.getRestaurant(user.getId(), row.getId()).name()).isEqualTo("최신 이름");
    }

    @Test void oneStatementPageDoesNotLoadOwnerOrCountOrLinks() {
        User user = user("queries");
        for (int i = 0; i < 4; i++) restaurant(user, "식당" + i, NOW);
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        var page = service.getDeletedRestaurants(user.getId(), null, 2);
        assertThat(page.restaurants()).hasSize(2);
        assertThat(page.hasNext()).isTrue();
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
    }

    @Test void malformedCursorNeverBecomesAnUnboundedList() {
        for (String value : List.of("", "!", "a".repeat(101), encode("2026-10-10T12:00|0"),
                encode("bad|1"), encode("2026-10-10T12:00|1|2"), encode("0999-01-01T00:00|1"))) {
            assertThatThrownBy(() -> service.getDeletedRestaurants(1L, value, 20))
                    .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);
        }
    }
    private String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
