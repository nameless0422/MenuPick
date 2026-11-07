package com.nameless0422.MenuPick.domain.user;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuService;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantService;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.tag.TagService;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 사용자별 저장 상한(D-077). 잠금과 개수 판정을 실 MySQL에서 검증한다. */
@SpringBootTest
@ActiveProfiles("integration")
class UserDataQuotaIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 1_000_000);

    @Autowired private MenuService menuService;
    @Autowired private RestaurantService restaurantService;
    @Autowired private TagService tagService;
    @Autowired private UserRepository userRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .nickname("quota" + SEQ.incrementAndGet())
                .emailVerified(true)
                .build());
    }

    private void fillMenus(int count) {
        menuRepository.saveAll(IntStream.range(0, count)
                .mapToObj(i -> Menu.builder().user(user).name("menu-" + i).build())
                .toList());
    }

    private void fillRestaurants(int count) {
        restaurantRepository.saveAll(IntStream.range(0, count)
                .mapToObj(i -> Restaurant.builder().user(user).name("place-" + i)
                        .latitude(BigDecimal.ONE).longitude(BigDecimal.ONE).build())
                .toList());
    }

    private void fillTags(int count) {
        tagRepository.saveAll(IntStream.range(0, count)
                .mapToObj(i -> Tag.builder().user(user).name("tag-" + i).build())
                .toList());
    }

    private RestaurantRequest.Create restaurantRequest(String name, String kakaoPlaceId) {
        return new RestaurantRequest.Create(name, null, null, BigDecimal.ONE, BigDecimal.ONE, null, kakaoPlaceId);
    }

    private void assertFails(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(code);
    }

    @Test
    @DisplayName("일괄 추가가 상한을 넘으면 일부만 저장하지 않고 전부 거절한다")
    void bulkCreateIsAllOrNothing() {
        fillMenus(Menu.MAX_PER_USER - 1);

        assertFails(ErrorCode.MENU_LIMIT_EXCEEDED, () -> menuService.bulkCreate(user.getId(),
                new MenuRequest.BulkCreate(List.of("new-a", "new-b"))));

        assertThat(menuRepository.countByUserId(user.getId())).isEqualTo(Menu.MAX_PER_USER - 1);
    }

    @Test
    @DisplayName("동시 요청이 몰려도 메뉴 상한을 넘지 않는다")
    void concurrentCreateRespectsLimit() throws Exception {
        fillMenus(Menu.MAX_PER_USER - 1);

        int succeeded = race(ErrorCode.MENU_LIMIT_EXCEEDED, i -> menuService.bulkCreate(user.getId(),
                new MenuRequest.BulkCreate(List.of("race-" + i))));

        assertThat(succeeded).isOne();
        assertThat(menuRepository.countByUserId(user.getId())).isEqualTo(Menu.MAX_PER_USER);
    }

    @Test
    @DisplayName("동시 요청이 몰려도 식당 상한을 넘지 않는다")
    void concurrentRestaurantCreateRespectsLimit() throws Exception {
        fillRestaurants(Restaurant.MAX_PER_USER - 1);

        int succeeded = race(ErrorCode.RESTAURANT_LIMIT_EXCEEDED, i -> restaurantService.createRestaurant(
                user.getId(), restaurantRequest("race-" + i, "race-place-" + i)));

        assertThat(succeeded).isOne();
        assertThat(restaurantRepository.countByUserId(user.getId())).isEqualTo(Restaurant.MAX_PER_USER);
    }

    @Test
    @DisplayName("동시 요청이 몰려도 태그 상한을 넘지 않는다")
    void concurrentTagCreateRespectsLimit() throws Exception {
        fillTags(Tag.MAX_PER_USER - 1);

        int succeeded = race(ErrorCode.TAG_LIMIT_EXCEEDED, i -> tagService.createTag(
                user.getId(), new TagRequest.Create("race-" + i)));

        assertThat(succeeded).isOne();
        assertThat(tagRepository.countByUserId(user.getId())).isEqualTo(Tag.MAX_PER_USER);
    }

    /** 생성 요청 5건을 동시에 보내 성공 수를 돌려준다. 실패는 상한 초과여야 한다. */
    private int race(ErrorCode expected, IntConsumer create) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(5);
        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int index = i;
            calls.add(() -> {
                try {
                    create.accept(index);
                    return true;
                } catch (BusinessException e) {
                    assertThat(e.getErrorCode()).isEqualTo(expected);
                    return false;
                }
            });
        }

        int succeeded = 0;
        for (Future<Boolean> result : pool.invokeAll(calls)) {
            succeeded += result.get() ? 1 : 0;
        }
        pool.shutdown();
        return succeeded;
    }

    @Test
    @DisplayName("삭제한 메뉴도 상한에 포함해 생성과 삭제를 반복해도 넘지 못한다")
    void deletedMenusCount() {
        fillMenus(Menu.MAX_PER_USER);
        jdbcTemplate.update("UPDATE menus SET deleted_at = NOW() WHERE user_id = ?", user.getId());

        assertFails(ErrorCode.MENU_LIMIT_EXCEEDED, () -> menuService.bulkCreate(user.getId(),
                new MenuRequest.BulkCreate(List.of("new-a"))));
    }

    @Test
    @DisplayName("식당은 상한에서 새로 만들지 못하지만 이미 저장한 장소는 그대로 돌려준다")
    void restaurantLimitAllowsExistingPlace() {
        fillRestaurants(Restaurant.MAX_PER_USER - 1);
        restaurantService.createRestaurant(user.getId(), restaurantRequest("kept", "place-kept"));

        assertFails(ErrorCode.RESTAURANT_LIMIT_EXCEEDED, () -> restaurantService.createRestaurant(
                user.getId(), restaurantRequest("new", "place-new")));

        assertThat(restaurantService.createRestaurant(user.getId(), restaurantRequest("kept", "place-kept"))
                .created()).isFalse();
    }

    @Test
    @DisplayName("태그는 상한까지 만들고 그다음은 거절한다")
    void tagLimit() {
        fillTags(Tag.MAX_PER_USER);

        assertFails(ErrorCode.TAG_LIMIT_EXCEEDED, () -> tagService.createTag(user.getId(),
                new TagRequest.Create("one-more")));
    }
}
