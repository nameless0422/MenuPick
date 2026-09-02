package com.nameless0422.MenuPick.domain.tag;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByUserIdAndName(Long userId, String name);

    /**
     * 이름 접두사로 자기 태그를 찾는다. {@code prefix}는 <b>이미 이스케이프되어 % 가 붙은</b>
     * LIKE 패턴이어야 한다({@code TagService.searchTags} 참고).
     *
     * <p>파생 메서드({@code findByUserIdAndNameStartingWith})를 쓰지 않는 이유는 그쪽이
     * 파라미터를 그대로 패턴에 넣기 때문이다 — 사용자가 {@code %} 하나만 보내면 자기 태그가
     * 전량 내려온다(타인 데이터는 아니지만 자동완성이 목록 덤프가 된다). {@code escape}를
     * 쓰려면 쿼리를 직접 써야 한다.
     *
     * <p>결과 수는 {@link Pageable}로 자른다. 상한이 없으면 태그가 많은 사용자의 자동완성
     * 한 번이 수백 행을 직렬화해 내려보낸다.
     */
    @Query("select t from Tag t where t.user.id = :userId and t.name like :pattern escape '!' order by t.name")
    List<Tag> searchByNamePattern(@Param("userId") Long userId,
                                  @Param("pattern") String pattern,
                                  Pageable pageable);

    /**
     * 태그 관리 화면용 전체 목록. 자동완성({@link #searchByNamePattern})과 달리 LIKE가 없어
     * 파생 메서드로 충분하다 — 이스케이프할 사용자 입력이 아예 없다.
     *
     * <p>여기도 {@link Pageable}로 자른다. "전체"라고 해서 상한을 빼면 태그가 많은 계정의
     * 설정 화면 한 번이 전 행을 직렬화한다.
     */
    List<Tag> findAllByUserIdOrderByName(Long userId, Pageable pageable);

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
