package com.nameless0422.MenuPick.domain.user;

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
class AuthProviderRepositoryTest extends AbstractIntegrationTest {

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
    @DisplayName("자체 계정은 이메일을 social_id로, 해시를 password_hash로 저장한다 (V4 마이그레이션)")
    void save_localAccount() {
        // social_id는 V4에서 VARCHAR(100) → VARCHAR(255)로 넓혔다. 마이그레이션이 빠지면
        // users.email과 같은 길이의 주소가 들어가지 못해 가입 자체가 깨진다.
        String longEmail = "a".repeat(243) + "@example.com"; // 255자
        String encoded = "{bcrypt}$2a$10$abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQR";

        authProviderRepository.save(AuthProvider.builder()
                .user(user)
                .provider(AuthProvider.LOCAL)
                .socialId(longEmail)
                .passwordHash(encoded)
                .build());

        Optional<AuthProvider> found = authProviderRepository
                .findByProviderAndSocialId(AuthProvider.LOCAL, longEmail);

        assertThat(found).isPresent();
        assertThat(found.get().getPasswordHash()).isEqualTo(encoded);
        assertThat(found.get().isLocal()).isTrue();
    }

    @Test
    @DisplayName("소셜 계정은 password_hash가 비어 있다")
    void save_socialAccount_hasNoPasswordHash() {
        authProviderRepository.save(AuthProvider.builder()
                .user(user).provider("KAKAO").socialId("kakao_no_pw").build());

        Optional<AuthProvider> found = authProviderRepository
                .findByProviderAndSocialId("KAKAO", "kakao_no_pw");

        assertThat(found).isPresent();
        assertThat(found.get().getPasswordHash()).isNull();
        assertThat(found.get().isLocal()).isFalse();
    }

    @Test
    @DisplayName("사용자 ID와 provider로 자체 계정 자격증명을 조회한다")
    void findByUserIdAndProvider() {
        authProviderRepository.save(AuthProvider.builder()
                .user(user).provider("KAKAO").socialId("kakao_mixed").build());
        authProviderRepository.save(AuthProvider.builder()
                .user(user).provider(AuthProvider.LOCAL).socialId("mixed@example.com")
                .passwordHash("{bcrypt}$2a$10$hash").build());

        Optional<AuthProvider> found =
                authProviderRepository.findByUserIdAndProvider(user.getId(), AuthProvider.LOCAL);

        assertThat(found).isPresent();
        assertThat(found.get().getSocialId()).isEqualTo("mixed@example.com");
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
