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
        Menu menu = findMenuOrThrow(menuId);
        verifyOwnership(menu, userId);
        return toDetail(menu);
    }

    @Transactional
    public MenuResponse.MenuDetail createMenu(Long userId, MenuRequest.Create request) {
        Menu menu = Menu.builder()
                .user(userRepository.getReferenceById(userId))
                .name(request.name())
                .memo(request.memo())
                .weight(request.weight())
                .build();

        if (request.categories() != null) {
            request.categories().forEach(menu::addCategory);
        }
        if (request.tagIds() != null && !request.tagIds().isEmpty()) {
            resolveTags(userId, request.tagIds()).forEach(menu::addTag);
        }

        return toDetail(menuRepository.save(menu));
    }

    @Transactional
    public MenuResponse.MenuDetail updateMenu(Long userId, Long menuId, MenuRequest.Update request) {
        Menu menu = findMenuOrThrow(menuId);
        verifyOwnership(menu, userId);

        menu.update(request.name(), request.memo(), request.weight());

        if (request.isExcluded()) {
            menu.exclude();
        } else {
            menu.include();
        }

        menu.getCategories().clear();
        if (request.categories() != null) {
            request.categories().forEach(menu::addCategory);
        }

        menu.getTags().clear();
        if (request.tagIds() != null && !request.tagIds().isEmpty()) {
            resolveTags(userId, request.tagIds()).forEach(menu::addTag);
        }

        return toDetail(menu);
    }

    @Transactional
    public void deleteMenu(Long userId, Long menuId) {
        Menu menu = findMenuOrThrow(menuId);
        verifyOwnership(menu, userId);
        menu.softDelete();
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
        Menu menu = findMenuOrThrow(menuId);
        verifyOwnership(menu, userId);
        if (exclude) {
            menu.exclude();
        } else {
            menu.include();
        }
    }

    private Menu findMenuOrThrow(Long menuId) {
        Menu menu = menuRepository.findById(menuId)
                .orElseThrow(() -> new BusinessException(ErrorCode.MENU_NOT_FOUND));
        if (menu.isDeleted()) {
            throw new BusinessException(ErrorCode.MENU_NOT_FOUND);
        }
        return menu;
    }

    private void verifyOwnership(Menu menu, Long userId) {
        if (!menu.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.MENU_ACCESS_DENIED);
        }
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
                menu.getCategories(),
                toTagSummaries(menu));
    }

    private MenuResponse.MenuDetail toDetail(Menu menu) {
        return new MenuResponse.MenuDetail(
                menu.getId(),
                menu.getName(),
                menu.getMemo(),
                menu.getWeight(),
                menu.isExcluded(),
                menu.getCategories(),
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
