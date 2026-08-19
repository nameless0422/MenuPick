package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.OAuthUserProfile;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.AuthProvider;
import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.NicknameAllocator;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserHardDeleteService;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 제공자가 닉네임을 주지 않은 경우(프로필 동의 거부 등) 사용할 기본 닉네임 — users.nickname은 NOT NULL이다. */
    private static final String DEFAULT_NICKNAME = "메뉴픽 사용자";

    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;
    private final NicknameAllocator nicknameAllocator;
    private final UserHardDeleteService userHardDeleteService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtProperties jwtProperties;
    private final TokenIssuer tokenIssuer;
    private final TransactionTemplate transactionTemplate;
    private final List<OAuthProvider> oAuthProviders;
    private final Clock clock;

    /**
     * 소셜 로그인.
     *
     * <p>메서드 전체를 @Transactional로 감싸지 않는다 — 인가 코드 교환·프로필 조회는 외부 HTTP
     * 블로킹 호출이라, 트랜잭션 안에 두면 응답이 느려지는 만큼 DB 커넥션을 붙잡아 커넥션 풀이
     * 마른다. 외부 호출을 먼저 끝내고 DB 작업만 TransactionTemplate으로 감싼다.
     * (같은 빈의 @Transactional 메서드를 내부 호출하면 프록시를 타지 않아 무효이므로
     * 메서드 분리 대신 TransactionTemplate을 쓴다.)
     */
    public TokenResponse socialLogin(String providerName, String code) {
        OAuthProvider provider = findProvider(providerName);
        OAuthUserProfile profile = provider.getUserProfile(code);

        Long userId = resolveUserWithConflictRetry(providerName, profile);

        return tokenIssuer.issue(userId);
    }

    public TokenResponse refresh(String refreshToken) {
        Claims claims = jwtTokenProvider.parseRefreshToken(refreshToken)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다."));

        Long userId = jwtTokenProvider.getUserId(claims);
        if (userId == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, "유효하지 않은 Refresh Token입니다.");
        }

        String newAccessToken = jwtTokenProvider.createAccessToken(userId);
        String newRefreshToken = jwtTokenProvider.createRefreshToken(userId);

        // 비교와 교체를 Lua로 원자 실행 — 동시 요청이 둘 다 통과해 유효 토큰이 두 개 생기는 창을 막는다.
        // 돌려받은 값을 그대로 내려보내야 한다. 방금 회전에 밀려난 직전 토큰이 제시된 경우
        // (탭 두 개가 부팅하며 각자 refresh를 부르는 흔한 상황) 저장소는 새로 만든 토큰이 아니라
        // 이미 유효한 토큰을 돌려주고, 여기서 newRefreshToken을 고집하면 그 값이 버려져
        // 클라이언트가 저장소에 없는 토큰을 들고 다음 회전에서 탈취로 판정된다.
        String issuedRefreshToken = refreshTokenStore
                .rotate(userId, refreshToken, newRefreshToken, jwtProperties.refreshTokenExpiry())
                // 저장값·유예값 어느 쪽과도 불일치 = 유예 창 밖의 옛 토큰이거나 탈취된 토큰.
                // 스크립트가 키를 삭제했으므로 해당 유저의 모든 Refresh Token 체인이 무효화된다.
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.UNAUTHORIZED, "Refresh Token이 일치하지 않습니다."));

        return new TokenResponse(newAccessToken, issuedRefreshToken);
    }

    public void logout(Long userId) {
        refreshTokenStore.delete(userId);
    }

    @Transactional
    public void withdraw(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
        user.softDelete(LocalDateTime.now(clock));
        refreshTokenStore.delete(userId);
    }

    /**
     * 계정 조회·생성을 트랜잭션 안에서 수행한다.
     *
     * <p>동시에 같은 소셜 계정으로 첫 로그인이 들어오면 UNIQUE 제약에 걸린다. 제약 위반이 난
     * 트랜잭션은 rollback-only로 마킹돼 같은 트랜잭션 안에서 재조회해봐야 커밋 시점에
     * UnexpectedRollbackException이 나므로, 새 트랜잭션에서 한 번 더 시도한다.
     * (두 번째 시도에서는 상대 트랜잭션이 커밋한 행이 조회되어 정상 종료된다.)
     */
    private Long resolveUserWithConflictRetry(String providerName, OAuthUserProfile profile) {
        try {
            return inTransaction(() -> resolveUser(providerName, profile));
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            log.info("소셜 로그인 동시 가입 충돌 — 새 트랜잭션에서 재시도합니다: provider={}", providerName);
            return inTransaction(() -> resolveUser(providerName, profile));
        }
    }

    private Long resolveUser(String providerName, OAuthUserProfile profile) {
        AuthProvider authProvider = authProviderRepository
                .findByProviderAndSocialId(providerName, profile.socialId())
                .orElseGet(() -> createNewUser(providerName, profile));

        User user = authProvider.getUser();
        if (user.isDeleted()) {
            if (user.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS, LocalDateTime.now(clock))) {
                String email = trustedEmail(profile);
                user.reactivate(
                        email != null ? email : user.getEmail(),
                        keptOrFreeNickname(user, nickname(profile, user.getNickname())));
            } else {
                // 유예기간 경과: 기존 데이터를 하드 삭제하고 새 계정으로 가입 처리
                userHardDeleteService.purge(user.getId());
                authProvider = createNewUser(providerName, profile);
                user = authProvider.getUser();
            }
        }

        return user.getId();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    private AuthProvider createNewUser(String providerName, OAuthUserProfile profile) {
        // 같은 이메일로 가입된 유저가 있으면 새 계정을 만들지 않고
        // 해당 유저에 소셜 연동만 추가한다 (이메일 기준 자동 통합).
        // 단, 제공자가 소유를 검증한 이메일만 통합·저장에 사용한다 — 미검증 이메일을
        // 신뢰하면 공격자가 타인 주소를 자기 소셜 계정에 넣어 기존 계정을 탈취할 수 있다.
        // 반대 방향도 같다: users.email에는 검증된 주소만 들어가므로(V4 마이그레이션 주석 참고)
        // 미인증 자체 계정이 병합 대상으로 잡히는 일은 없다.
        String email = trustedEmail(profile);
        User user = findUserByEmail(email)
                .orElseGet(() -> userRepository.save(
                        User.builder()
                                .email(email)
                                .emailVerified(email != null)
                                .nickname(nicknameAllocator.allocate(nickname(profile, DEFAULT_NICKNAME)))
                                .build()
                ));

        return authProviderRepository.save(
                AuthProvider.builder()
                        .user(user)
                        .provider(providerName)
                        .socialId(profile.socialId())
                        .build()
        );
    }

    /**
     * 복구되는 계정이 쓸 닉네임.
     *
     * <p>자기가 쓰던 이름 그대로면 비어 있는지 볼 필요가 없다 — 탈퇴 중에도 그 행이 인덱스에
     * 남아 이름을 붙잡고 있었으므로, 확인하면 "이미 쓰임"으로 나와 자기 이름을 자기가 빼앗긴다.
     * 제공자가 그 사이 바꾼 이름을 준 경우에만 빈자리를 찾는다.
     */
    private String keptOrFreeNickname(User user, String desired) {
        return desired.equals(user.getNickname()) ? desired : nicknameAllocator.allocate(desired);
    }

    /**
     * 제공자가 닉네임 제공에 동의받지 못하면 nickname이 null로 온다.
     * users.nickname은 NOT NULL이므로 대체값으로 채운다.
     */
    private String nickname(OAuthUserProfile profile, String fallback) {
        String nickname = profile.nickname();
        return (nickname == null || nickname.isBlank()) ? fallback : nickname;
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
}
