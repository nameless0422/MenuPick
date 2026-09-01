package com.nameless0422.MenuPick.domain.menu;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MenuRepository extends JpaRepository<Menu, Long>, JpaSpecificationExecutor<Menu> {

    List<Menu> findAllByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 기본 메뉴를 두 번 넣지 않기 위한 확인({@code DefaultMenuProvisioner}).
     *
     * <p>다른 조회와 달리 {@code deletedAt}을 거르지 않는다 — 여기서 묻는 것은 "지금 보이는
     * 메뉴가 있는가"가 아니라 "이 계정이 메뉴를 가져 본 적이 있는가"이고, 기본 메뉴를 전부 지운
     * 계정에 다시 채워 넣지 않으려면 지워진 것도 세야 한다.
     */
    boolean existsByUserId(Long userId);

    /**
     * 조회 자체를 소유자 범위로 한정한다 — 타인의 메뉴는 "권한 없음(403)"이 아니라
     * "없음(404)"으로 처리해 리소스 존재 여부가 새어 나가지 않게 한다.
     */
    Optional<Menu> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * 필터 없는 픽의 후보. 조건이 붙은 픽은 {@code PickCandidates}가 만든 Specification으로
     * 조인까지 SQL에 내려 조회한다 — 여기서 전량을 올린 뒤 자바로 거르지 않는다.
     */
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
