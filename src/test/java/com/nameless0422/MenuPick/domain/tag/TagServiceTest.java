package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.menu.Menu;
import com.nameless0422.MenuPick.domain.menu.MenuRepository;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.tag.dto.TagResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock private TagRepository tagRepository;
    @Mock private UserRepository userRepository;
    @Mock private MenuRepository menuRepository;

    @InjectMocks private TagService tagService;

    private User user;
    private Tag tag;

    @BeforeEach
    void setUp() throws Exception {
        user = User.builder().email("test@test.com").nickname("tester").build();
        setId(user, 1L);
        tag = Tag.builder().user(user).name("혼밥").build();
        setId(tag, 1L);
    }

    private void setId(Object entity, Long id) throws Exception {
        Field idField = entity.getClass().getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(entity, id);
    }

    @Test
    @DisplayName("자동완성 검색 - 매칭되는 태그 반환")
    void searchTags_returnsMatchingTags() {
        Tag tag2 = Tag.builder().user(user).name("혼술").build();
        given(tagRepository.findByUserIdAndNameStartingWith(1L, "혼"))
                .willReturn(List.of(tag, tag2));

        List<TagResponse.TagInfo> result = tagService.searchTags(1L, "혼");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("혼밥");
        assertThat(result.get(1).name()).isEqualTo("혼술");
    }

    @Test
    @DisplayName("자동완성 검색 - 빈 키워드면 빈 리스트 반환")
    void searchTags_emptyKeyword_returnsEmpty() {
        List<TagResponse.TagInfo> result = tagService.searchTags(1L, "");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("태그 생성 성공")
    void createTag_success() {
        given(tagRepository.findByUserIdAndName(1L, "혼밥")).willReturn(Optional.empty());
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(tagRepository.save(any(Tag.class))).willReturn(tag);

        TagResponse.TagInfo result = tagService.createTag(1L, new TagRequest.Create("혼밥"));

        assertThat(result.name()).isEqualTo("혼밥");
        verify(tagRepository).save(any(Tag.class));
    }

    @Test
    @DisplayName("태그 생성 - 중복 시 TAG_DUPLICATE 예외")
    void createTag_duplicate_throwsException() {
        given(tagRepository.findByUserIdAndName(1L, "혼밥")).willReturn(Optional.of(tag));

        assertThatThrownBy(() -> tagService.createTag(1L, new TagRequest.Create("혼밥")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TAG_DUPLICATE);
    }

    @Test
    @DisplayName("태그 삭제 성공 - 연결된 메뉴에서 태그 제거")
    void deleteTag_success() {
        given(tagRepository.findById(1L)).willReturn(Optional.of(tag));
        Menu menu = Menu.builder().user(user).name("김치찌개").memo("").weight(1).build();
        menu.addTag(tag);
        given(menuRepository.findAllByTagId(1L)).willReturn(List.of(menu));

        tagService.deleteTag(1L, 1L);

        assertThat(menu.getTags()).doesNotContain(tag);
        verify(tagRepository).delete(tag);
    }

    @Test
    @DisplayName("태그 삭제 - 미존재 시 TAG_NOT_FOUND 예외")
    void deleteTag_notFound_throwsException() {
        given(tagRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> tagService.deleteTag(1L, 999L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TAG_NOT_FOUND);
    }

    @Test
    @DisplayName("태그 삭제 - 타 사용자 접근 시 MENU_ACCESS_DENIED 예외")
    void deleteTag_otherUser_throwsForbidden() {
        given(tagRepository.findById(1L)).willReturn(Optional.of(tag));

        assertThatThrownBy(() -> tagService.deleteTag(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MENU_ACCESS_DENIED);
    }
}
