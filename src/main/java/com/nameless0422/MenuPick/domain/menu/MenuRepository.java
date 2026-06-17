package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
