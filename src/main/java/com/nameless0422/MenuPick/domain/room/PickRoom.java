package com.nameless0422.MenuPick.domain.room;

import com.nameless0422.MenuPick.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 여럿이 같이 뽑는 방.
 *
 * <h2>코드가 곧 입장 자격이다</h2>
 *
 * <p>참여에 로그인을 요구하지 않는다 — 점심 자리에 있는 사람 전원이 가입해 있을 리 없고,
 * 요구하는 순간 방을 만들 이유가 사라진다. 대신 {@code code}가 추측 불가능한 랜덤이라
 * <b>링크를 받은 사람만</b> 들어올 수 있다. 방에 담기는 것은 호스트가 스스로 공유한 자기 메뉴
 * 이름뿐이고, 호스트가 누구인지는 응답에 싣지 않는다.
 *
 * <h2>한 번 정해지면 바뀌지 않는다</h2>
 *
 * <p>{@link #decide}는 이미 정해진 방에서 아무것도 하지 않는다. 링크를 아는 사람이 다시 굴려
 * 결과를 덮어쓸 수 있으면 "같이 정했다"가 성립하지 않는다. 다시 뽑고 싶으면 새 방을 만든다.
 */
@Entity
@Table(name = "pick_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PickRoom {

    /** 방 하나에 담을 수 있는 메뉴 수. 이보다 많으면 화면에서 고르기도 어렵다. */
    public static final int MAX_MENUS = 100;

    /** 한 사용자가 동시에 열어 둘 수 있는 방 수. 링크를 뿌려 두고 쌓아 두는 것을 막는다. */
    public static final int MAX_OPEN_ROOMS_PER_USER = 5;

    /** 방 수명. 점심 한 끼를 정하는 자리라 길 이유가 없다. */
    public static final int LIFETIME_HOURS = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 32, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_user_id", nullable = false)
    private User host;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime expiresAt;

    private LocalDateTime decidedAt;

    /**
     * 뽑힌 항목. 원본 메뉴가 아니라 <b>방 안의 사본</b>을 가리킨다 — 방이 열려 있는 동안
     * 호스트가 메뉴를 고치거나 지워도 결과가 달라지지 않아야 한다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decided_room_menu_id")
    private PickRoomMenu decidedMenu;

    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PickRoomMenu> menus = new ArrayList<>();

    public PickRoom(String code, User host, LocalDateTime createdAt) {
        this.code = Objects.requireNonNull(code);
        this.host = Objects.requireNonNull(host);
        this.createdAt = Objects.requireNonNull(createdAt);
        this.expiresAt = createdAt.plusHours(LIFETIME_HOURS);
    }

    /** 방을 만드는 순간의 이름·가중치를 복사해 담는다. 근거는 {@link PickRoomMenu}. */
    public PickRoomMenu addMenu(Long menuId, String name, int weight) {
        PickRoomMenu roomMenu = new PickRoomMenu(this, menuId, name, weight);
        this.menus.add(roomMenu);
        return roomMenu;
    }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(expiresAt);
    }

    public boolean isDecided() {
        return decidedAt != null;
    }

    /**
     * 결과를 못 박는다. 이미 정해져 있으면 아무것도 하지 않는다 — 먼저 정해진 것이 그대로 남는다.
     *
     * @return 이번 호출이 결과를 정했으면 true
     */
    public boolean decide(PickRoomMenu menu, LocalDateTime now) {
        if (isDecided()) {
            return false;
        }
        this.decidedMenu = Objects.requireNonNull(menu);
        this.decidedAt = Objects.requireNonNull(now);
        return true;
    }
}
