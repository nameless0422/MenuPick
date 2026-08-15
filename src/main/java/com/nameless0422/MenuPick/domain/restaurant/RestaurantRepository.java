package com.nameless0422.MenuPick.domain.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    List<Restaurant> findAllByUserIdAndDeletedAtIsNull(Long userId);

    Optional<Restaurant> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * 같은 장소를 이미 저장했는지 본다.
     *
     * <p>삭제된 행도 함께 찾는다 — {@code uq_restaurants_user_place}가 soft delete된 행까지
     * 포함해 자리를 잡고 있어서, 살아 있는 것만 보고 없다고 판단하면 INSERT가 제약에 걸린다.
     */
    Optional<Restaurant> findByUserIdAndKakaoPlaceId(Long userId, String kakaoPlaceId);

    @Modifying
    @Query("delete from Restaurant r where r.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
