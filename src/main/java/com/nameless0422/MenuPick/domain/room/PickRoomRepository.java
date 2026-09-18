package com.nameless0422.MenuPick.domain.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PickRoomRepository extends JpaRepository<PickRoom, Long> {

    Optional<PickRoom> findByCode(String code);

    /**
     * 아직 살아 있는 내 방 수. 링크를 뿌려 두고 방만 쌓는 것을 막는 상한에 쓴다
     * ({@link PickRoom#MAX_OPEN_ROOMS_PER_USER}).
     */
    @Query("select count(r) from PickRoom r where r.host.id = :userId and r.expiresAt > :now")
    long countOpenRooms(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 만료된 방. 정리 스케줄러가 쓴다.
     *
     * <p>벌크 DELETE로 한 번에 지우지 않는 이유는 자식 행(메뉴 사본·제외) 때문이다. JPQL 벌크
     * 삭제는 cascade를 타지 않고, DB의 {@code ON DELETE CASCADE}에 기대면 "무엇이 몇 개
     * 지워졌는지"를 로그에 남길 수 없다.
     */
    List<PickRoom> findAllByExpiresAtBefore(LocalDateTime cutoff);

    @Modifying
    @Query("delete from PickRoom r where r.host.id = :userId")
    void deleteAllByHostId(@Param("userId") Long userId);
}
