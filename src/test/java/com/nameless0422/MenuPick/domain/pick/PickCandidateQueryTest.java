package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.menu.MenuRestaurant;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.RestaurantRepository;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 픽 후보 조건이 <b>실제 SQL로</b> 무엇을 남기는지 본다.
 *
 * <p>이 파일이 따로 있는 이유: {@code PickServiceTest}는 순수 Mockito라 리포지토리를 통째로
 * 스텁한다. 필터를 자바에서 걸던 시절에는 그래도 로직이 실행됐지만, 조건을 SQL로 내린
 * 뒤에는 스텁이 "이미 걸러진 목록"을 돌려줄 뿐이라 <b>조건 자체는 한 줄도 실행되지 않는다</b>.
 * 서브쿼리 상관관계 하나만 어긋나도(예: 남의 메뉴까지 세는 count) 단위 테스트는 전부
 * 초록불인 채 픽 결과만 조용히 틀린다. 그래서 실 MySQL 위에서 조건만 따로 검증한다.
 *
 * <p>거리는 여기서 <b>바운딩 박스까지만</b> 본다 — 정밀 판정(Haversine)은 자바에 남아 있고
 * {@code PickServiceTest}가 맡는다. 여기서 확인할 것은 하나다: <b>박스가 반경 안의 식당을
 * 떨어뜨리지 않는가.</b> 박스가 좁으면 자바가 되살릴 방법이 없다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickCandidateQueryTest extends AbstractIntegrationTest {

    /** 서울시청. 좌표 계산의 기준점으로만 쓴다. */
    private static final BigDecimal BASE_LAT = new BigDecimal("37.5665350");
    private static final BigDecimal BASE_LNG = new BigDecimal("126.9779692");

    @Autowired private MenuRepository menuRepository;
    @Autowired private RestaurantRepository restaurantRepository;
    @Autowired private TagRepository tagRepository;
    @Autowired private UserRepository userRepository;

    private User me;
    private User other;

    @BeforeEach
    void setUp() {
        me = userRepository.save(User.builder().email("me@example.com").nickname("나").build());
        other = userRepository.save(User.builder().email("other@example.com").nickname("남").build());
    }

    // ------------------------------------------------------------------ 기본 범위

    @Test
    @DisplayName("조건이 없으면 내 메뉴 중 제외되지 않고 살아 있는 것만 남는다")
    void baseScope() {
        Menu kept = menu(me, "김치찌개");
        Menu excluded = menu(me, "제외된메뉴");
        excluded.exclude();
        Menu removed = menu(me, "지운메뉴");
        removed.softDelete(LocalDateTime.now());
        menu(other, "남의메뉴");
        menuRepository.flush();

        assertThat(names(find(Set.of(), null, null, null, null, null)))
                .containsExactly(kept.getName())
                .doesNotContain(excluded.getName(), removed.getName(), "남의메뉴");
    }

    // ------------------------------------------------------------------ 카테고리

    @Test
    @DisplayName("카테고리는 OR — 요청한 것 중 하나만 가져도 남는다")
    void categoriesAreOr() {
        Menu korean = menu(me, "김치찌개", "한식");
        Menu japanese = menu(me, "스시", "일식");
        menu(me, "파스타", "양식");
        menuRepository.flush();

        assertThat(names(find(Set.of("한식", "일식"), null, null, null, null, null)))
                .containsExactlyInAnyOrder(korean.getName(), japanese.getName());
    }

    @Test
    @DisplayName("카테고리 여러 개를 가진 메뉴는 그중 하나만 맞아도 남는다")
    void menuWithSeveralCategories() {
        Menu fusion = menu(me, "퓨전덮밥", "한식", "일식");
        menuRepository.flush();

        assertThat(names(find(Set.of("일식"), null, null, null, null, null)))
                .containsExactly(fusion.getName());
    }

    // ------------------------------------------------------------------ 태그

    @Test
    @DisplayName("포함 태그는 AND — 요청한 태그를 전부 가진 메뉴만 남는다")
    void includeTagsAreAnd() {
        Tag alone = tag(me, "혼밥");
        Tag cheap = tag(me, "가성비");
        Menu both = menuWithTags(me, "국밥", alone, cheap);
        menuWithTags(me, "라멘", alone);
        menuRepository.flush();

        assertThat(names(find(Set.of(), Set.of(alone.getId(), cheap.getId()), null, null, null, null)))
                .containsExactly(both.getName());
    }

    /**
     * 상관 서브쿼리의 {@code where menu = 바깥메뉴}가 빠지면 count가 <b>테이블 전체</b>를 세어,
     * 태그를 하나도 안 가진 메뉴까지 조건을 통과한다. 눈으로는 안 보이는 종류의 실수다.
     */
    @Test
    @DisplayName("포함 태그 - 그 태그가 없는 메뉴는 남지 않는다")
    void includeTagsAreCorrelatedToTheMenu() {
        Tag alone = tag(me, "혼밥");
        Menu tagged = menuWithTags(me, "국밥", alone);
        menu(me, "태그없는메뉴");
        menuRepository.flush();

        assertThat(names(find(Set.of(), Set.of(alone.getId()), null, null, null, null)))
                .containsExactly(tagged.getName());
    }

    @Test
    @DisplayName("포함 태그 - 남의 태그가 같은 이름이어도 내 메뉴 판정에 끼어들지 않는다")
    void includeTagsUseIdsNotNames() {
        Tag mine = tag(me, "혼밥");
        Tag theirs = tag(other, "혼밥");
        menuWithTags(me, "국밥", mine);
        menuRepository.flush();

        // 남의 태그 id로 요청하면 내 메뉴 중 그 id를 가진 것이 없으므로 결과가 비어야 한다.
        assertThat(find(Set.of(), Set.of(theirs.getId()), null, null, null, null)).isEmpty();
    }

    @Test
    @DisplayName("제외 태그는 하나만 걸려도 뺀다")
    void excludeTags() {
        Tag greasy = tag(me, "느끼함");
        Tag cheap = tag(me, "가성비");
        menuWithTags(me, "크림파스타", greasy, cheap);
        Menu kept = menuWithTags(me, "국밥", cheap);
        menuRepository.flush();

        assertThat(names(find(Set.of(), null, Set.of(greasy.getId()), null, null, null)))
                .containsExactly(kept.getName());
    }

    @Test
    @DisplayName("포함과 제외를 함께 걸면 둘 다 만족해야 남는다")
    void includeAndExcludeTogether() {
        Tag alone = tag(me, "혼밥");
        Tag greasy = tag(me, "느끼함");
        Menu kept = menuWithTags(me, "국밥", alone);
        menuWithTags(me, "라멘", alone, greasy);
        menuRepository.flush();

        assertThat(names(find(Set.of(), Set.of(alone.getId()), Set.of(greasy.getId()), null, null, null)))
                .containsExactly(kept.getName());
    }

    // ------------------------------------------------------------------ 거리(바운딩 박스)

    @Test
    @DisplayName("바운딩 박스 - 반경 안의 식당이 붙은 메뉴는 반드시 남는다")
    void boundingBoxKeepsEverythingInsideRadius() {
        // 정북·정동·대각선 — 대각선이 박스 계산에서 가장 아슬아슬하다.
        Menu north = menuAt(me, "북쪽", offset(BASE_LAT, 0.0026), BASE_LNG);        // 약 290m
        Menu east = menuAt(me, "동쪽", BASE_LAT, offset(BASE_LNG, 0.0033));         // 약 290m
        Menu diagonal = menuAt(me, "대각선", offset(BASE_LAT, 0.0018), offset(BASE_LNG, 0.0023));
        menuRepository.flush();

        assertThat(names(find(Set.of(), null, null, BASE_LAT, BASE_LNG, 500)))
                .containsExactlyInAnyOrder(north.getName(), east.getName(), diagonal.getName());
    }

    /**
     * 반경 경계 <b>바로 안쪽</b>. 박스를 조금이라도 좁게 잡으면(예: 위도 1도를 가장 긴 값으로
     * 두거나 여유를 빼면) 여기서 떨어지는데, 한 번 떨어진 식당을 자바가 되살릴 방법은 없다.
     * 499m짜리 식당이 "500m 이내" 결과에서 사라지는 것은 사용자가 알아챌 수 있는 종류의 오류다.
     */
    @Test
    @DisplayName("바운딩 박스 - 반경 경계 바로 안쪽(약 499m)의 식당도 떨어뜨리지 않는다")
    void boundingBoxKeepsTheRadiusEdge() {
        Menu edge = menuAt(me, "경계", offset(BASE_LAT, 0.0044900), BASE_LNG);
        menuRepository.flush();

        assertThat(names(find(Set.of(), null, null, BASE_LAT, BASE_LNG, 500)))
                .containsExactly(edge.getName());
    }

    @Test
    @DisplayName("바운딩 박스 - 한참 먼 식당은 SQL 단계에서 이미 걸러진다")
    void boundingBoxDropsFarAway() {
        Menu near = menuAt(me, "가까운곳", offset(BASE_LAT, 0.001), BASE_LNG);
        menuAt(me, "부산", new BigDecimal("35.1795543"), new BigDecimal("129.0756416"));
        menuRepository.flush();

        assertThat(names(find(Set.of(), null, null, BASE_LAT, BASE_LNG, 500)))
                .containsExactly(near.getName());
    }

    @Test
    @DisplayName("바운딩 박스 - 식당이 하나도 없는 메뉴는 거리 필터에서 빠진다")
    void menuWithoutRestaurants() {
        menu(me, "식당없는메뉴");
        menuRepository.flush();

        assertThat(find(Set.of(), null, null, BASE_LAT, BASE_LNG, 500)).isEmpty();
    }

    @Test
    @DisplayName("바운딩 박스 - 지운 식당은 후보 근거가 되지 못한다")
    void softDeletedRestaurantIsNotEvidence() {
        Menu menu = menu(me, "김치찌개");
        Restaurant removed = restaurant(me, "지운식당", offset(BASE_LAT, 0.001), BASE_LNG);
        removed.softDelete(LocalDateTime.now());
        link(menu, removed);
        menuRepository.flush();

        // 근거로 인정하면 결과에 식당이 하나도 없는 픽이 나간다 — 목록에서는 지운 식당이 빠지므로.
        assertThat(find(Set.of(), null, null, BASE_LAT, BASE_LNG, 500)).isEmpty();
    }

    @Test
    @DisplayName("거리 조건이 하나라도 없으면 거리로 거르지 않는다")
    void distanceNeedsAllThree() {
        menuAt(me, "부산", new BigDecimal("35.1795543"), new BigDecimal("129.0756416"));
        menuRepository.flush();

        assertThat(find(Set.of(), null, null, BASE_LAT, BASE_LNG, null)).hasSize(1);
        assertThat(find(Set.of(), null, null, null, null, 500)).hasSize(1);
    }

    /**
     * 날짜변경선 근처에서는 박스의 경도 범위가 ±180을 넘어간다. 그대로 {@code between}을 쓰면
     * 범위가 뒤집혀 <b>반경 안의 식당까지 전부</b> 떨어진다. 그럴 때는 경도 조건을 걸지 않고
     * 위도만으로 줄인 뒤 자바가 판정한다.
     */
    @Test
    @DisplayName("바운딩 박스 - 날짜변경선 근처에서도 가까운 식당을 떨어뜨리지 않는다")
    void boundingBoxNearAntimeridian() {
        BigDecimal lat = new BigDecimal("-16.5000000");
        BigDecimal lng = new BigDecimal("179.9990000");
        Menu near = menuAt(me, "국경마을", lat, new BigDecimal("-179.9990000"));
        menuRepository.flush();

        assertThat(names(find(Set.of(), null, null, lat, lng, 500))).contains(near.getName());
    }

    // ------------------------------------------------------------------ 조합

    @Test
    @DisplayName("모든 조건은 AND로 묶인다")
    void allConditionsCombine() {
        Tag alone = tag(me, "혼밥");
        Tag greasy = tag(me, "느끼함");

        Menu match = menuAt(me, "국밥", offset(BASE_LAT, 0.001), BASE_LNG);
        match.addCategory("한식");
        match.addTag(alone);

        Menu wrongCategory = menuAt(me, "라멘", offset(BASE_LAT, 0.001), BASE_LNG);
        wrongCategory.addCategory("일식");
        wrongCategory.addTag(alone);

        Menu excludedByTag = menuAt(me, "곰탕", offset(BASE_LAT, 0.001), BASE_LNG);
        excludedByTag.addCategory("한식");
        excludedByTag.addTag(alone);
        excludedByTag.addTag(greasy);

        Menu tooFar = menuAt(me, "부산국밥", new BigDecimal("35.1795543"), new BigDecimal("129.0756416"));
        tooFar.addCategory("한식");
        tooFar.addTag(alone);
        menuRepository.flush();

        assertThat(names(find(Set.of("한식"), Set.of(alone.getId()), Set.of(greasy.getId()),
                BASE_LAT, BASE_LNG, 500)))
                .containsExactly(match.getName())
                .doesNotContain(wrongCategory.getName(), excludedByTag.getName(), tooFar.getName());
    }

    // ------------------------------------------------------------------ 헬퍼

    private List<Menu> find(Set<String> categories, Set<Long> includeTagIds, Set<Long> excludeTagIds,
                            BigDecimal lat, BigDecimal lng, Integer maxDistance) {
        return menuRepository.findAll(PickCandidates.of(
                me.getId(), categories, includeTagIds, excludeTagIds, lat, lng, maxDistance));
    }

    private static List<String> names(List<Menu> menus) {
        return menus.stream().map(Menu::getName).toList();
    }

    private static BigDecimal offset(BigDecimal base, double degrees) {
        return base.add(BigDecimal.valueOf(degrees));
    }

    private Menu menu(User owner, String name, String... categories) {
        Menu menu = Menu.builder().user(owner).name(name).weight(1).build();
        for (String category : categories) {
            menu.addCategory(category);
        }
        return menuRepository.save(menu);
    }

    private Menu menuWithTags(User owner, String name, Tag... tags) {
        Menu menu = Menu.builder().user(owner).name(name).weight(1).build();
        for (Tag tag : tags) {
            menu.addTag(tag);
        }
        return menuRepository.save(menu);
    }

    private Menu menuAt(User owner, String name, BigDecimal lat, BigDecimal lng) {
        Menu menu = menu(owner, name);
        link(menu, restaurant(owner, name + "식당", lat, lng));
        return menu;
    }

    private Tag tag(User owner, String name) {
        return tagRepository.save(Tag.builder().user(owner).name(name).build());
    }

    private Restaurant restaurant(User owner, String name, BigDecimal lat, BigDecimal lng) {
        return restaurantRepository.save(Restaurant.builder()
                .user(owner).name(name).latitude(lat).longitude(lng).build());
    }

    @Autowired private jakarta.persistence.EntityManager entityManager;

    private void link(Menu menu, Restaurant restaurant) {
        entityManager.persist(MenuRestaurant.builder().menu(menu).restaurant(restaurant).build());
    }
}
