package com.nameless0422.MenuPick.domain.pick;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.domain.pick.dto.PickPreferenceRequest;
import com.nameless0422.MenuPick.domain.tag.Tag;
import com.nameless0422.MenuPick.domain.tag.TagRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DefaultPickPreferenceServiceTest {
    @Mock TagRepository tagRepository;

    @Test
    void update_replacesDefaultsAfterOwnershipValidation() {
        given(tagRepository.findAllByIdInAndUserId(Set.of(3L, 4L), 1L))
                .willReturn(List.of(mock(Tag.class), mock(Tag.class)));
        var service = new DefaultPickPreferenceService(tagRepository);

        service.update(1L, new PickPreferenceRequest(Set.of(3L, 4L)));

        verify(tagRepository).deleteDefaultExcludedTags(1L);
        verify(tagRepository).insertDefaultExcludedTag(1L, 3L);
        verify(tagRepository).insertDefaultExcludedTag(1L, 4L);
    }

    @Test
    void update_rejectsAnotherUsersTagWithoutChangingExistingDefaults() {
        given(tagRepository.findAllByIdInAndUserId(Set.of(3L), 1L)).willReturn(List.of());
        var service = new DefaultPickPreferenceService(tagRepository);

        assertThatThrownBy(() -> service.update(1L, new PickPreferenceRequest(Set.of(3L))))
                .isInstanceOf(BusinessException.class);

        verify(tagRepository, never()).deleteDefaultExcludedTags(anyLong());
        verify(tagRepository, never()).insertDefaultExcludedTag(anyLong(), anyLong());
    }
}
