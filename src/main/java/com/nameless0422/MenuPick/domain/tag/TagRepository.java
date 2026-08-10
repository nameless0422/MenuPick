package com.nameless0422.MenuPick.domain.tag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByUserIdAndName(Long userId, String name);

    List<Tag> findByUserIdAndNameStartingWith(Long userId, String prefix);

    List<Tag> findAllByIdInAndUserId(Iterable<Long> ids, Long userId);

    @Modifying
    @Query("delete from Tag t where t.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);

    /**
     * 태그를 참조하는 menu_tags 링크를 DELETE 한 방으로 정리한다. menu_tags는 엔티티가 아니라
     * {@code @ManyToMany} 조인 테이블이라 네이티브 쿼리를 쓴다
     * (MenuRepository.deleteMenuTagsByUserId와 동일한 패턴).
     *
     * <p>{@code flushAutomatically=true} — 같은 트랜잭션에서 아직 flush되지 않은 태그 연결이
     * 있으면 DELETE가 그 행을 놓쳐 이어지는 tags 삭제가 FK 위반이 된다.
     *
     * <p>{@code clearAutomatically}는 걸지 않는다 — menu_tags는 PC에 독립 엔티티로 존재하지 않고,
     * 이미 로드된 Menu의 tags 컬렉션은 dirty가 아니어서 flush 시 재삽입되지 않는다.
     * 컨텍스트를 비우면 곧바로 이어지는 Tag 삭제가 merge(SELECT)를 유발할 뿐이다.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = "DELETE FROM menu_tags WHERE tag_id = :tagId", nativeQuery = true)
    void deleteMenuTagsByTagId(@Param("tagId") Long tagId);
}
