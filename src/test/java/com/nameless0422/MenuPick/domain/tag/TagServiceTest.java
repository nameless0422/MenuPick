package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.domain.tag.dto.TagRequest;
import com.nameless0422.MenuPick.domain.tag.dto.TagResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TagServiceTest {

    @Mock private TagRepository tagRepository;
    @Mock private UserRepository userRepository;

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
        given(tagRepository.searchByNamePattern(eq(1L), eq("혼%"), any(Pageable.class)))
                .willReturn(List.of(tag, tag2));

        List<TagResponse.TagInfo> result = tagService.searchTags(1L, "혼");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("혼밥");
        assertThat(result.get(1).name()).isEqualTo("혼술");
    }

    /**
     * 이스케이프하지 않으면 {@code ?keyword=%} 하나로 자기 태그 전량이 내려온다.
     * 자동완성이 목록 덤프가 되는 셈이라, 입력값이 와일드카드로 해석되지 않아야 한다.
     */
    @Test
    @DisplayName("자동완성 검색 - LIKE 와일드카드를 문자 그대로 취급한다")
    void searchTags_escapesLikeWildcards() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        given(tagRepository.searchByNamePattern(eq(1L), pattern.capture(), any(Pageable.class)))
                .willReturn(List.of());

        tagService.searchTags(1L, "100%_!");

        // % 와 _ 는 앞에 ! 가 붙어 문자로 내려가고, 이스케이프 문자 자신도 두 번 겹쳐 표시된다.
        assertThat(pattern.getValue()).isEqualTo("100!%!_!!%");
    }

    @Test
    @DisplayName("자동완성 검색 - 결과 수에 상한을 건다")
    void searchTags_limitsResults() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        given(tagRepository.searchByNamePattern(eq(1L), anyString(), pageable.capture()))
                .willReturn(List.of());

        tagService.searchTags(1L, "혼");

        // 상한이 없으면 태그가 많은 사용자의 키 입력 한 번이 수백 행을 직렬화해 내려보낸다.
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("자동완성 검색 - 빈 키워드면 빈 리스트 반환")
    void searchTags_emptyKeyword_returnsEmpty() {
        List<TagResponse.TagInfo> result = tagService.searchTags(1L, "");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("전체 목록 - 내 태그를 이름순으로 모두 준다")
    void listTags_returnsAllTags() throws Exception {
        Tag tag2 = Tag.builder().user(user).name("혼술").build();
        setId(tag2, 2L);
        given(tagRepository.findAllByUserIdOrderByName(eq(1L), any(Pageable.class)))
                .willReturn(List.of(tag, tag2));

        List<TagResponse.TagInfo> result = tagService.listTags(1L);

        assertThat(result).extracting(TagResponse.TagInfo::name).containsExactly("혼밥", "혼술");
    }

    /**
     * 관리 화면은 "다 보여주는" 것이 목적이라 자동완성의 20개 상한을 쓰면 안 된다.
     * 태그가 21개인 사용자가 21번째 태그를 영영 지울 수 없게 된다.
     */
    @Test
    @DisplayName("전체 목록 - 자동완성보다 넉넉한 상한을 쓴다")
    void listTags_usesLargerLimitThanAutocomplete() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        given(tagRepository.findAllByUserIdOrderByName(eq(1L), pageable.capture()))
                .willReturn(List.of());

        tagService.listTags(1L);

        assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
    }

    /**
     * 관리 화면을 위해 자동완성의 규칙(빈 키워드 → 빈 목록)을 바꾸지 않았다는 것을 고정한다.
     * 바꿨다면 태그 입력창이 열리자마자 제안 칩이 쏟아진다.
     */
    @Test
    @DisplayName("전체 목록은 자동완성과 다른 경로다 - 빈 키워드 검색은 여전히 비어 있다")
    void listTags_doesNotChangeAutocomplete() {
        assertThat(tagService.searchTags(1L, "")).isEmpty();
        verify(tagRepository, never()).findAllByUserIdOrderByName(any(), any());
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
    @DisplayName("태그 삭제 성공 - menu_tags 링크를 벌크 DELETE 한 번으로 정리하고 태그를 삭제한다")
    void deleteTag_success() {
        given(tagRepository.findById(1L)).willReturn(Optional.of(tag));

        tagService.deleteTag(1L, 1L);

        InOrder inOrder = inOrder(tagRepository);
        // FK 때문에 링크를 먼저 지운 뒤 태그를 지워야 한다
        inOrder.verify(tagRepository).deleteMenuTagsByTagId(1L);
        inOrder.verify(tagRepository).delete(tag);
    }

    @Test
    @DisplayName("태그 삭제 - 타인 태그·미존재 시 링크 삭제도 하지 않는다")
    void deleteTag_notOwned_doesNotTouchLinks() {
        given(tagRepository.findById(1L)).willReturn(Optional.of(tag));

        assertThatThrownBy(() -> tagService.deleteTag(999L, 1L))
                .isInstanceOf(BusinessException.class);

        verify(tagRepository, never()).deleteMenuTagsByTagId(any());
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
    @DisplayName("태그 삭제 - 타 사용자 접근 시 TAG_NOT_FOUND (403 아님 — 존재 노출 차단)")
    void deleteTag_otherUser_throwsNotFound() {
        given(tagRepository.findById(1L)).willReturn(Optional.of(tag));

        assertThatThrownBy(() -> tagService.deleteTag(999L, 1L))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TAG_NOT_FOUND);
    }

    @Test
    @DisplayName("태그 생성 - 사전 검사 통과 후 유니크 제약 위반(동시 생성) 시 TAG_DUPLICATE로 변환")
    void createTag_concurrentInsert_translatedToDuplicate() {
        given(tagRepository.findByUserIdAndName(1L, "혼밥")).willReturn(Optional.empty());
        given(userRepository.getReferenceById(1L)).willReturn(user);
        given(tagRepository.save(any(Tag.class)))
                .willThrow(new DataIntegrityViolationException("uq_tags_user_name"));

        assertThatThrownBy(() -> tagService.createTag(1L, new TagRequest.Create("혼밥")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.TAG_DUPLICATE);
    }
}
