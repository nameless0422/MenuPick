package com.nameless0422.MenuPick.domain.pick;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PickPresetRepository extends JpaRepository<PickPreset, Long> {

    /**
     * 목록. 조건 컬렉션을 <b>한 번에</b> 끌어온다.
     *
     * <p>{@code left join fetch}를 두 컬렉션에 동시에 걸면 카테시안 곱이 나와 행이 부풀지만,
     * 둘 다 {@code Set}이라 하이버네이트가 중복을 접는다. 상한이 프리셋 10개 ×
     * (카테고리 20 + 태그 40)이라 곱이 커질 수 없어 이 방식이 안전하다.
     * fetch join을 쓰지 않으면 프리셋 수만큼 조회가 따라붙는다(N+1).
     */
    @Query("select distinct p from PickPreset p "
            + "left join fetch p.categories "
            + "left join fetch p.tags "
            + "where p.user.id = :userId order by p.id")
    List<PickPreset> findAllByUserIdWithConditions(@Param("userId") Long userId);

    /**
     * 단건. <b>소유자 범위로 찾는다</b> — 남의 프리셋과 없는 프리셋이 같은 404가 되어야
     * 리소스 존재가 새어 나가지 않는다(기존 메뉴·태그 조회와 같은 관례).
     */
    @Query("select distinct p from PickPreset p "
            + "left join fetch p.categories "
            + "left join fetch p.tags "
            + "where p.id = :id and p.user.id = :userId")
    Optional<PickPreset> findByIdAndUserIdWithConditions(@Param("id") Long id,
                                                         @Param("userId") Long userId);

    /**
     * 실행용 단건 조회 — <b>행을 잠근다.</b>
     *
     * <p>실행은 프리셋을 수정하지 않으므로 낙관적 락만으로는 지켜지지 않는다. 버전을 읽고
     * 확인한 뒤 실제로 픽하기까지 사이가 벌어지는데, 그동안 다른 트랜잭션이 태그를 지워
     * {@link #markNeedsReviewByTagId}로 needsReview를 켜면 <b>이미 통과한 검사를 우회해</b>
     * 조건이 줄어든 채로 추천이 나간다.
     *
     * <p>비관적 쓰기 잠금을 걸면 그 벌크 UPDATE가 이 트랜잭션을 기다리거나 그 반대가 된다.
     * fetch join을 함께 쓰지 않는 이유는 MySQL이 아우터 조인에 {@code FOR UPDATE}를 붙일 때
     * 잠금 대상이 모호해지기 때문이다 — 부모만 잠그고 조건은 지연 로딩으로 읽는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PickPreset p where p.id = :id and p.user.id = :userId")
    Optional<PickPreset> findByIdAndUserIdForUpdate(@Param("id") Long id,
                                                    @Param("userId") Long userId);

    /**
     * 상한 검사용 개수.
     *
     * <p><b>여기에 잠금을 걸지 않는다.</b> 개수 제약은 DB가 표현할 수 없어(UNIQUE는 이름에만
     * 걸린다) 애플리케이션이 세는데, count에 {@code FOR UPDATE}를 붙여도 <b>이미 있는 행만</b>
     * 잠그고 새 INSERT는 막지 못한다 — 실제로 그렇게 짰다가 동시 20건에서 12건이 저장됐다.
     * 직렬화는 부모 행을 잠그는 {@code UserRepository.findByIdForUpdate}가 맡고, 이 조회는
     * 그 잠금 안에서 도는 단순 count다.
     */
    @Query("select count(p) from PickPreset p where p.user.id = :userId")
    long countByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndName(Long userId, String name);

    /**
     * 삭제되는 태그를 참조하는 프리셋에 검토 필요를 켜고 버전을 올린다.
     *
     * <p>한 프리셋이 같은 태그를 포함·제외 양쪽으로 쓸 수는 없지만(검증이 막는다), 이 쿼리는
     * {@code exists} 서브쿼리라 <b>어느 쪽이든 한 번만</b> 갱신한다 — 행 수만큼 버전이 여러 번
     * 오르지 않는다.
     *
     * <p>버전을 함께 올리는 이유: 사용자가 미리보기 화면을 띄워 둔 채 다른 탭에서 태그를
     * 지웠다면, 그 화면이 들고 있는 버전으로는 실행할 수 없어야 한다. needsReview만 켜면
     * 버전이 그대로라 낙관적 락을 통과해 버린다.
     *
     * <p>{@code flushAutomatically} — 같은 트랜잭션에서 아직 flush되지 않은 프리셋 변경이
     * 있으면 이 UPDATE가 그 행을 놓친다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update PickPreset p set p.needsReview = true, p.version = p.version + 1, "
            + "p.updatedAt = CURRENT_TIMESTAMP "
            + "where exists (select 1 from PickPreset other join other.tags t "
            + "              where other.id = p.id and t.tagId = :tagId)")
    int markNeedsReviewByTagId(@Param("tagId") Long tagId);

    /**
     * 하드 삭제용. FK cascade가 자식을 지우지만 <b>부모를 명시적으로 지운다</b> —
     * 수동 purge 목록에서 빠진 테이블은 cascade가 있어도 조용히 남는 전례가 있어
     * ({@code UserHardDeleteService}의 다른 항목들과 같은 이유) 목록에 올려 둔다.
     */
    @Modifying
    @Query("delete from PickPreset p where p.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
