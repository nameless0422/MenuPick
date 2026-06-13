package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.nameless0422.MenuPick.common.config.JpaConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class TagRepositoryTest {

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
}
