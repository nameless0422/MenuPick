package com.nameless0422.MenuPick.common.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = provider(
                "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!",
                1800000L,   // 30분
                1209600000L // 14일
        );
    }

    private JwtTokenProvider provider(String secret, long accessExpiry, long refreshExpiry) {
        JwtTokenProvider provider = new JwtTokenProvider(
                new JwtProperties(secret, accessExpiry, refreshExpiry));
        provider.init();
        return provider;
    }

    @Test
    @DisplayName("Access Token을 생성하고 Claims에서 userId를 추출한다")
    void createAccessToken_and_getUserId() {
        String token = jwtTokenProvider.createAccessToken(1L);

        assertThat(token).isNotBlank();
        Claims claims = jwtTokenProvider.parseAccessToken(token).orElseThrow();
        assertThat(jwtTokenProvider.getUserId(claims)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Refresh Token을 생성하고 Claims에서 userId를 추출한다")
    void createRefreshToken_and_getUserId() {
        String token = jwtTokenProvider.createRefreshToken(42L);

        assertThat(token).isNotBlank();
        Claims claims = jwtTokenProvider.parseRefreshToken(token).orElseThrow();
        assertThat(jwtTokenProvider.getUserId(claims)).isEqualTo(42L);
    }

    @Test
    @DisplayName("Refresh Token은 parseAccessToken이 빈 Optional을 반환한다")
    void parseAccessToken_rejectsRefreshToken() {
        String refreshToken = jwtTokenProvider.createRefreshToken(1L);

        assertThat(jwtTokenProvider.parseAccessToken(refreshToken)).isEmpty();
    }

    @Test
    @DisplayName("Access Token은 parseRefreshToken이 빈 Optional을 반환한다")
    void parseRefreshToken_rejectsAccessToken() {
        String accessToken = jwtTokenProvider.createAccessToken(1L);

        assertThat(jwtTokenProvider.parseRefreshToken(accessToken)).isEmpty();
    }

    @Test
    @DisplayName("허용 시계 오차(60초)를 넘겨 만료된 토큰은 빈 Optional을 반환한다")
    void parse_expiredBeyondClockSkew() {
        // 2분 전에 만료 — clock skew 60초로도 덮이지 않는다
        JwtTokenProvider expiredProvider = provider(
                "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!", -120_000L, -120_000L);

        String token = expiredProvider.createAccessToken(1L);

        assertThat(expiredProvider.parseAccessToken(token)).isEmpty();
    }

    @Test
    @DisplayName("허용 시계 오차(60초) 안에서 막 만료된 토큰은 여전히 유효하다")
    void parse_expiredWithinClockSkew() {
        // 1초 전에 만료 — 서버 간 시계 오차로 보고 통과시킨다
        JwtTokenProvider justExpiredProvider = provider(
                "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!", -1000L, -1000L);

        String token = justExpiredProvider.createAccessToken(1L);

        assertThat(justExpiredProvider.parseAccessToken(token)).isPresent();
    }

    @Test
    @DisplayName("잘못된 시크릿으로 서명된 토큰은 빈 Optional을 반환한다")
    void parse_wrongSecret() {
        JwtTokenProvider otherProvider = provider(
                "differentSecretKeyThatIsAlsoAtLeast256BitsLongForHS256!!", 1800000L, 1209600000L);

        String token = otherProvider.createAccessToken(1L);

        assertThat(jwtTokenProvider.parseAccessToken(token)).isEmpty();
    }

    @Test
    @DisplayName("null·빈 문자열·형식 오류 토큰은 예외 없이 빈 Optional을 반환한다")
    void parse_nullOrEmptyOrMalformed() {
        assertThat(jwtTokenProvider.parseAccessToken(null)).isEmpty();
        assertThat(jwtTokenProvider.parseAccessToken("")).isEmpty();
        assertThat(jwtTokenProvider.parseAccessToken("   ")).isEmpty();
        assertThat(jwtTokenProvider.parseAccessToken("invalid.token.here")).isEmpty();
        assertThat(jwtTokenProvider.parseRefreshToken("not-a-jwt-at-all")).isEmpty();
    }

    @Test
    @DisplayName("Claims가 null이거나 subject가 숫자가 아니면 userId는 null이다")
    void getUserId_nonNumericSubject() {
        assertThat(jwtTokenProvider.getUserId(null)).isNull();

        Claims claims = io.jsonwebtoken.Jwts.claims().subject("not-a-number").build();
        assertThat(jwtTokenProvider.getUserId(claims)).isNull();
    }

    @Test
    @DisplayName("token_type이 없는 토큰은 어느 쪽으로도 파싱되지 않는다")
    void parse_missingTokenType() {
        Optional<Claims> parsed = jwtTokenProvider.parseAccessToken(
                io.jsonwebtoken.Jwts.builder().subject("1").compact());

        assertThat(parsed).isEmpty();
    }
}
