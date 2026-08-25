package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.tag.dto.TagResponse;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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

    /**
     * 자동완성 결과 상한. 입력창 아래 목록이라 이보다 많이 보여줄 자리가 없고, 상한이 없으면
     * 태그가 많은 사용자의 키 입력 한 번이 수백 행을 직렬화해 내려보낸다.
     */
    private static final int MAX_SUGGESTIONS = 20;

    public List<TagResponse.TagInfo> searchTags(Long userId, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String pattern = escapeLike(keyword.trim()) + "%";
        return tagRepository
                .searchByNamePattern(userId, pattern, PageRequest.of(0, MAX_SUGGESTIONS)).stream()
                .map(t -> new TagResponse.TagInfo(t.getId(), t.getName(), t.getCreatedAt()))
                .toList();
    }

    /**
     * LIKE 와일드카드를 문자 그대로 취급하게 만든다.
     *
     * <p>이스케이프하지 않으면 {@code ?keyword=%} 하나로 자기 태그 전량이 내려온다.
     * 타인 데이터가 새지는 않지만 자동완성이 목록 덤프가 되고, {@code _}는 "아무 한 글자"라
     * 사용자가 기대한 것과 다른 결과가 나온다.
     *
     * <p>이스케이프 문자 자신을 먼저 치환해야 한다. 나중에 하면 방금 우리가 붙인 {@code !}까지
     * 다시 이스케이프해 패턴이 어긋난다.
     */
    private static String escapeLike(String value) {
        return value.replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
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

        // 연결된 메뉴를 M건 로드해 컬렉션에서 하나씩 빼면 메뉴 수만큼 쿼리가 늘어난다.
        // 조인 테이블을 tag_id로 한 번에 지운다.
        tagRepository.deleteMenuTagsByTagId(tagId);

        tagRepository.delete(tag);
    }
}
