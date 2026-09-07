package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.pick.dto.PickPreferenceRequest;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DefaultPickPreferenceService {
    private final TagRepository tagRepository;

    public Set<Long> getDefaultExcludedTagIds(Long userId) {
        return Set.copyOf(tagRepository.findDefaultExcludedTagIds(userId));
    }

    @Transactional
    public Set<Long> update(Long userId, PickPreferenceRequest request) {
        Set<Long> ids = new LinkedHashSet<>(request.defaultExcludedTagIds());
        if (ids.contains(null) || tagRepository.findAllByIdInAndUserId(ids, userId).size() != ids.size()) {
            throw new BusinessException(ErrorCode.TAG_NOT_FOUND);
        }
        tagRepository.deleteDefaultExcludedTags(userId);
        ids.forEach(tagId -> tagRepository.insertDefaultExcludedTag(userId, tagId));
        return Set.copyOf(ids);
    }
}
