package com.nameless0422.MenuPick.domain.room;

import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.room.dto.PickRoomRequest;
import com.nameless0422.MenuPick.domain.room.dto.PickRoomResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 여럿이 같이 뽑기.
 *
 * <p>이 기능은 <b>인증 없이 열려 있는</b> 유일한 쓰기 경로다. 그래서 검증의 절반은 "링크를 아는
 * 사람이 할 수 있는 나쁜 일"을 막는 것이다 — 결과 덮어쓰기, 참가자 무한 증식, 남의 방 조회,
 * 만료된 방 되살리기.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class PickRoomServiceTest extends AbstractIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 19, 12, 0);

    @Autowired private PickRoomRepository roomRepository;
    @Autowired private PickRoomVetoRepository vetoRepository;
    @Autowired private MenuRepository menuRepository;
    @Autowired private HistoryRepository historyRepository;
    @Autowired private UserRepository userRepository;

    private PickRoomService service;
    private User host;
    private int seq;

    @BeforeEach
    void setUp() {
        service = serviceAt(NOW);
        host = newUser();
    }

    private PickRoomService serviceAt(LocalDateTime now) {
        return new PickRoomService(roomRepository, vetoRepository, menuRepository,
                historyRepository, userRepository, Clock.fixed(now.atZone(KST).toInstant(), KST));
    }

    private User newUser() {
        seq++;
        return userRepository.save(User.builder()
                .email("room" + seq + "@example.com").nickname("room" + seq).build());
    }

    private Menu menu(String name, String category, int weight) {
        Menu menu = Menu.builder().user(host).name(name).weight(weight).build();
        if (category != null) menu.addCategory(category);
        return menuRepository.save(menu);
    }

    private PickRoomResponse createRoom() {
        return service.create(host.getId(), new PickRoomRequest.Create(null));
    }

    private void veto(String code, String participant, List<Long> menuIds) {
        service.replaceVetoes(code, new PickRoomRequest.Vetoes(participant, menuIds));
    }

    private Long menuIdNamed(PickRoomResponse room, String name) {
        return room.menus().stream().filter(m -> m.name().equals(name)).findFirst().orElseThrow().id();
    }

    // --- 만들기 ---

    @Test
    @DisplayName("추천에서 빼 두지 않은 내 메뉴로 방을 만든다")
    void createsRoomFromMyMenus() {
        menu("김치찌개", "한식", 1);
        Menu excluded = menu("제외메뉴", "한식", 1);
        excluded.exclude();
        menuRepository.save(excluded);

        PickRoomResponse room = createRoom();

        assertThat(room.menus()).extracting(PickRoomResponse.Menu::name).containsExactly("김치찌개");
        assertThat(room.code()).isNotBlank();
        assertThat(room.decision()).isNull();
        assertThat(room.expiresAt()).isEqualTo(NOW.plusHours(PickRoom.LIFETIME_HOURS));
    }

    @Test
    @DisplayName("카테고리를 고르면 그 카테고리 메뉴만 담는다")
    void createsRoomFilteredByCategory() {
        menu("김치찌개", "한식", 1);
        menu("파스타", "양식", 1);

        PickRoomResponse room = service.create(host.getId(), new PickRoomRequest.Create(Set.of("한식")));

        assertThat(room.menus()).extracting(PickRoomResponse.Menu::name).containsExactly("김치찌개");
    }

    @Test
    @DisplayName("담을 메뉴가 없으면 픽과 같은 이유로 거절한다")
    void rejectsEmptyCandidates() {
        assertThatThrownBy(this::createRoom)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.NO_PICKABLE_MENUS);
    }

    /** 링크를 뿌려 두고 방만 쌓는 것을 막는다. */
    @Test
    @DisplayName("열어 둔 방이 상한에 닿으면 더 만들지 못한다")
    void limitsOpenRooms() {
        menu("김치찌개", "한식", 1);
        for (int i = 0; i < PickRoom.MAX_OPEN_ROOMS_PER_USER; i++) {
            createRoom();
        }

        assertThatThrownBy(this::createRoom)
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_ROOM_LIMIT_EXCEEDED);
    }

    /** 만료된 방은 자리를 차지하지 않는다 — 6시간이 지나면 새 방을 만들 수 있어야 한다. */
    @Test
    @DisplayName("만료된 방은 상한에 세지 않는다")
    void expiredRoomsDoNotCountTowardLimit() {
        menu("김치찌개", "한식", 1);
        for (int i = 0; i < PickRoom.MAX_OPEN_ROOMS_PER_USER; i++) {
            createRoom();
        }

        PickRoomService later = serviceAt(NOW.plusHours(PickRoom.LIFETIME_HOURS + 1));
        assertThat(later.create(host.getId(), new PickRoomRequest.Create(null)).code()).isNotBlank();
    }

    // --- 제외 ---

    @Test
    @DisplayName("제외는 사람 수로 세고, 내 선택은 나에게만 표시된다")
    void countsVetoesByPeople() {
        menu("김치찌개", "한식", 1);
        menu("파스타", "양식", 1);
        PickRoomResponse room = createRoom();
        Long kimchi = menuIdNamed(room, "김치찌개");

        veto(room.code(), "p-1", List.of(kimchi));
        veto(room.code(), "p-2", List.of(kimchi));
        // 같은 사람이 다시 제출해도 사람 수는 그대로다.
        veto(room.code(), "p-2", List.of(kimchi));

        PickRoomResponse seenByP1 = service.get(room.code(), "p-1");
        assertThat(seenByP1.participantCount()).isEqualTo(2);
        assertThat(seenByP1.menus()).filteredOn(m -> m.id().equals(kimchi))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.vetoedBy()).isEqualTo(2);
                    assertThat(m.vetoedByMe()).isTrue();
                });
        // 남이 뺀 것은 숫자로만 보인다.
        assertThat(service.get(room.code(), "p-3").menus())
                .filteredOn(m -> m.id().equals(kimchi))
                .singleElement()
                .satisfies(m -> assertThat(m.vetoedByMe()).isFalse());
    }

    /** 제출은 전체 교체다. 빈 목록은 "다 풀었다"는 뜻이지 "변경 없음"이 아니다. */
    @Test
    @DisplayName("빈 목록을 보내면 내 제외가 전부 풀린다")
    void emptyListClearsMyVetoes() {
        menu("김치찌개", "한식", 1);
        PickRoomResponse room = createRoom();
        Long kimchi = menuIdNamed(room, "김치찌개");
        veto(room.code(), "p-1", List.of(kimchi));

        veto(room.code(), "p-1", List.of());

        assertThat(service.get(room.code(), "p-1").menus())
                .singleElement()
                .satisfies(m -> assertThat(m.vetoedBy()).isZero());
    }

    /** 낡은 목록으로 제출하면 사용자는 뺐다고 믿는데 서버에는 없는 상태가 된다. */
    @Test
    @DisplayName("이 방에 없는 메뉴 id는 거절한다")
    void rejectsForeignMenuIds() {
        menu("김치찌개", "한식", 1);
        PickRoomResponse room = createRoom();

        assertThatThrownBy(() -> veto(room.code(), "p-1", List.of(999_999L)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }

    @Test
    @DisplayName("참가자가 상한을 넘으면 더 받지 않는다")
    void limitsParticipants() {
        menu("김치찌개", "한식", 1);
        PickRoomResponse room = createRoom();
        Long kimchi = menuIdNamed(room, "김치찌개");
        for (int i = 0; i < PickRoomService.MAX_PARTICIPANTS; i++) {
            veto(room.code(), "p-" + i, List.of(kimchi));
        }

        assertThatThrownBy(() -> veto(room.code(), "p-over", List.of(kimchi)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_ROOM_LIMIT_EXCEEDED);
        // 이미 참여한 사람은 계속 바꿀 수 있다.
        veto(room.code(), "p-0", List.of());
    }

    // --- 뽑기 ---

    @Test
    @DisplayName("제외된 메뉴는 뽑히지 않는다")
    void neverPicksVetoedMenu() {
        menu("김치찌개", "한식", 1);
        menu("파스타", "양식", 1);
        PickRoomResponse room = createRoom();
        veto(room.code(), "p-1", List.of(menuIdNamed(room, "김치찌개")));

        PickRoomResponse decided = service.decide(room.code());

        assertThat(decided.decision()).isNotNull();
        assertThat(decided.decision().menuName()).isEqualTo("파스타");
    }

    /**
     * 이 파일에서 가장 중요한 테스트다. 다시 뽑기가 열려 있으면 링크를 가진 누구나 마음에 들
     * 때까지 굴려 결과를 갈아치울 수 있고, 그러면 "같이 정했다"가 성립하지 않는다.
     */
    @Test
    @DisplayName("한 번 정해진 결과는 다시 눌러도 바뀌지 않는다")
    void decisionIsFinal() {
        for (int i = 0; i < 20; i++) {
            menu("메뉴" + i, "한식", 1);
        }
        PickRoomResponse room = createRoom();

        String first = service.decide(room.code()).decision().menuName();
        for (int i = 0; i < 10; i++) {
            assertThat(service.decide(room.code()).decision().menuName()).isEqualTo(first);
        }
    }

    /** 전부 빠진 상태에서 나온 결과는 누구도 동의한 적이 없다. */
    @Test
    @DisplayName("모두 제외되면 아무거나 뽑지 않고 멈춘다")
    void refusesWhenEverythingVetoed() {
        menu("김치찌개", "한식", 1);
        PickRoomResponse room = createRoom();
        veto(room.code(), "p-1", List.of(menuIdNamed(room, "김치찌개")));

        assertThatThrownBy(() -> service.decide(room.code()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_ROOM_NO_CANDIDATES);
    }

    /** 이 기록이 있어야 나중에 "드셨어요?"를 묻고 식사 요약에도 잡힌다. */
    @Test
    @DisplayName("결과는 호스트 히스토리에 한 번만 남는다")
    void recordsHostHistoryOnce() {
        menu("김치찌개", "한식", 1);
        PickRoomResponse room = createRoom();

        service.decide(room.code());
        service.decide(room.code());
        service.decide(room.code());

        assertThat(historyRepository.findAll())
                .singleElement()
                .satisfies(history -> {
                    assertThat(history.getUser().getId()).isEqualTo(host.getId());
                    assertThat(history.getMenu().getName()).isEqualTo("김치찌개");
                    assertThat(history.isVisited()).isFalse();
                });
    }

    // --- 수명 ---

    @Test
    @DisplayName("만료된 방은 없는 방과 똑같이 404다")
    void expiredRoomIsGone() {
        menu("김치찌개", "한식", 1);
        PickRoomResponse room = createRoom();
        PickRoomService later = serviceAt(NOW.plusHours(PickRoom.LIFETIME_HOURS));

        assertThatThrownBy(() -> later.get(room.code(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_ROOM_NOT_FOUND);
        assertThatThrownBy(() -> later.decide(room.code()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_ROOM_NOT_FOUND);
        assertThatThrownBy(() -> service.get("없는코드", null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.PICK_ROOM_NOT_FOUND);
    }

    /** 코드가 곧 입장 자격이다. 짧거나 예측 가능하면 남의 방이 열린다. */
    @Test
    @DisplayName("방 코드는 매번 다르고 충분히 길다")
    void codesAreUnguessable() {
        menu("김치찌개", "한식", 1);

        String first = createRoom().code();
        String second = createRoom().code();

        assertThat(first).isNotEqualTo(second).hasSizeGreaterThanOrEqualTo(22);
        assertThat(first).matches("[A-Za-z0-9_-]+");
    }
}
