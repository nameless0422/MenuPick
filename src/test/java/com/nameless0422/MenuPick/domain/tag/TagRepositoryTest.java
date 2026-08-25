package com.nameless0422.MenuPick.domain.tag;

import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
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
    void searchByNamePattern() {
        tagRepository.save(Tag.builder().user(user).name("혼밥가능").build());
        tagRepository.save(Tag.builder().user(user).name("혼술가능").build());
        tagRepository.save(Tag.builder().user(user).name("맵찔이").build());

        List<Tag> tags = tagRepository.searchByNamePattern(user.getId(), "혼%", PageRequest.of(0, 20));
        assertThat(tags).hasSize(2);
        assertThat(tags).extracting(Tag::getName)
                .containsExactlyInAnyOrder("혼밥가능", "혼술가능");
    }

    /**
     * 파생 메서드({@code findByUserIdAndNameStartingWith})는 파라미터를 그대로 패턴에 넣어
     * {@code %} 하나면 전량이 내려온다. escape 절이 실제로 DB까지 전달되는지는 여기서만 확인된다 —
     * 서비스 단위 테스트는 어떤 패턴을 넘겼는지까지만 볼 수 있다.
     */
    @Test
    @DisplayName("이스케이프된 와일드카드는 문자 그대로 매칭된다")
    void searchByNamePattern_escapedWildcardIsLiteral() {
        tagRepository.save(Tag.builder().user(user).name("100%만족").build());
        tagRepository.save(Tag.builder().user(user).name("혼밥가능").build());

        List<Tag> literal = tagRepository.searchByNamePattern(user.getId(), "100!%%", PageRequest.of(0, 20));
        assertThat(literal).extracting(Tag::getName).containsExactly("100%만족");

        // 이스케이프하지 않은 %는 여전히 와일드카드다 — 그래서 서비스가 반드시 이스케이프해야 한다.
        List<Tag> wildcard = tagRepository.searchByNamePattern(user.getId(), "%", PageRequest.of(0, 20));
        assertThat(wildcard).hasSize(2);
    }

    @Test
    @DisplayName("결과 수는 Pageable로 잘린다")
    void searchByNamePattern_respectsLimit() {
        tagRepository.save(Tag.builder().user(user).name("혼밥가능").build());
        tagRepository.save(Tag.builder().user(user).name("혼술가능").build());

        assertThat(tagRepository.searchByNamePattern(user.getId(), "혼%", PageRequest.of(0, 1)))
                .hasSize(1);
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
