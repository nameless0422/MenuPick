package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.tag.dto.TagResponse;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
        // 일반 경로에서 명확한 에러를 주기 위한 사전 검사. 동시 요청 레이스는 아래 catch가 받는다.
        tagRepository.findByUserIdAndName(userId, request.name())
                .ifPresent(t -> { throw new BusinessException(ErrorCode.TAG_DUPLICATE); });

        Tag tag;
        try {
            // IDENTITY 전략이라 save() 시점에 INSERT가 즉시 실행되므로 여기서 제약 위반을 잡을 수 있다.
            tag = tagRepository.save(Tag.builder()
                    .user(userRepository.getReferenceById(userId))
                    .name(request.name())
                    .build());
        } catch (DataIntegrityViolationException e) {
            // uq_tags_user_name(user_id, name) 위반 — check-then-act 사이에 끼어든 동시 생성
            throw new BusinessException(ErrorCode.TAG_DUPLICATE);
        }

        return new TagResponse.TagInfo(tag.getId(), tag.getName(), tag.getCreatedAt());
    }

    @Transactional
    public void deleteTag(Long userId, Long tagId) {
        Tag tag = tagRepository.findById(tagId)
                .orElseThrow(() -> new BusinessException(ErrorCode.TAG_NOT_FOUND));

        // 타인의 태그는 "권한 없음(403)"이 아니라 "없음(404)"으로 응답한다 — 리소스 존재 노출 차단.
        if (!tag.getUser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }

        List<Menu> menusWithTag = menuRepository.findAllByTagId(tagId);
        menusWithTag.forEach(menu -> menu.removeTag(tag));

        tagRepository.delete(tag);
    }
}
