package com.nameless0422.MenuPick.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * k6가 만든 토큰을 서버 파서가 그대로 받아들이는지 고정한다.
 *
 * <p>부하 테스트({@code scripts/k6/jwt.js})는 OAuth를 거치지 않고 Access Token을 직접 서명해
 * 만든다. 성립 근거는 {@link JwtAuthenticationFilter}가 서명·만료·{@code token_type}만 보고
 * DB나 Redis를 조회하지 않는다는 것이다. 그 조립이 서버와 한 글자라도 어긋나면 부하 테스트는
 * 실행 자체가 안 되는데, <b>실제로 부하를 걸어야 할 시점에야 401 더미로 드러난다</b>.
 *
 * <p>아래 토큰은 실제로 k6가 {@code scripts/k6/jwt.js}를 돌려 만들어낸 값이다. 두 구현이
 * 갈라지면(클레임 이름 변경, 서명 알고리즘 변경, base64 패딩 처리 변경 등) 여기서 먼저 깨진다.
 *
 * <p><b>만료는 일부러 무시한다.</b> 박아둔 토큰에는 발급 당시의 exp가 들어 있어 그대로
 * 검증하면 30분 뒤부터 영원히 실패하는 시한폭탄이 된다. 여기서 확인하려는 것은
 * "서명과 클레임 형식이 맞는가"이므로, 시계 오차 허용치를 크게 준 파서로 만료를 건너뛴다.
 */
class K6MintedTokenTest {

    /** {@code src/test/resources/application-test.yml}의 jwt.secret과 같은 값. */
    private static final String SECRET =
            "DXRGmMVjGW4b1OJtFaEzoaqjGtDElap1fCw30qQgXes3KR3ckr0Th5wZ7ubJTej5";

    /** k6가 위 시크릿으로 userId=42에 대해 만든 토큰. */
    private static final String K6_TOKEN =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJzdWIiOiI0MiIsInRva2VuX3R5cGUiOiJhY2Nlc3MiLCJpYXQiOjE3ODY4MTcyNzMsImV4cCI6MTc4NjgxOTA3M30"
                    + ".ZVn5-J6ysxUbLn3rIxbso-UiOrjS5F7GTQLKcgH_amU";

    /** 만료만 건너뛰고 서명은 그대로 검증하는 파서. 100년치 오차를 허용해 exp를 무력화한다. */
    private Claims parseIgnoringExpiry(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(100L * 365 * 24 * 60 * 60)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @Test
    @DisplayName("k6가 만든 서명을 서버 파서가 검증에 통과시킨다")
    void signatureIsAcceptedByServerParser() {
        // 서명이 어긋나면 여기서 SignatureException이 난다 — k6의 HMAC/base64url 조립이
        // jjwt와 다르다는 뜻이고, 그러면 부하 테스트의 모든 요청이 401이 된다.
        Claims claims = parseIgnoringExpiry(K6_TOKEN, SECRET);

        assertThat(claims).isNotNull();
    }

    @Test
    @DisplayName("클레임 형식이 서버가 읽는 모양과 같다 — sub는 숫자 문자열, token_type은 access")
    void claimShapeMatchesWhatServerReads() {
        Claims claims = parseIgnoringExpiry(K6_TOKEN, SECRET);

        // JwtTokenProvider.getUserId가 Long.parseLong(subject)로 되돌리므로 숫자 문자열이어야 한다.
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(Long.parseLong(claims.getSubject())).isEqualTo(42L);

        // 이 값이 없거나 refresh면 parseAccessToken이 거부한다.
        assertThat(claims.get("token_type")).isEqualTo("access");

        // 만료 클레임 자체는 들어 있어야 한다 — 없으면 영구 토큰이 되어 버린다.
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getIssuedAt()).isNotNull();
    }

    @Test
    @DisplayName("서버가 발급한 토큰과 헤더·클레임 구조가 같다")
    void sameShapeAsServerIssuedToken() {
        JwtTokenProvider provider = new JwtTokenProvider(
                new JwtProperties(SECRET, 1_800_000L, 1_209_600_000L));
        provider.init(); // @PostConstruct는 스프링 컨텍스트 밖에서 자동으로 불리지 않는다

        Claims serverIssued = parseIgnoringExpiry(provider.createAccessToken(42L), SECRET);
        Claims k6Minted = parseIgnoringExpiry(K6_TOKEN, SECRET);

        assertThat(k6Minted.getSubject()).isEqualTo(serverIssued.getSubject());
        assertThat(k6Minted.get("token_type")).isEqualTo(serverIssued.get("token_type"));
        assertThat(k6Minted.keySet())
                .as("서버가 넣는 클레임 집합과 같아야 한다 — 한쪽에만 있는 클레임이 생기면 조립이 갈라진 것이다")
                .isEqualTo(serverIssued.keySet());
    }

    @Test
    @DisplayName("다른 시크릿으로는 거부된다 — 위 테스트가 '무엇이든 통과'로 성립하는 게 아니다")
    void tokenSignedWithAnotherSecretIsRejected() {
        assertThatThrownBy(() ->
                parseIgnoringExpiry(K6_TOKEN, "aCompletelyDifferentSecretThatIsAtLeast256BitsLong!!"))
                .isInstanceOf(RuntimeException.class);
    }
}
