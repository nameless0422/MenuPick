package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.tag.dto.TagResponse;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;
    private final UserRepository userRepository;
    private final MenuRepository menuRepository;

    public List<TagResponse.TagInfo> searchTags(Long userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        return tagRepository.findByUserIdAndNameStartingWith(userId, keyword).stream()
                .map(t -> new TagResponse.TagInfo(t.getId(), t.getName(), t.getCreatedAt()))
                .toList();
    }

    @Transactional
    public TagResponse.TagInfo createTag(Long userId, TagRequest.Create request) {
        tagRepository.findByUserIdAndName(userId, request.name())
                .ifPresent(t -> { throw new BusinessException(ErrorCode.TAG_DUPLICATE); });

        Tag tag = tagRepository.save(Tag.builder()
                .user(userRepository.getReferenceById(userId))
                .name(request.name())
                .build());

        return new TagResponse.TagInfo(tag.getId(), tag.getName(), tag.getCreatedAt());
    }

    @Transactional
    public void deleteTag(Long userId, Long tagId) {
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAG_NOT_FOUND));

        if (!tag.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.MENU_ACCESS_DENIED);
        }

        List<Menu> menusWithTag = menuRepository.findAllByTagId(tagId);
        menusWithTag.forEach(menu -> menu.removeTag(tag));

        tagRepository.delete(tag);
    }
}
