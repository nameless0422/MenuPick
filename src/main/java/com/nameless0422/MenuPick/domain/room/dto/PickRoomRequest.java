package com.nameless0422.MenuPick.domain.room.dto;

import com.nameless0422.MenuPick.domain.room.PickRoom;
import com.nameless0422.MenuPick.domain.room.PickRoomVeto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Set;

public class PickRoomRequest {

    /**
     * 방 만들기. 카테고리로 후보를 좁힐 수 있다 — "오늘은 한식 중에서만" 같은 자리가 실제로 있다.
     * 비워 두면 추천에서 빼 두지 않은 내 메뉴 전부가 들어간다.
     */
    public record Create(
            @Size(max = 8, message = "카테고리는 최대 8개까지 고를 수 있습니다.")
            Set<@Size(max = 20) String> categories
    ) {}

    /**
     * 제외 제출. <b>전체 교체</b>다 — 이 참가자가 지금 빼 둔 것이 곧 이 목록이고, 빠진 것은
     * 해제된 것이다. 더하기/빼기로 주고받으면 네트워크가 한 번 어긋났을 때 화면과 서버가
     * 영영 다른 상태로 갈라진다.
     */
    public record Vetoes(
            /**
             * 참가자 식별자. 브라우저가 방마다 새로 만드는 랜덤 값이며 계정과 무관하다 —
             * 근거는 {@link PickRoomVeto}.
             */
            @NotBlank(message = "참가자 식별자는 필수입니다.")
            @Size(max = PickRoomVeto.MAX_PARTICIPANT_LENGTH)
            String participant,

            /** 뺄 메뉴(방 안 사본의 id). 전부 해제하면 빈 배열을 보낸다. */
            @Size(max = PickRoom.MAX_MENUS, message = "제외가 너무 많습니다.")
            List<Long> vetoedMenuIds
    ) {}
}
