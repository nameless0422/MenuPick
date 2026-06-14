package com.nameless0422.MenuPick.common.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties(
                "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!",
                1800000L,   // 30분
                1209600000L // 14일
        );
        jwtTokenProvider = new JwtTokenProvider(properties);
        jwtTokenProvider.init();
    }

    @Test
    @DisplayName("Access Token을 생성하고 userId를 추출한다")
    void createAccessToken_and_getUserId() {
        String token = jwtTokenProvider.createAccessToken(1L);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Refresh Token을 생성하고 userId를 추출한다")
    void createRefreshToken_and_getUserId() {
        String token = jwtTokenProvider.createRefreshToken(42L);

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo(42L);
    }

    @Test
    @DisplayName("유효한 토큰은 validate가 true를 반환한다")
    void validate_validToken() {
        String token = jwtTokenProvider.createAccessToken(1L);

        assertThat(jwtTokenProvider.validate(token)).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰은 validate가 false를 반환한다")
    void validate_expiredToken() {
        JwtProperties shortExpiry = new JwtProperties(
                "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!",
                -1000L, // 이미 만료
                -1000L
        );
        JwtTokenProvider shortProvider = new JwtTokenProvider(shortExpiry);
        shortProvider.init();

        String token = shortProvider.createAccessToken(1L);

        assertThat(shortProvider.validate(token)).isFalse();
    }

    @Test
    @DisplayName("잘못된 시크릿으로 서명된 토큰은 validate가 false를 반환한다")
    void validate_wrongSecret() {
        JwtProperties otherProperties = new JwtProperties(
                "differentSecretKeyThatIsAlsoAtLeast256BitsLongForHS256!!",
                1800000L,
                1209600000L
        );
        JwtTokenProvider otherProvider = new JwtTokenProvider(otherProperties);
        otherProvider.init();

        String token = otherProvider.createAccessToken(1L);

        assertThat(jwtTokenProvider.validate(token)).isFalse();
    }

    @Test
    @DisplayName("null이나 빈 문자열은 validate가 false를 반환한다")
    void validate_nullOrEmpty() {
        assertThat(jwtTokenProvider.validate(null)).isFalse();
        assertThat(jwtTokenProvider.validate("")).isFalse();
        assertThat(jwtTokenProvider.validate("invalid.token.here")).isFalse();
    }
}
