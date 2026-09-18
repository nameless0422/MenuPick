package com.nameless0422.MenuPick.domain.room;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 방에 담긴 메뉴 한 줄 — 방을 만든 순간의 <b>사본</b>이다.
 *
 * <h2>왜 원본을 참조만 하지 않는가</h2>
 *
 * <p>방이 열려 있는 동안 호스트는 메뉴 이름을 고치거나 지울 수 있다. 참여자가 보고 있던
 * 목록이 중간에 바뀌면 방금 누른 "제외"가 다른 메뉴에 붙는다. 이름과 가중치를 복사해 두고
 * 그 사본으로만 뽑으면 그런 일이 생기지 않는다.
 *
 * <p>{@code menuId}는 남겨 둔다. 뽑힌 뒤 호스트의 히스토리에 기록할 때 필요하기 때문이다.
 * 원본 메뉴가 지워지면 이 값은 {@code null}이 되고(FK {@code ON DELETE SET NULL}), 방은
 * 이름 사본으로 계속 보인다 — 이미 일어난 일이라 없던 것으로 만들 이유가 없다.
 */
@Entity
@Table(name = "pick_room_menus")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickRoomMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private PickRoom room;

    /** 원본 메뉴 id. 메뉴가 지워지면 null이 된다. */
    @Column(name = "menu_id")
    private Long menuId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false)
    private int weight;

    PickRoomMenu(PickRoom room, Long menuId, String name, int weight) {
        this.room = room;
        this.menuId = menuId;
        this.name = name;
        this.weight = weight;
    }
}
