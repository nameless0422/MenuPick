package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.security.JwtProperties;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 로그인 성공 시점의 토큰 발급을 한 곳에 모은다.
 *
 * <p>소셜 로그인({@link AuthService})과 자체 계정 로그인({@link LocalAuthService})이
 * 같은 규칙 — Access/Refresh 발급 + Refresh를 저장소에 기록 — 을 따라야 하므로,
 * 어느 한쪽 서비스의 private 메서드로 두지 않고 별도 컴포넌트로 뺐다.
 */
@Component
@RequiredArgsConstructor
public class TokenIssuer {

    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtProperties jwtProperties;

    public TokenResponse issue(Long userId) {
        String accessToken = jwtTokenProvider.createAccessToken(userId);
        String refreshToken = jwtTokenProvider.createRefreshToken(userId);

        refreshTokenStore.save(userId, refreshToken, jwtProperties.refreshTokenExpiry());

        return new TokenResponse(accessToken, refreshToken);
    }
}
