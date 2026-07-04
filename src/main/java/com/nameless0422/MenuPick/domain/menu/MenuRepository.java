package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MenuRepository extends JpaRepository<Menu, Long> {

    List<Menu> findAllByUserIdAndDeletedAtIsNull(Long userId);

    List<Menu> findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(Long userId);

    List<Menu> findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(Long userId, Pageable pageable);

    List<Menu> findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(
            Long userId, Long cursor, Pageable pageable);

    @Query("SELECT m FROM Menu m JOIN m.tags t WHERE t.id = :tagId")
    List<Menu> findAllByTagId(@Param("tagId") Long tagId);

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
