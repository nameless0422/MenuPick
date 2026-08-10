package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.nameless0422.MenuPick.common.config.JpaConfig;
import com.nameless0422.MenuPick.support.AbstractIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaConfig.class)
@ActiveProfiles("integration")
class TagRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .email("tag@example.com")
                .nickname("태그유저")
                .build());
    }

    @Test
    @DisplayName("태그를 저장하고 조회한다")
    void save_and_findById() {
        Tag tag = Tag.builder()
                .user(user)
                .name("혼밥가능")
                .build();
        Tag saved = tagRepository.save(tag);

        Tag found = tagRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getName()).isEqualTo("혼밥가능");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("사용자 ID와 태그명으로 조회한다")
    void findByUserIdAndName() {
        tagRepository.save(Tag.builder().user(user).name("혼밥가능").build());

        Optional<Tag> found = tagRepository.findByUserIdAndName(user.getId(), "혼밥가능");
        assertThat(found).isPresent();
    }

    @Test
    @DisplayName("태그명 prefix로 자동완성 검색한다")
    void findByUserIdAndNameStartingWith() {
        tagRepository.save(Tag.builder().user(user).name("혼밥가능").build());
        tagRepository.save(Tag.builder().user(user).name("혼술가능").build());
        tagRepository.save(Tag.builder().user(user).name("맵찔이").build());

        List<Tag> tags = tagRepository.findByUserIdAndNameStartingWith(user.getId(), "혼");
        assertThat(tags).hasSize(2);
        assertThat(tags).extracting(Tag::getName)
                .containsExactlyInAnyOrder("혼밥가능", "혼술가능");
    }

    // --- findAllByIdInAndUserId (MenuService/PickService의 태그 소유권 검증 근거) ---

    @Test
    @DisplayName("ID 목록으로 조회할 때 타 사용자의 태그는 제외된다")
    void findAllByIdInAndUserId_excludesOtherUsersTags() {
        User other = userRepository.save(User.builder()
                .email("tag-other@example.com")
                .nickname("다른유저")
                .build());

        Tag mine = tagRepository.save(Tag.builder().user(user).name("내태그").build());
        Tag others = tagRepository.save(Tag.builder().user(other).name("남의태그").build());

        List<Tag> found = tagRepository.findAllByIdInAndUserId(
                List.of(mine.getId(), others.getId()), user.getId());

        // 결과 개수가 요청 개수보다 작아지는 것이 서비스에서 TAG_NOT_FOUND로 걸러지는 근거다.
        assertThat(found).extracting(Tag::getId).containsExactly(mine.getId());
        assertThat(found).hasSizeLessThan(2);
    }

    @Test
    @DisplayName("존재하지 않는 ID가 섞이면 존재하는 태그만 반환한다")
    void findAllByIdInAndUserId_ignoresUnknownIds() {
        Tag mine = tagRepository.save(Tag.builder().user(user).name("내태그").build());

        List<Tag> found = tagRepository.findAllByIdInAndUserId(
                List.of(mine.getId(), mine.getId() + 9999), user.getId());

        assertThat(found).extracting(Tag::getId).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("빈 ID 목록으로 조회하면 빈 결과를 반환한다")
    void findAllByIdInAndUserId_emptyIds() {
        tagRepository.save(Tag.builder().user(user).name("내태그").build());

        assertThat(tagRepository.findAllByIdInAndUserId(List.of(), user.getId())).isEmpty();
    }
}
