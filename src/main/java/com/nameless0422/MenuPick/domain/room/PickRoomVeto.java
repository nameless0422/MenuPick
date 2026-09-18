package com.nameless0422.MenuPick.domain.room;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * "이건 빼주세요" 한 줄.
 *
 * <h2>참가자를 계정으로 식별하지 않는다</h2>
 *
 * <p>로그인하지 않은 사람도 참여하므로 {@code participant}는 브라우저가 만든 랜덤 값이다.
 * <b>방마다 새로 만든다</b> — 하나를 여러 방에서 재사용하면 "같은 사람이 이 방들에 다 있었다"가
 * DB에 남는다. 이름·기기 정보는 들어가지 않고, 방이 지워지면 함께 사라진다.
 *
 * <p>같은 사람이 같은 메뉴를 두 번 빼도 한 줄이다(유니크 제약). 화면이 보여주는 숫자는
 * "몇 번 눌렸나"가 아니라 <b>"몇 사람이 뺐나"</b>여야 하기 때문이다.
 */
@Entity
@Table(name = "pick_room_vetoes")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickRoomVeto {

    /** 참가자 식별자의 최대 길이. 브라우저의 UUID(36자)가 들어갈 자리다. */
    public static final int MAX_PARTICIPANT_LENGTH = 64;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_menu_id", nullable = false)
    private PickRoomMenu roomMenu;

    @Column(nullable = false, length = MAX_PARTICIPANT_LENGTH)
    private String participant;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public PickRoomVeto(PickRoomMenu roomMenu, String participant, LocalDateTime createdAt) {
        this.roomMenu = roomMenu;
        this.participant = participant;
        this.createdAt = createdAt;
    }
}
