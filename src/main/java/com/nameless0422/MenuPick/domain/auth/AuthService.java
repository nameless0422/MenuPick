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
import com.nameless0422.MenuPick.domain.user.UserHardDeleteService;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;
    private final UserHardDeleteService userHardDeleteService;
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
            if (user.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS)) {
                String email = trustedEmail(profile);
                user.reactivate(email != null ? email : user.getEmail(), profile.nickname());
            } else {
                // 유예기간 경과: 기존 데이터를 하드 삭제하고 새 계정으로 가입 처리
                userHardDeleteService.purge(user.getId());
                authProvider = createNewUser(providerName, profile);
                user = authProvider.getUser();
            }
        }

        return issueTokens(user.getId());
    }

    public TokenResponse refresh(String refreshToken) {
        if (!jwtTokenProvider.validateRefreshToken(refreshToken)) {
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
        try {
            // 같은 이메일로 가입된 유저가 있으면 새 계정을 만들지 않고
            // 해당 유저에 소셜 연동만 추가한다 (이메일 기준 자동 통합).
            // 단, 제공자가 소유를 검증한 이메일만 통합·저장에 사용한다 — 미검증 이메일을
            // 신뢰하면 공격자가 타인 주소를 자기 소셜 계정에 넣어 기존 계정을 탈취할 수 있다.
            String email = trustedEmail(profile);
            User user = findUserByEmail(email)
                    .orElseGet(() -> userRepository.save(
                            User.builder()
                                    .email(email)
                                    .nickname(profile.nickname())
                                    .build()
                    ));

            return authProviderRepository.save(
                    AuthProvider.builder()
                            .user(user)
                            .provider(providerName)
                            .socialId(profile.socialId())
                            .build()
            );
        } catch (DataIntegrityViolationException e) {
            return authProviderRepository
                    .findByProviderAndSocialId(providerName, profile.socialId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
        }
    }

    private String trustedEmail(OAuthUserProfile profile) {
        if (!profile.emailVerified() || profile.email() == null || profile.email().isBlank()) {
            return null;
        }
        return profile.email();
    }

    private Optional<User> findUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findByEmail(email);
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
