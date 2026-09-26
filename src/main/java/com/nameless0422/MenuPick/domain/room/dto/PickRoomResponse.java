package com.nameless0422.MenuPick.domain.room.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 방 상태.
 *
 * <p><b>호스트가 누구인지 싣지 않는다.</b> 링크만 있으면 누구나 볼 수 있는 응답이라, 계정을
 * 가리키는 값이 들어가면 링크를 받은 사람이 아니라 링크를 주운 사람에게도 그것이 드러난다.
 * 참가자 식별자도 마찬가지로 남의 것은 내려보내지 않는다 — 숫자(몇 명이 뺐나)만 준다.
 */
public record PickRoomResponse(
        String code,
        LocalDateTime expiresAt,
        List<Menu> menus,
        /** 이 방에서 제외를 남긴 서로 다른 사람 수. */
        long participantCount,
        /** 아직 안 정해졌으면 null. */
        Decision decision,
        /** 로그인한 방장에게만 true. 식당은 방장이 자기 기록에 저장한다. */
        boolean canChoosePlace
) {

    public record Menu(
            Long id,
            String name,
            /** 이 메뉴를 뺀 사람 수. 이름은 담지 않는다. */
            long vetoedBy,
            /** 내가 뺐는가. 화면이 내 선택을 그대로 복원한다. */
            boolean vetoedByMe
    ) {}

    public record Decision(String menuName, LocalDateTime decidedAt, Place place) {}

    public record Place(String name, String url) {}
}
