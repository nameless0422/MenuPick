package com.nameless0422.MenuPick.domain.restaurant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.time.LocalDateTime;
import java.util.Optional;

public interface RestaurantRepository extends JpaRepository<Restaurant, Long> {

    List<Restaurant> findAllByUserIdAndDeletedAtIsNull(Long userId);

    @Query("""
            select r from Restaurant r
            where r.user.id = :userId and r.deletedAt is null
              and (r.name like :pattern escape '!' or r.address like :pattern escape '!')
            order by r.id desc
            """)
    List<Restaurant> searchSavedRestaurants(@Param("userId") Long userId, @Param("pattern") String pattern);

    Optional<Restaurant> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    Optional<Restaurant> findByIdAndUserId(Long id, Long userId);

    @Query("""
            select r from Restaurant r
            where r.user.id = :userId and r.deletedAt is not null
              and (:deletedBefore is null or r.deletedAt < :deletedBefore
                   or (r.deletedAt = :deletedBefore and r.id < :cursorId))
            order by r.deletedAt desc, r.id desc
            """)
    List<Restaurant> findDeletedRestaurants(@Param("userId") Long userId,
            @Param("deletedBefore") LocalDateTime deletedBefore,
            @Param("cursorId") Long cursorId, Pageable pageable);

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
