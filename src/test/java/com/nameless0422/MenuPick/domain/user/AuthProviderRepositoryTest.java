package com.nameless0422.MenuPick.domain.user;

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
class AuthProviderRepositoryTest {

    @Autowired
    private AuthProviderRepository authProviderRepository;

    @Autowired
    private UserRepository userRepository;

    private User user;

    @BeforeEach
    void setUp() {
        user = userRepository.save(User.builder()
                .email("auth@example.com")
                .nickname("인증유저")
                .build());
    }

    @Test
    @DisplayName("소셜 로그인 정보를 저장하고 provider+socialId로 조회한다")
    void save_and_findByProviderAndSocialId() {
        AuthProvider provider = AuthProvider.builder()
                .user(user)
                .provider("KAKAO")
                .socialId("kakao_12345")
                .build();
        authProviderRepository.save(provider);

        Optional<AuthProvider> found = authProviderRepository
                .findByProviderAndSocialId("KAKAO", "kakao_12345");

        assertThat(found).isPresent();
        assertThat(found.get().getUser().getId()).isEqualTo(user.getId());
    }

    @Test
    @DisplayName("사용자 ID로 연동된 소셜 목록을 조회한다")
    void findAllByUserId() {
        authProviderRepository.save(AuthProvider.builder()
                .user(user).provider("KAKAO").socialId("kakao_1").build());
        authProviderRepository.save(AuthProvider.builder()
                .user(user).provider("GOOGLE").socialId("google_1").build());

        List<AuthProvider> providers = authProviderRepository.findAllByUserId(user.getId());
        assertThat(providers).hasSize(2);
    }
}
