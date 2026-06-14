package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.AuthProvider;
import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final StringRedisTemplate redisTemplate;
    private final JwtProperties jwtProperties;
    private final List<OAuthProvider> oAuthProviders;

    @Transactional
    public TokenResponse socialLogin(String providerName, String code) {
        OAuthProvider provider = findProvider(providerName);
        OAuthUserProfile profile = provider.getUserProfile(code);

        AuthProvider authProvider = authProviderRepository
                .findByProviderAndSocialId(providerName, profile.socialId())
                .orElseGet(() -> createNewUser(providerName, profile));

        User user = authProvider.getUser();
        if (user.isDeleted()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "탈퇴한 계정입니다.");
        }

        return issueTokens(user.getId());
    }

    public TokenResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validate(refreshToken)) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다.");
        }

        Long userId = jwtTokenProvider.getUserId(refreshToken);
        String stored = redisTemplate.opsForValue().get(refreshTokenKey(userId));

        if (stored == null || !stored.equals(refreshToken)) {
            redisTemplate.delete(refreshTokenKey(userId));
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "Refresh Token이 일치하지 않습니다.");
        }

        return issueTokens(userId);
    }

    @Transactional
    public void logout(Long userId) {
        redisTemplate.delete(refreshTokenKey(userId));
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        user.softDelete();
        redisTemplate.delete(refreshTokenKey(userId));
    }

    private TokenResponse issueTokens(Long userId) {
        String accessToken = jwtTokenProvider.createAccessToken(userId);
        String refreshToken = jwtTokenProvider.createRefreshToken(userId);

        redisTemplate.opsForValue().set(
                refreshTokenKey(userId),
                refreshToken,
                jwtProperties.refreshTokenExpiry(),
                TimeUnit.MILLISECONDS
        );

        return new TokenResponse(accessToken, refreshToken);
    }

    private AuthProvider createNewUser(String providerName, OAuthUserProfile profile) {
        User user = userRepository.save(
                User.builder()
                        .email(profile.email())
                        .nickname(profile.nickname())
                        .build()
        );

        return authProviderRepository.save(
                AuthProvider.builder()
                        .user(user)
                        .provider(providerName)
                        .socialId(profile.socialId())
                        .build()
        );
    }

    private OAuthProvider findProvider(String name) {
        return oAuthProviders.stream()
                .filter(p -> p.getProviderName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_INPUT, "지원하지 않는 로그인 제공자입니다: " + name));
    }

    private String refreshTokenKey(Long userId) {
        return "refresh:" + userId;
    }
}
