package com.nameless0422.MenuPick.domain.room;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.history.History;
import com.nameless0422.MenuPick.domain.history.HistoryRepository;
import com.nameless0422.MenuPick.domain.history.HistoryPlaceService;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.room.dto.PickRoomRequest;
import com.nameless0422.MenuPick.domain.room.dto.PickRoomResponse;
import com.nameless0422.MenuPick.domain.restaurant.Restaurant;
import com.nameless0422.MenuPick.domain.restaurant.dto.RestaurantRequest;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 여럿이 같이 뽑기.
 *
 * <h2>흐름</h2>
 *
 * <p>호스트가 자기 메뉴로 방을 만들고(로그인 필요) 링크를 공유한다 → 링크를 받은 사람은
 * 로그인 없이 들어와 "이건 빼주세요"를 누른다 → 아무나 뽑기를 누르면 남은 것 중에서 하나가
 * 정해지고, 그 결과는 더 바뀌지 않는다.
 *
 * <h2>왜 인증 없이 여는가</h2>
 *
 * <p>점심 자리에 있는 사람 전원이 가입해 있을 리 없다. 참여에 가입을 요구하면 방을 만들 이유가
 * 사라진다. 대신 방 코드가 추측 불가능한 랜덤이라 <b>링크를 받은 사람만</b> 들어올 수 있고,
 * 응답에는 호스트가 누구인지도, 다른 참가자가 누구인지도 담기지 않는다.
 *
 * <h2>열어 둔 만큼의 대가</h2>
 *
 * <p>인증이 없으므로 링크를 아는 사람은 뭐든 할 수 있다고 가정하고 막아 두었다.
 * 방 수 상한({@link PickRoom#MAX_OPEN_ROOMS_PER_USER}), 메뉴 수 상한, 참가자 수 상한,
 * 6시간 수명, 그리고 <b>결과는 한 번만 정해진다</b>는 규칙이 그것이다. 마지막 것이 특히 중요하다 —
 * 다시 뽑기가 열려 있으면 링크를 가진 누구나 마음에 들 때까지 굴려 결과를 갈아치울 수 있다.
 */
@Service
@RequiredArgsConstructor
public class PickRoomService {

    /** 한 방에 제외를 남길 수 있는 사람 수. 자리 하나의 인원을 훨씬 넘는 값이면 남용이다. */
    static final int MAX_PARTICIPANTS = 30;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder CODE_ENCODER = Base64.getUrlEncoder().withoutPadding();
    /** 16바이트 = 128비트. 링크가 곧 입장 자격이라 추측 가능성을 남기지 않는다. */
    private static final int CODE_BYTES = 16;

    private final PickRoomRepository roomRepository;
    private final PickRoomVetoRepository vetoRepository;
    private final MenuRepository menuRepository;
    private final HistoryRepository historyRepository;
    private final HistoryPlaceService historyPlaceService;
    private final UserRepository userRepository;
    private final Clock clock;

    @Transactional
    public PickRoomResponse create(Long userId, PickRoomRequest.Create request) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (roomRepository.countOpenRooms(userId, now) >= PickRoom.MAX_OPEN_ROOMS_PER_USER) {
            throw new BusinessException(ErrorCode.PICK_ROOM_LIMIT_EXCEEDED);
        }

        List<Menu> candidates = pickableMenus(userId, request.categories());
        if (candidates.isEmpty()) {
            // 픽과 같은 코드로 답한다 — 사용자가 할 일("메뉴를 추가하거나 제외를 풀어라")이 같다.
            throw new BusinessException(ErrorCode.NO_PICKABLE_MENUS);
        }

        PickRoom room = new PickRoom(newCode(), userRepository.getReferenceById(userId), now);
        candidates.stream()
                // 상한을 넘으면 앞에서 자른다. 무엇이 잘렸는지 알 수 있게 이름순으로 고정한다 —
                // 무작위로 자르면 같은 조건으로 방을 두 번 만들 때 목록이 달라진다.
                .sorted(Comparator.comparing(Menu::getName).thenComparing(Menu::getId))
                .limit(PickRoom.MAX_MENUS)
                .forEach(menu -> room.addMenu(menu.getId(), menu.getName(), menu.getWeight()));

        roomRepository.save(room);
        return toResponse(room, null, userId);
    }

    @Transactional(readOnly = true)
    public PickRoomResponse get(String code, String participant) {
        return get(code, participant, null);
    }

    @Transactional(readOnly = true)
    public PickRoomResponse get(String code, String participant, Long viewerId) {
        return toResponse(openRoom(code), participant, viewerId);
    }

    /**
     * 제외를 통째로 교체한다.
     *
     * <p>이미 결과가 정해진 방에서도 받아들인다 — 거절하면 "늦게 눌렀다"는 이유로 오류를 보게
     * 되는데, 그 시점에는 이미 결과가 화면에 떠 있어 사용자가 할 일이 없다. 제외는 결과를
     * 바꾸지 못하므로 그냥 기록만 되고 끝난다.
     */
    @Transactional
    public PickRoomResponse replaceVetoes(String code, PickRoomRequest.Vetoes request) {
        PickRoom room = openRoom(code);
        String participant = request.participant().trim();
        if (participant.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        List<Long> requested = request.vetoedMenuIds() == null ? List.of() : request.vetoedMenuIds();
        Map<Long, PickRoomMenu> byId = new HashMap<>();
        room.getMenus().forEach(menu -> byId.put(menu.getId(), menu));
        // 이 방에 없는 id는 조용히 무시하지 않는다. 화면이 낡은 목록으로 제출했다는 뜻이고,
        // 그대로 두면 사용자는 뺀 줄 알지만 서버에는 없는 상태가 된다.
        Set<Long> targets = new HashSet<>(requested);
        if (!byId.keySet().containsAll(targets)) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }

        boolean newParticipant = vetoRepository.findMyVetoedMenuIds(room.getId(), participant).isEmpty();
        if (newParticipant && vetoRepository.countParticipants(room.getId()) >= MAX_PARTICIPANTS) {
            throw new BusinessException(ErrorCode.PICK_ROOM_LIMIT_EXCEEDED);
        }

        vetoRepository.deleteMine(room.getId(), participant);
        LocalDateTime now = LocalDateTime.now(clock);
        List<PickRoomVeto> vetoes = targets.stream()
                .map(menuId -> new PickRoomVeto(byId.get(menuId), participant, now))
                .toList();
        vetoRepository.saveAll(vetoes);

        return toResponse(room, participant, null);
    }

    /**
     * 남은 것 중에서 하나를 정한다. 이미 정해진 방이면 그 결과를 그대로 돌려준다.
     *
     * <p>가중치는 호스트가 메뉴에 매겨 둔 값을 그대로 쓴다({@code PickService}와 같은 방식).
     * 다만 개인화 보정은 넣지 않는다 — 그 보정은 호스트 한 사람의 최근 피드백에서 나오는데,
     * 여기 모인 사람들의 결정에 호스트의 취향만 몰래 얹는 셈이 된다.
     */
    @Transactional
    public PickRoomResponse decide(String code) {
        PickRoom room = openRoom(code);
        if (room.isDecided()) {
            return toResponse(room, null, null);
        }

        Map<Long, Long> vetoCounts = vetoCounts(room);
        List<PickRoomMenu> survivors = room.getMenus().stream()
                .filter(menu -> vetoCounts.getOrDefault(menu.getId(), 0L) == 0L)
                .toList();
        if (survivors.isEmpty()) {
            // 아무거나 뽑지 않는다. 전부 빠진 상태에서 나온 결과는 누구도 동의한 적이 없다.
            throw new BusinessException(ErrorCode.PICK_ROOM_NO_CANDIDATES);
        }

        PickRoomMenu chosen = weightedRandom(survivors);
        LocalDateTime now = LocalDateTime.now(clock);
        if (room.decide(chosen, now)) {
            recordHostHistory(room, chosen, now);
        }
        return toResponse(room, null, null);
    }

    /** 방장이 정한 장소를 자신의 픽 기록에 저장하고, 방 링크로 모두에게 보여준다. */
    @Transactional
    public PickRoomResponse choosePlace(String code, Long userId, RestaurantRequest.Create request) {
        PickRoom room = roomRepository.findByCodeForUpdate(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PICK_ROOM_NOT_FOUND));
        if (room.isExpired(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.PICK_ROOM_NOT_FOUND);
        }
        if (userId == null || !room.getHost().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.PICK_ROOM_NOT_FOUND);
        }
        if (!room.isDecided()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT);
        }
        History history = roomHistory(room)
                .orElseThrow(() -> new BusinessException(ErrorCode.HISTORY_NOT_FOUND));
        // 첫 장소만 지킨다. 다시 제출해도 이미 모두에게 공유된 결정을 바꾸지 않는다.
        if (history.getRestaurant() == null) {
            historyPlaceService.choosePlace(userId, history.getId(), request);
        }
        return toResponse(room, null, userId);
    }

    /**
     * 호스트 히스토리에 한 줄 남긴다. 방의 메뉴는 호스트의 메뉴이므로 "이날 이걸 뽑았다"는
     * 호스트의 기록이 맞고, 이 기록이 있어야 나중에 "드셨어요?"를 묻고 식사 요약에도 잡힌다.
     *
     * <p>원본 메뉴가 지워졌으면 남기지 않는다 — 가리킬 메뉴가 없는 히스토리는 이름만 남은 줄이
     * 되어 "그때 무엇을 뽑았는지"를 되짚는 쓸모가 없다.
     *
     * <p>방당 한 번만 불린다({@link PickRoom#decide}가 먼저 정해진 것을 지킨다). 그래서 링크를
     * 아는 사람이 이 경로로 호스트 히스토리를 부풀릴 수 없다.
     */
    private void recordHostHistory(PickRoom room, PickRoomMenu chosen, LocalDateTime now) {
        if (chosen.getMenuId() == null) {
            return;
        }
        menuRepository.findByIdAndUserIdAndDeletedAtIsNull(chosen.getMenuId(), room.getHost().getId())
                .ifPresent(menu -> {
                    History history = History.builder()
                            .user(room.getHost())
                            .menu(menu)
                            .recommendedAt(now)
                            .build();
                    history.addFilterCondition("ROOM", room.getCode());
                    historyRepository.save(history);
                });
    }

    private PickRoomMenu weightedRandom(List<PickRoomMenu> menus) {
        int total = menus.stream().mapToInt(menu -> Math.max(1, menu.getWeight())).sum();
        int random = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (PickRoomMenu menu : menus) {
            cumulative += Math.max(1, menu.getWeight());
            if (random < cumulative) {
                return menu;
            }
        }
        return menus.get(menus.size() - 1);
    }

    /** 없는 방과 만료된 방은 같은 404다 — 링크가 유효했는지를 바깥에서 알아낼 이유가 없다. */
    private PickRoom openRoom(String code) {
        PickRoom room = roomRepository.findByCode(code)
                .orElseThrow(() -> new BusinessException(ErrorCode.PICK_ROOM_NOT_FOUND));
        if (room.isExpired(LocalDateTime.now(clock))) {
            throw new BusinessException(ErrorCode.PICK_ROOM_NOT_FOUND);
        }
        return room;
    }

    private List<Menu> pickableMenus(Long userId, Set<String> categories) {
        List<Menu> menus = menuRepository.findAllByUserIdAndIsExcludedFalseAndDeletedAtIsNull(userId);
        if (categories == null || categories.isEmpty()) {
            return menus;
        }
        Set<String> wanted = categories.stream().map(String::trim).collect(java.util.stream.Collectors.toSet());
        return menus.stream()
                .filter(menu -> menu.getCategories().stream().anyMatch(wanted::contains))
                .toList();
    }

    private Map<Long, Long> vetoCounts(PickRoom room) {
        Map<Long, Long> counts = new HashMap<>();
        vetoRepository.countByRoom(room.getId())
                .forEach(row -> counts.put(row.getRoomMenuId(), row.getPeople()));
        return counts;
    }

    private Optional<History> roomHistory(PickRoom room) {
        return historyRepository.findRoomHistory(room.getHost().getId(), room.getCode(),
                Pageable.ofSize(1)).stream().findFirst();
    }

    private PickRoomResponse toResponse(PickRoom room, String participant, Long viewerId) {
        Map<Long, Long> counts = vetoCounts(room);
        Set<Long> mine = participant == null || participant.isBlank()
                ? Set.of()
                : new HashSet<>(vetoRepository.findMyVetoedMenuIds(room.getId(), participant));

        List<PickRoomResponse.Menu> menus = new ArrayList<>();
        room.getMenus().stream()
                .sorted(Comparator.comparing(PickRoomMenu::getName).thenComparing(PickRoomMenu::getId))
                .forEach(menu -> menus.add(new PickRoomResponse.Menu(
                        menu.getId(),
                        menu.getName(),
                        counts.getOrDefault(menu.getId(), 0L),
                        mine.contains(menu.getId()))));

        History history = room.isDecided() ? roomHistory(room).orElse(null) : null;
        Restaurant chosenPlace = history == null ? null : history.getRestaurant();
        PickRoomResponse.Place place = chosenPlace == null ? null
                : new PickRoomResponse.Place(chosenPlace.getName(), chosenPlace.getNaverUrl());
        PickRoomResponse.Decision decision = room.isDecided()
                ? new PickRoomResponse.Decision(room.getDecidedMenu().getName(), room.getDecidedAt(), place)
                : null;
        boolean canChoosePlace = history != null && chosenPlace == null
                && history.getMenu() != null && !history.getMenu().isDeleted()
                && viewerId != null && room.getHost().getId().equals(viewerId);

        return new PickRoomResponse(room.getCode(), room.getExpiresAt(), menus,
                vetoRepository.countParticipants(room.getId()), decision, canChoosePlace);
    }

    private String newCode() {
        byte[] bytes = new byte[CODE_BYTES];
        RANDOM.nextBytes(bytes);
        return CODE_ENCODER.encodeToString(bytes);
    }
}
