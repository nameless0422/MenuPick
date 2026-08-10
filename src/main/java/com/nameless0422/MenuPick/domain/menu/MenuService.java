package com.nameless0422.MenuPick.domain.menu;

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
import java.util.List;
import java.util.Map;
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
        var pageable = PageRequest.of(0, size + 1);
        List<Menu> menus = (cursor == null)
                ? menuRepository.findAllByUserIdAndDeletedAtIsNullOrderByIdDesc(userId, pageable)
                : menuRepository.findAllByUserIdAndDeletedAtIsNullAndIdLessThanOrderByIdDesc(userId, cursor, pageable);

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

    @Transactional
    public MenuResponse.MenuDetail updateMenu(Long userId, Long menuId, MenuRequest.Update request) {
        Menu menu = findMenuOrThrow(userId, menuId);

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

        return toDetail(menu);
    }

    @Transactional
    public void deleteMenu(Long userId, Long menuId) {
        findMenuOrThrow(userId, menuId).softDelete(LocalDateTime.now(clock));
    }

    @Transactional
    public void batchUpdateWeight(Long userId, MenuRequest.BatchUpdateWeight request) {
        List<Long> menuIds = request.entries().stream()
                .map(MenuRequest.WeightEntry::menuId).toList();
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
                toTagSummaries(menu));
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
                menu.getUpdatedAt());
    }

    private List<MenuResponse.TagSummary> toTagSummaries(Menu menu) {
        return menu.getTags().stream()
                .map(t -> new MenuResponse.TagSummary(t.getId(), t.getName()))
                .toList();
    }
}
