package com.nameless0422.MenuPick.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 발급·검증 담당.
 *
 * <p>검증은 {@link #parseAccessToken}/{@link #parseRefreshToken}이 <b>토큰당 한 번만</b> 파싱해
 * {@link Claims}를 돌려주는 형태다 — 호출측은 이 Claims에서 userId·token_type을 꺼내 쓰므로
 * "검증 후 다시 파싱"으로 인한 중복 서명 검증이 발생하지 않는다.
 *
 * <p>서명 오류·만료·형식 오류 등 모든 실패는 예외 대신 빈 Optional로 표현한다.
 * 파싱 예외가 필터/서비스 밖으로 새어 나가 500이 되는 경로를 원천 차단하기 위함이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtTokenProvider {

    /** 서버 간 시계 오차 허용치 — 이 범위 내의 exp/nbf 차이는 만료로 보지 않는다. */
    private static final long CLOCK_SKEW_SECONDS = 60L;

    private static final String CLAIM_TOKEN_TYPE = "token_type";
    private static final String TOKEN_TYPE_ACCESS = "access";
    private static final String TOKEN_TYPE_REFRESH = "refresh";

    private final JwtProperties jwtProperties;
    private SecretKey key;
    private JwtParser parser;

    @PostConstruct
    void init() {
        this.key = Keys.hmacShaKeyFor(
                jwtProperties.secret().getBytes(StandardCharsets.UTF_8));
        this.parser = Jwts.parser()
                .verifyWith(key)
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .build();
    }

    public String createAccessToken(Long userId) {
        return createToken(userId, jwtProperties.accessTokenExpiry(), TOKEN_TYPE_ACCESS);
    }

    public String createRefreshToken(Long userId) {
        return createToken(userId, jwtProperties.refreshTokenExpiry(), TOKEN_TYPE_REFRESH);
    }

    /** Access Token으로서 유효하면 Claims, 아니면 빈 Optional. */
    public Optional<Claims> parseAccessToken(String token) {
        return parse(token, TOKEN_TYPE_ACCESS);
    }

    /** Refresh Token으로서 유효하면 Claims, 아니면 빈 Optional. */
    public Optional<Claims> parseRefreshToken(String token) {
        return parse(token, TOKEN_TYPE_REFRESH);
    }

    /** 이미 파싱된 Claims에서 userId를 꺼낸다. subject가 숫자가 아니면 null. */
    public Long getUserId(Claims claims) {
        if (claims == null || claims.getSubject() == null) {
            return null;
        }
        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            log.warn("JWT subject가 숫자가 아닙니다");
            return null;
        }
    }

    private String createToken(Long userId, long expiryMs, String tokenType) {
        Date now = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMs))
                .signWith(key)
                .compact();
    }

    private Optional<Claims> parse(String token, String expectedType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = parser.parseSignedClaims(token).getPayload();
            if (!expectedType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
                return Optional.empty();
            }
            return Optional.of(claims);
        } catch (JwtException | IllegalArgumentException e) {
            // ExpiredJwtException·SignatureException·MalformedJwtException 등 모두 여기로 모인다.
            // 토큰 원문은 자격증명이므로 로그에 남기지 않는다.
            log.debug("JWT 파싱 실패: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }
}
