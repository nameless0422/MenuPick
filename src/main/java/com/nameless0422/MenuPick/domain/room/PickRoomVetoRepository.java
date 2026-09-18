package com.nameless0422.MenuPick.domain.room;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PickRoomVetoRepository extends JpaRepository<PickRoomVeto, Long> {

    /** 방의 제외를 메뉴별로 센다. 세는 단위는 <b>사람 수</b>다 — 유니크 제약이 그것을 보장한다. */
    @Query("select v.roomMenu.id as roomMenuId, count(v) as people " +
            "from PickRoomVeto v where v.roomMenu.room.id = :roomId group by v.roomMenu.id")
    List<VetoCount> countByRoom(@Param("roomId") Long roomId);

    interface VetoCount {
        Long getRoomMenuId();
        Long getPeople();
    }

    /** 이 방에 제외를 남긴 서로 다른 참가자 수. 화면의 "N명 참여"가 이 값이다. */
    @Query("select count(distinct v.participant) from PickRoomVeto v where v.roomMenu.room.id = :roomId")
    long countParticipants(@Param("roomId") Long roomId);

    /** 내가 뺀 것. 화면이 내 선택을 그대로 복원하는 데 쓴다. */
    @Query("select v.roomMenu.id from PickRoomVeto v " +
            "where v.roomMenu.room.id = :roomId and v.participant = :participant")
    List<Long> findMyVetoedMenuIds(@Param("roomId") Long roomId,
                                   @Param("participant") String participant);

    /**
     * 내 제외를 전부 지운다. 제출은 <b>전체 교체</b>라 지우고 다시 넣는다 — 부분 갱신으로 하면
     * "방금 해제한 것"을 따로 계산해야 하고, 그 계산이 틀리면 해제가 화면에만 반영된다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from PickRoomVeto v where v.roomMenu.room.id = :roomId and v.participant = :participant")
    void deleteMine(@Param("roomId") Long roomId, @Param("participant") String participant);
}
