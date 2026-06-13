package com.nameless0422.MenuPick.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.nameless0422.MenuPick.common.config.JpaConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    @DisplayName("사용자를 저장하고 조회한다")
    void save_and_findById() {
        User user = User.builder()
                .email("test@example.com")
                .nickname("테스트유저")
                .build();

        User saved = userRepository.save(user);

        Optional<User> found = userRepository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
        assertThat(found.get().getNickname()).isEqualTo("테스트유저");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("이메일로 사용자를 조회한다")
    void findByEmail() {
        User user = User.builder()
                .email("find@example.com")
                .nickname("검색유저")
                .build();
        userRepository.save(user);

        Optional<User> found = userRepository.findByEmail("find@example.com");
        assertThat(found).isPresent();
        assertThat(found.get().getNickname()).isEqualTo("검색유저");
    }

    @Test
    @DisplayName("소프트 삭제된 사용자는 deletedAt이 설정된다")
    void softDelete() {
        User user = User.builder()
                .email("delete@example.com")
                .nickname("삭제유저")
                .build();
        User saved = userRepository.save(user);

        saved.softDelete();
        userRepository.flush();

        User found = userRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.isDeleted()).isTrue();
        assertThat(found.getDeletedAt()).isNotNull();
    }
}
