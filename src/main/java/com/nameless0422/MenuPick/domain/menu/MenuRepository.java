package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findAllByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 조회 자체를 소유자 범위로 한정한다 — 타인의 메뉴는 "권한 없음(403)"이 아니라
     * "없음(404)"으로 처리해 리소스 존재 여부가 새어 나가지 않게 한다.
     */
    Optional<Menu> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    List<Menu> findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(Long userId);

    List<Menu> findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId, Pageable pageable);

    List<Menu> findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
            Long userId, Long cursor, Pageable pageable);

    List<Menu> findAllByUserIdAndIsExcludedTrueAndDeletedAtIsNullOrderByIdDesc(Long userId);

    List<Menu> findAllByIdInAndUserIdAndDeletedAtIsNull(List<Long> ids, Long userId);

    // menu_tags, menu_categories는 엔티티가 아니라서(조인/컬렉션 테이블) 네이티브 쿼리로 삭제한다.
    @Modifying
    @Query(value = "DELETE FROM menu_tags WHERE menu_id IN (SELECT id FROM menus WHERE user_id = :userId)",
            nativeQuery = true)
    void deleteMenuTagsByUserId(@Param("userId") Long userId);

    @Modifying
    @Query(value = "DELETE FROM menu_categories WHERE menu_id IN (SELECT id FROM menus WHERE user_id = :userId)",
            nativeQuery = true)
    void deleteMenuCategoriesByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from Menu m where m.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
