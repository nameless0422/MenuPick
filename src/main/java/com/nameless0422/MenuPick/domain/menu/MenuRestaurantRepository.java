package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuRestaurantRepository extends JpaRepository<MenuRestaurant, Long> {

    List<MenuRestaurant> findAllByMenuId(Long menuId);

    Optional<MenuRestaurant> findByMenuIdAndRestaurantId(Long menuId, Long restaurantId);

    boolean existsByMenuIdAndRestaurantId(Long menuId, Long restaurantId);

    /**
     * 이 사용자에게 <b>식당이 연결된 메뉴가 하나라도</b> 있는가.
     *
     * <p>거리 필터로 후보가 비었을 때 "반경을 늘리면 되는 상황"인지 "반경과 무관하게 연결이
     * 아예 없는 상황"인지를 가르는 데 쓴다. 둘을 구분하지 않으면 화면이 반경을 늘리라고
     * 조언하는데 아무리 늘려도 결과가 없다 — 기본 메뉴 22개로 시작한 신규 사용자가 정확히
     * 이 상태다(메뉴는 많고 연결은 0건).
     *
     * <p>양쪽의 soft delete를 모두 본다. 지운 메뉴나 지운 식당의 링크는 후보 근거가 되지
     * 못하므로({@code PickCandidates}의 같은 조건), 여기서 세면 "연결이 있다"고 답해 놓고
     * 픽은 계속 비는 어긋남이 생긴다.
     */
    @Query("select count(mr) > 0 from MenuRestaurant mr " +
            "where mr.menu.user.id = :userId " +
            "and mr.menu.deletedAt is null " +
            "and mr.restaurant.deletedAt is null")
    boolean existsLinkedRestaurantForUser(@Param("userId") Long userId);

    /**
     * 식당 soft-delete 시 해당 식당을 참조하는 메뉴-식당 링크를 일괄 정리한다.
     * 링크는 soft-delete 대상이 아니므로(단순 연결 테이블) 물리 삭제한다.
     */
    @Modifying
    @Query("delete from MenuRestaurant mr where mr.restaurant.id = :restaurantId")
    void deleteByRestaurantId(@Param("restaurantId") Long restaurantId);

    /**
     * 유저 하드 삭제용. menu 기준·restaurant 기준을 OR로 묶으면 옵티마이저가 인덱스를
     * 하나도 못 쓰고 menu_restaurants 전체를 훑는다. 각각 다른 인덱스(uq_menu_restaurant /
     * idx_mr_restaurant_id)를 타도록 두 개의 DELETE로 분리한다.
     *
     * <p>메뉴 소유자와 식당 소유자가 같은 유저인 링크는 첫 번째 DELETE에서 이미 지워지고,
     * 두 번째 DELETE는 남은 행만 지우므로 중복 삭제 문제는 없다.
     */
    @Modifying
    @Query("delete from MenuRestaurant mr where " +
            "mr.menu.id in (select m.id from Menu m where m.user.id = :userId)")
    void deleteAllByMenuOwnerId(@Param("userId") Long userId);

    /** @see #deleteAllByMenuOwnerId(Long) */
    @Modifying
    @Query("delete from MenuRestaurant mr where " +
            "mr.restaurant.id in (select r.id from Restaurant r where r.user.id = :userId)")
    void deleteAllByRestaurantOwnerId(@Param("userId") Long userId);
}
