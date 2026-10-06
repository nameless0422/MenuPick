package com.nameless0422.MenuPick.domain.menu;

import com.nameless0422.MenuPick.common.domain.VersionGuard;
import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.dto.MenuRequest;
import com.nameless0422.MenuPick.domain.menu.dto.MenuResponse;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.stream.Collectors;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MenuService {

    private final MenuRepository menuRepository;
    private final TagRepository tagRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    public MenuResponse.MenuListResponse getMenus(Long userId, Long cursor, int size) {
        return getMenus(userId, cursor, size, null);
    }

    public MenuResponse.MenuListResponse getMenus(Long userId, Long cursor, int size, String keyword) {
        var pageable = PageRequest.of(0, size + 1);
        String search = keyword == null ? "" : keyword.trim();
        List<Menu> menus;
        if (search.isEmpty()) {
            menus = (cursor == null)
                    ? menuRepository.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(userId, pageable)
                    : menuRepository.findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(userId, cursor, pageable);
        } else {
            // LIKE의 특수문자는 이름에 들어간 글자 그대로 찾는다.
            String pattern = "%" + search.replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%";
            menus = menuRepository.searchActiveByName(userId, cursor, pattern, pageable);
        }

        boolean hasNext = menus.size() > size;
        List<Menu> result = hasNext ? menus.subList(0, size) : menus;
        Long nextCursor = hasNext ? result.get(result.size() - 1).getId() : null;

        List<MenuResponse.MenuSummary> summaries = result.stream()
                .map(this::toSummary).toList();
        return new MenuResponse.MenuListResponse(summaries, nextCursor, hasNext);
    }

    public MenuResponse.MenuDetail getMenu(Long userId, Long menuId) {
        return toDetail(findMenuOrThrow(userId, menuId));
    }

    public MenuResponse.DeletedMenuListResponse getDeletedMenus(Long userId, String cursor, int size) {
        TrashCursor anchor = decodeTrashCursor(cursor);
        List<Menu> menus = menuRepository.findDeletedMenus(userId,
                anchor == null ? null : anchor.deletedAt(), anchor == null ? null : anchor.id(),
                PageRequest.of(0, size + 1));
        boolean hasNext = menus.size() > size;
        List<Menu> result = hasNext ? menus.subList(0, size) : menus;
        String nextCursor = hasNext ? encodeTrashCursor(result.get(result.size() - 1)) : null;
        // 휴지통에서는 태그·카테고리 컬렉션을 읽지 않는다.
        return new MenuResponse.DeletedMenuListResponse(result.stream()
                .map(menu -> new MenuResponse.DeletedMenuSummary(
                        menu.getId(), menu.getName(), menu.getDeletedAt(), menu.getVersion()))
                .toList(), nextCursor, hasNext);
    }

    private record TrashCursor(LocalDateTime deletedAt, long id) {}

    private static String encodeTrashCursor(Menu menu) {
        String value = menu.getDeletedAt() + "|" + menu.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static TrashCursor decodeTrashCursor(String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 100) throw new IllegalArgumentException();
            String[] values = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (values.length != 2) throw new IllegalArgumentException();
            LocalDateTime deletedAt = LocalDateTime.parse(values[0]);
            long id = Long.parseLong(values[1]);
            if (id <= 0 || deletedAt.getYear() < 1000 || deletedAt.getYear() > 9999) throw new IllegalArgumentException();
            return new TrashCursor(deletedAt, id);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "휴지통 커서가 올바르지 않습니다.");
        }
    }

    @Transactional
    public void restoreMenu(Long userId, Long menuId, MenuRequest.Restore request) {
        Menu menu = menuRepository.findByIdAndUserId(menuId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
        // 응답 유실 후 재시도해도 이미 복원한 메뉴의 설정을 변경하지 않는다.
        if (!menu.isDeleted()) return;
        // 복원 뒤 다시 삭제됐다면 옛 휴지통 화면의 요청으로 되살리면 안 된다.
        VersionGuard.requireCurrentVersion(menu.getVersion(), request.version());
        menu.restore();
    }

    @Transactional
    public MenuResponse.MenuDetail createMenu(Long userId, MenuRequest.Create request) {
        Menu menu = Menu.builder()
                .user(userRepository.getReferenceById(userId))
                .name(request.name())
                .memo(request.memo())
                .weight(request.weight())
                .build();

        normalizeCategories(request.categories()).forEach(menu::addCategory);
        if (request.tagIds() != null && !request.tagIds().isEmpty()) {
            resolveTags(userId, request.tagIds()).forEach(menu::addTag);
        }

        return toDetail(menuRepository.save(menu));
    }

    public MenuResponse.BulkPreview previewBulkCreate(Long userId, MenuRequest.BulkCreate request) {
        return planBulkCreate(userId, request);
    }

    @Transactional
    public MenuResponse.BulkCreateResult bulkCreate(Long userId, MenuRequest.BulkCreate request) {
        MenuResponse.BulkPreview preview = planBulkCreate(userId, request);
        if (preview.hasInvalid()) {
            String lines = preview.entries().stream()
                    .filter(entry -> entry.status().equals("INVALID"))
                    .map(entry -> Integer.toString(entry.line()))
                    .collect(Collectors.joining(", "));
            throw new BusinessException(ErrorCode.INVALID_INPUT, lines + "번째 줄의 이름은 100자 이하여야 합니다.");
        }
        var user = userRepository.getReferenceById(userId);
        List<Menu> menus = preview.entries().stream()
                .filter(entry -> entry.status().equals("ADD"))
                .map(entry -> Menu.builder().user(user).name(entry.name()).build())
                .toList();
        menuRepository.saveAll(menus);
        return new MenuResponse.BulkCreateResult(menus.size());
    }

    private MenuResponse.BulkPreview planBulkCreate(Long userId, MenuRequest.BulkCreate request) {
        Set<String> candidateNames = request.names().stream()
                .filter(name -> name != null)
                .map(String::trim)
                .filter(name -> !name.isEmpty() && name.length() <= 100)
                .collect(Collectors.toSet());
        Set<String> existing = candidateNames.isEmpty() ? Set.of()
                : new HashSet<>(menuRepository.findExistingNamesForBulkCreate(userId, candidateNames));
        Set<String> seen = new HashSet<>();
        List<MenuResponse.BulkEntry> entries = new ArrayList<>();
        int addCount = 0;
        boolean hasInvalid = false;
        for (int i = 0; i < request.names().size(); i++) {
            String raw = request.names().get(i);
            String name = raw == null ? "" : raw.trim();
            String status;
            if (name.isEmpty()) {
                status = "EMPTY";
            } else if (name.length() > 100) {
                status = "INVALID";
                hasInvalid = true;
            } else if (!seen.add(name)) {
                status = "DUPLICATE";
            } else if (existing.contains(name)) {
                status = "EXISTING";
            } else {
                status = "ADD";
                addCount++;
            }
            entries.add(new MenuResponse.BulkEntry(i + 1, name, status));
        }
        return new MenuResponse.BulkPreview(entries, addCount, hasInvalid);
    }

    @Transactional
    public MenuResponse.MenuDetail updateMenu(Long userId, Long menuId, MenuRequest.Update request) {
        Menu menu = findMenuOrThrow(userId, menuId);
        // 아무것도 고치기 전에 확인한다. 뒤로 미루면 이미 바뀐 엔티티를 들고 예외를 던지게 되고,
        // 같은 트랜잭션 안에서 롤백에 기대는 코드가 된다.
        VersionGuard.requireCurrentVersion(menu.getVersion(), request.version());

        menu.update(request.name(), request.memo(), request.weight());

        // isExcluded는 @NotNull이라 여기서는 안전하게 언박싱된다 (누락 시 컨트롤러에서 400).
        if (request.isExcluded()) {
            menu.exclude();
        } else {
            menu.include();
        }

        // 내용이 같은데도 clear() 후 재추가하면 컬렉션 테이블 전체 DELETE+INSERT가 발생한다.
        // 동일하면 건너뛰어 불필요한 쓰기를 없앤다.
        Set<String> newCategories = normalizeCategories(request.categories());
        if (!menu.getCategories().equals(newCategories)) {
            menu.getCategories().clear();
            newCategories.forEach(menu::addCategory);
        }

        // resolveTags는 동일 여부와 무관하게 먼저 호출해야 존재하지 않는 태그 ID를 검증할 수 있다.
        Set<Tag> newTags = (request.tagIds() == null || request.tagIds().isEmpty())
                ? Set.of()
                : Set.copyOf(resolveTags(userId, request.tagIds()));
        if (!menu.getTags().equals(newTags)) {
            menu.getTags().clear();
            newTags.forEach(menu::addTag);
        }

        // 버전은 flush 시점에 올라간다. 그 전에 DTO로 옮기면 응답에 **저장 전 버전**이 실려,
        // 화면은 방금 저장하고도 곧바로 오래된 값을 들게 된다 — 같은 폼에서 한 번 더 저장하면
        // 아무도 건드리지 않았는데 409가 난다. 그래서 매핑 전에 flush를 강제한다.
        menuRepository.flush();
        return toDetail(menu);
    }

    @Transactional
    public void deleteMenu(Long userId, Long menuId) {
        findMenuOrThrow(userId, menuId).softDelete(LocalDateTime.now(clock));
    }

    @Transactional
    public void batchUpdateWeight(Long userId, MenuRequest.BatchUpdateWeight request) {
        // distinct가 없으면 같은 메뉴를 두 번 담은 요청이 404가 된다 — IN 조회는 중복을 한 행으로
        // 돌려주므로 menus.size()가 항상 작아지기 때문이다. 실제로는 접근 가능한 메뉴이고,
        // 마지막 항목의 가중치를 적용하면 되는 정상 요청이다.
        List<Long> menuIds = request.entries().stream()
                .map(MenuRequest.WeightEntry::menuId).distinct().toList();
        List<Menu> menus = menuRepository.findAllByIdInAndUserIdAndDeletedAtIsNull(menuIds, userId);

        if (menus.size() != menuIds.size()) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND,
                    "존재하지 않거나 접근할 수 없는 메뉴가 포함되어 있습니다.");
        }

        Map<Long, Menu> menuMap = menus.stream()
                .collect(Collectors.toMap(Menu::getId, m -> m));
        request.entries().forEach(entry ->
                menuMap.get(entry.menuId()).updateWeight(entry.weight()));
    }

    /**
     * 추천 제외를 한 번에 바꾼다. 근거와 계약은 {@code MenuRequest.BatchUpdateExclusion}.
     *
     * <p>{@code batchUpdateWeight}와 같은 방식으로 소유권을 한 번에 확인한다 — 하나라도 남의
     * 메뉴이거나 없는 메뉴면 아무것도 바꾸지 않고 404다. 일부만 반영되는 것이 가장 나쁘다.
     */
    @Transactional
    public void batchUpdateExclusion(Long userId, MenuRequest.BatchUpdateExclusion request) {
        List<Long> menuIds = request.entries().stream()
                .map(MenuRequest.ExclusionEntry::menuId).distinct().toList();
        List<Menu> menus = menuRepository.findAllByIdInAndUserIdAndDeletedAtIsNull(menuIds, userId);

        if (menus.size() != menuIds.size()) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND,
                    "존재하지 않거나 접근할 수 없는 메뉴가 포함되어 있습니다.");
        }

        Map<Long, Menu> menuMap = menus.stream().collect(Collectors.toMap(Menu::getId, m -> m));
        request.entries().forEach(entry -> {
            Menu menu = menuMap.get(entry.menuId());
            if (entry.excluded()) {
                menu.exclude();
            } else {
                menu.include();
            }
        });
    }

    public List<MenuResponse.MenuSummary> getExcludedMenus(Long userId) {
        return menuRepository.findAllByUserIdAndIsExcludedTrueAndDeletedAtIsNullOrderByIdDesc(userId)
                .stream().map(this::toSummary).toList();
    }

    @Transactional
    public void toggleExclude(Long userId, Long menuId, boolean exclude) {
        Menu menu = findMenuOrThrow(userId, menuId);
        if (exclude) {
            menu.exclude();
        } else {
            menu.include();
        }
    }

    /**
     * 메뉴를 {@code days}일 동안 추천에서 쉬게 한다.
     *
     * <p><b>자정 기준이 아니라 지금부터 N일이다.</b> 날짜 경계로 끊으면 밤 11시에 "3일 쉬기"를
     * 누른 사람은 실제로 2일 하고 한 시간을 쉬게 된다 — 누른 사람이 셈한 것과 다르다.
     *
     * <p>이미 쉬는 중인 메뉴도 그냥 덮어쓴다. 남은 기간에 더하지 않는 이유는 두 번 누르면
     * 기간이 두 배가 되는 동작을 아무도 기대하지 않기 때문이다(그리고 줄이는 수단이 없어진다).
     */
    @Transactional
    public MenuResponse.MenuDetail pauseMenu(Long userId, Long menuId, int days) {
        Menu menu = findMenuOrThrow(userId, menuId);
        menu.pause(LocalDateTime.now(clock).plusDays(days));
        return toDetail(menu);
    }

    /** 쉬는 중인 메뉴를 지금 깨운다. 쉬지 않던 메뉴에 불러도 그대로 성공한다(멱등). */
    @Transactional
    public MenuResponse.MenuDetail resumeMenu(Long userId, Long menuId) {
        Menu menu = findMenuOrThrow(userId, menuId);
        menu.resume();
        return toDetail(menu);
    }

    /**
     * 소유자 범위로 한정해 조회한다. 타인의 메뉴·삭제된 메뉴 모두 MENU_NOT_FOUND(404)로
     * 동일하게 응답해 리소스 존재 여부가 노출되지 않게 한다.
     */
    private Menu findMenuOrThrow(Long userId, Long menuId) {
        return menuRepository.findByIdAndUserIdAndDeletedAtIsNull(menuId, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
    }

    /** 저장 직전 공백만 제거한다 — 대소문자 정규화는 하지 않는다(한글 위주 도메인, UX 결정 대기). */
    private Set<String> normalizeCategories(Set<String> categories) {
        if (categories == null || categories.isEmpty()) {
            return Set.of();
        }
        return categories.stream()
                .map(String::trim)
                .collect(Collectors.toSet());
    }

    private List<Tag> resolveTags(Long userId, Set<Long> tagIds) {
        List<Tag> tags = tagRepository.findAllByIdInAndUserId(tagIds, userId);
        if (tags.size() != tagIds.size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND,
                    "존재하지 않거나 접근할 수 없는 태그가 포함되어 있습니다.");
        }
        return tags;
    }

    private MenuResponse.MenuSummary toSummary(Menu menu) {
        return new MenuResponse.MenuSummary(
                menu.getId(),
                menu.getName(),
                menu.getWeight(),
                menu.isExcluded(),
                // LAZY 컬렉션을 트랜잭션 안에서 복사해 초기화한다 — 참조를 그대로 넘기면
                // open-in-view=false라 직렬화 시점에 LazyInitializationException이 발생한다.
                Set.copyOf(menu.getCategories()),
                toTagSummaries(menu),
                menu.getPausedUntil());
    }

    private MenuResponse.MenuDetail toDetail(Menu menu) {
        return new MenuResponse.MenuDetail(
                menu.getId(),
                menu.getName(),
                menu.getMemo(),
                menu.getWeight(),
                menu.isExcluded(),
                Set.copyOf(menu.getCategories()),
                toTagSummaries(menu),
                menu.getCreatedAt(),
                menu.getUpdatedAt(),
                menu.getVersion(),
                menu.getPausedUntil());
    }

    private List<MenuResponse.TagSummary> toTagSummaries(Menu menu) {
        return menu.getTags().stream()
                .map(t -> new MenuResponse.TagSummary(t.getId(), t.getName()))
                .toList();
    }
}
