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
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** 브라우저가 만든 CSRF state의 허용 형식. 프론트는 32바이트 난수의 hex(64자)를 보낸다. */
    private static final java.util.regex.Pattern STATE_PATTERN =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;
    private final NicknameAllocator nicknameAllocator;
    private final UserHardDeleteService userHardDeleteService;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final AuthMailer authMailer;
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

        Resolved resolved = resolveUserWithConflictRetry(provider.getProviderName(), profile);

        if (resolved.userToPurge() != null) {
            // 유예기간이 지난 탈퇴 계정. 트랜잭션 안에서 지우며 예외를 던지면 정리까지 함께
            // 롤백되므로 트랜잭션을 닫은 뒤에 지운다(LocalAuthService.login과 같은 방식).
            // 연동 행도 함께 사라지므로 이번 시도는 실패로 끝난다. 예전과 달리 여기서 새 계정을
            // 만들지 않는다 — 소셜만으로는 계정을 만들지 않는다는 규칙이 복귀 경로에도 그대로 적용된다.
            userHardDeleteService.purge(resolved.userToPurge());
            throw new BusinessException(ErrorCode.SOCIAL_ACCOUNT_NOT_LINKED);
        }

        return tokenIssuer.issue(resolved.userId());
    }

    /**
     * 로그인한 사용자가 자기 계정에 소셜 계정을 붙인다.
     *
     * <p>외부 HTTP 호출을 트랜잭션 밖으로 빼는 이유는 {@link #socialLogin}과 같다.
     *
     * <p>같은 소셜 계정으로 연동 요청이 동시에 들어오면 uq_auth_provider_social에 걸린다.
     * 제약 위반이 난 트랜잭션은 rollback-only로 마킹돼 같은 트랜잭션에서 다시 조회해봐야
     * 커밋 시점에 UnexpectedRollbackException이 난다({@link #resolveUserWithConflictRetry}와 같은 이유).
     * 새 트랜잭션에서 다시 보면 상대가 커밋한 행이 보이므로, 재시도는 성공이 아니라
     * "이미 연동됨"이라는 정확한 거절로 끝난다.
     */
    public LinkResult linkSocialAccount(Long userId, String providerName, String code) {
        OAuthProvider provider = findProvider(providerName);
        OAuthUserProfile profile = provider.getUserProfile(code);
        // 경로에서 온 문자열이 아니라 제공자가 선언한 이름으로 저장한다 — findProvider가 대소문자를
        // 가리지 않으므로, 받은 값을 그대로 쓰면 "kakao" 행과 "KAKAO" 행이 갈라져 연동 조회가 엇나간다.
        String canonicalName = provider.getProviderName();

        Changed changed;
        try {
            changed = inTransaction(() -> link(userId, canonicalName, profile));
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            log.info("소셜 연동 동시 요청 충돌 — 새 트랜잭션에서 재시도합니다: provider={}", canonicalName);
            changed = inTransaction(() -> link(userId, canonicalName, profile));
        }
        return finishAuthMethodChange(userId, changed, canonicalName, true);
    }

    /** 연동·해제 결과. 세션이 갈리므로 새 토큰이 함께 나간다. */
    public record LinkResult(List<String> linkedProviders, TokenResponse tokens) {}

    /**
     * 로그인 수단이 바뀐 뒤의 공통 마무리 — 기존 세션 정리와 주인 통지.
     *
     * <p><b>세션을 끊는 이유.</b> 예전에는 연동 추가·해제가 {@code refreshTokenStore}를 전혀
     * 건드리지 않았다. 그래서 붙여 둔 수단을 해제해도 그 수단으로 만들어진 세션이 Refresh Token
     * 수명(14일) 동안 그대로 살아 있었다 — "이 로그인 방법을 없앴다"는 사용자의 행동이 실제로는
     * 아무것도 끊지 못했다. 비밀번호 변경이 같은 이유로 이미 이렇게 하고 있고,
     * {@code RefreshTokenStore.save}가 회전 유예 값까지 버리는 것도 같은 맥락이다.
     *
     * <p>새 토큰을 돌려주는 이유는 당사자까지 함께 끊기기 때문이다. 안 주면 사용자는 설정
     * 화면에서 연동 버튼을 한 번 눌렀다가 그대로 로그아웃당한다.
     *
     * <p><b>통지가 더 중요하다.</b> 세션을 끊는 것만으로는 Access Token을 탈취한 쪽이 자기 소셜
     * 계정을 붙여 둔 경우를 막지 못한다 — 그쪽은 자기 소셜로 다시 들어온다. 주인이 사실을
     * 알아야 연동을 해제하고 비밀번호를 바꿀 수 있다.
     *
     * <p>메일은 트랜잭션 밖에서 보낸다(AuthMailer가 전용 풀로 다시 넘긴다). 주소가 없는 계정
     * (#71 이전 소셜 생성 레거시)은 보낼 곳이 없어 건너뛴다 — 그 사실이 연동 자체를 막을
     * 이유는 아니다.
     */
    private LinkResult finishAuthMethodChange(Long userId, Changed changed,
                                              String providerName, boolean linked) {
        TokenResponse tokens = tokenIssuer.issue(userId);

        if (changed.email() != null) {
            authMailer.sendLoginMethodChanged(changed.email(), providerLabel(providerName), linked);
        }
        return new LinkResult(changed.providers(), tokens);
    }

    /** 로그인 수단 변경의 결과. 메일 발송을 트랜잭션 밖으로 빼기 위해 주소를 함께 들고 나온다. */
    private record Changed(List<String> providers, String email) {}

    /** 메일 문구에 쓰는, 사람이 읽는 이름. 모르는 값이면 그대로 쓴다(연동 자체는 이미 성공했다). */
    private static String providerLabel(String providerName) {
        return switch (providerName) {
            case "KAKAO" -> "카카오";
            case "GOOGLE" -> "구글";
            default -> providerName;
        };
    }

    private Changed link(Long userId, String providerName, OAuthUserProfile profile) {
        User user = activeUser(userId);

        authProviderRepository.findByProviderAndSocialId(providerName, profile.socialId())
                .ifPresent(linked -> {
                    // 남의 소셜 계정을 가져오게 두면, 그 계정의 주인이 다음 로그인부터
                    // 공격자 계정으로 들어오게 된다.
                    throw new BusinessException(userId.equals(linked.getUser().getId())
                            ? ErrorCode.SOCIAL_ALREADY_LINKED
                            : ErrorCode.SOCIAL_ACCOUNT_TAKEN);
                });

        List<AuthProvider> owned = authProviderRepository.findAllByUserId(userId);

        // 같은 제공자를 다른 소셜 계정으로 한 번 더 붙이려는 경우. DB에는 (user_id, provider)
        // UNIQUE가 없어서(V1 스키마) 여기서 막지 않으면 KAKAO 행이 둘 생기고, 그때부터
        // findByUserIdAndProvider가 결과 둘을 만나 조회 자체가 터진다(비밀번호 변경·해제 경로).
        if (owned.stream().anyMatch(mine -> mine.getProvider().equals(providerName))) {
            throw new BusinessException(ErrorCode.SOCIAL_ALREADY_LINKED);
        }

        AuthProvider saved = authProviderRepository.save(
                AuthProvider.builder()
                        .user(user)
                        .provider(providerName)
                        .socialId(profile.socialId())
                        .build()
        );

        // 재조회 대신 직접 이어 붙인다 — 방금 save한 행이 조회에 잡히려면 flush 시점에 기대게 된다.
        return new Changed(
                socialProviderNames(Stream.concat(owned.stream(), Stream.of(saved)).toList()),
                user.getEmail());
    }

    /**
     * 연동 해제.
     *
     * <p>외부 호출이 없어 통째로 @Transactional을 걸어도 되지만, 같은 빈의 @Transactional 메서드를
     * 안에서 부르면 프록시를 타지 않아 무효라 이 클래스의 다른 경로와 같은 방식을 유지한다.
     */
    public LinkResult unlinkSocialAccount(Long userId, String providerName) {
        String canonicalName = findProvider(providerName).getProviderName();
        Changed changed = inTransaction(() -> unlink(userId, canonicalName));
        return finishAuthMethodChange(userId, changed, canonicalName, false);
    }

    private Changed unlink(Long userId, String providerName) {
        User user = activeUser(userId);

        List<AuthProvider> owned = authProviderRepository.findAllByUserId(userId);

        AuthProvider target = owned.stream()
                .filter(provider -> provider.getProvider().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_LINK_NOT_FOUND));

        // 해제한 뒤에도 들어올 문이 하나는 남아야 한다. 비밀번호가 없는 계정이 마지막 연동을
        // 끊으면 그 계정에는 영원히 들어갈 수 없고, 탈퇴조차 못 해 데이터만 남는다.
        // LOCAL 행이 있어도 passwordHash가 비어 있으면 로그인 수단이 아니다(/me의 hasPassword와 같은 판단).
        boolean anotherWayIn = owned.stream()
                .filter(provider -> provider != target)
                .anyMatch(provider -> !provider.isLocal() || provider.getPasswordHash() != null);
        if (!anotherWayIn) {
            throw new BusinessException(ErrorCode.LAST_LOGIN_METHOD);
        }

        authProviderRepository.delete(target);

        return new Changed(
                socialProviderNames(owned.stream().filter(provider -> provider != target).toList()),
                user.getEmail());
    }

    /**
     * 연동 목록에는 소셜 제공자만 담는다 — LOCAL은 연동한 소셜 계정이 아니라 자체 자격증명이고,
     * 그 보유 여부는 /me의 hasPassword가 따로 알려준다.
     */
    private List<String> socialProviderNames(List<AuthProvider> providers) {
        return providers.stream()
                .filter(provider -> !provider.isLocal())
                .map(AuthProvider::getProvider)
                .sorted()
                .toList();
    }

    /** 탈퇴 처리된 계정에는 연동을 붙이거나 떼지 않는다 — 되살아날 때의 상태가 조용히 달라진다. */
    private User activeUser(Long userId) {
        return userRepository.findById(userId)
                .filter(user -> !user.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
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

    /**
     * 로그아웃. <b>Access Token이 없거나 만료됐어도 동작해야 한다.</b>
     *
     * <p>예전에는 이 경로가 {@code authenticated()}라 AT가 만료된 뒤에는 401이 났고, 401 응답에는
     * 쿠키를 지우는 {@code Set-Cookie}도 실리지 않았다. Refresh Token은 14일이고 {@code /refresh}는
     * permitAll이라, 공용 PC에서 다음 사용자가 {@code /refresh} 한 번으로 남의 세션을 되살릴 수
     * 있었다. 즉 "로그아웃"이 가장 필요한 상황에서 정확히 동작하지 않았다.
     *
     * <p>그래서 주체를 두 곳에서 찾는다 — 인증된 principal, 없으면 Refresh Token 쿠키.
     * 쿠키까지 없거나 파싱되지 않으면 지울 체인이 없다는 뜻이라 조용히 끝낸다(컨트롤러는
     * 어느 경우에도 만료 쿠키를 내려보낸다).
     *
     * <p>permitAll이 되면서 남이 강제로 로그아웃시키는 CSRF가 이론상 가능해지지만, refresh
     * 쿠키가 {@code SameSite=Strict}라 다른 사이트에서 실려 나가지 않고, 성공해도 피해는
     * 재로그인뿐이다.
     */
    public void logout(Long userId, String refreshToken) {
        Long target = userId != null ? userId : userIdFrom(refreshToken);
        if (target != null) {
            refreshTokenStore.delete(target);
        }
    }

    private Long userIdFrom(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return null;
        }
        return jwtTokenProvider.parseRefreshToken(refreshToken)
                .map(jwtTokenProvider::getUserId)
                .orElse(null);
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
    private Resolved resolveUserWithConflictRetry(String providerName, OAuthUserProfile profile) {
        try {
            return inTransaction(() -> resolveUser(providerName, profile));
        } catch (DataIntegrityViolationException | UnexpectedRollbackException e) {
            log.info("소셜 로그인 동시 가입 충돌 — 새 트랜잭션에서 재시도합니다: provider={}", providerName);
            return inTransaction(() -> resolveUser(providerName, profile));
        }
    }

    /**
     * 소셜 로그인 결과.
     *
     * <p>유예기간이 지난 탈퇴 계정은 트랜잭션 밖에서 정리해야 해서 따로 표시한다 —
     * LocalAuthService가 같은 상황을 다루는 방식과 같다.
     */
    private record Resolved(Long userId, Long userToPurge) {}

    private Resolved resolveUser(String providerName, OAuthUserProfile profile) {
        AuthProvider authProvider = authProviderRepository
                .findByProviderAndSocialId(providerName, profile.socialId())
                .orElseGet(() -> linkToVerifiedEmailOwner(providerName, profile));

        User user = authProvider.getUser();
        if (user.isDeleted()) {
            if (!user.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS, LocalDateTime.now(clock))) {
                return new Resolved(null, user.getId());
            }
            String email = trustedEmail(profile);
            user.reactivate(
                    email != null ? email : user.getEmail(),
                    keptOrFreeNickname(user, nickname(profile, user.getNickname())));
        }

        return new Resolved(user.getId(), null);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }

    /**
     * 아직 연동된 적 없는 소셜 계정으로 로그인이 들어왔을 때.
     *
     * <p>같은 이메일로 가입된 유저가 있으면 그 유저에 소셜 연동만 추가한다(이메일 기준 자동 통합).
     * 단, 제공자가 소유를 검증한 이메일만 통합에 사용한다 — 미검증 이메일을 신뢰하면 공격자가
     * 타인 주소를 자기 소셜 계정에 넣어 기존 계정을 탈취할 수 있다. 반대 방향도 같다:
     * users.email에는 검증된 주소만 들어가므로(V4 마이그레이션 주석 참고) 미인증 자체 계정이
     * 병합 대상으로 잡히는 일은 없다. 구글은 검증된 주소를 주므로 이 경로가 계속 동작한다.
     *
     * <p>찾지 못하면 <b>새 유저를 만들지 않고 거절한다</b>. 카카오는 비즈앱 전환(사업자 등록)
     * 없이는 이메일을 주지 않아, 예전처럼 여기서 계정을 만들면 users.email이 NULL인 채로 남는다.
     * 그 계정은 비밀번호 재설정도 안내 메일 수신도 불가능한 고아 계정이다. 그래서 가입은
     * 이메일로만 받고, 소셜은 로그인한 상태에서 명시적으로 붙이게 한다({@link #linkSocialAccount}).
     */
    private AuthProvider linkToVerifiedEmailOwner(String providerName, OAuthUserProfile profile) {
        User user = findUserByEmail(trustedEmail(profile))
                .orElseThrow(() -> new BusinessException(ErrorCode.SOCIAL_ACCOUNT_NOT_LINKED));

        // 명시적 연동 경로(link)가 두는 것과 같은 가드. DB에는 (user_id, provider) UNIQUE가
        // 없어(V1 스키마) 여기서 막지 않으면 같은 제공자 행이 둘 생기고, 그때부터
        // findByUserIdAndProvider가 결과 둘을 만나 조회 자체가 터진다 — 비밀번호 변경·연동
        // 해제 경로가 통째로 막힌다. 제공자가 계정당 이메일 유일성을 강제해 재현 조건이 좁을
        // 뿐이지, 한쪽에만 가드가 있는 상태는 그 전제에 기대고 있다는 뜻이다.
        boolean alreadyLinked = authProviderRepository.findAllByUserId(user.getId()).stream()
                .anyMatch(mine -> mine.getProvider().equals(providerName));
        if (alreadyLinked) {
            throw new BusinessException(ErrorCode.SOCIAL_ALREADY_LINKED);
        }

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

    /**
     * 제공자 동의 화면 URL을 만들어 돌려준다. 브라우저는 이 주소로 이동만 하면 된다.
     *
     * <p>state는 브라우저가 만들어 넘긴 값을 그대로 싣는다(대조 주체가 브라우저라서다).
     * 다만 남이 준 값을 URL에 넣는 자리이므로 형식을 검증한다 — 길이·문자 집합을 묶어
     * 제어문자나 개행이 섞여 들어가지 못하게 한다.
     */
    public String authorizeUrl(String providerName, String state) {
        if (state == null || !STATE_PATTERN.matcher(state).matches()) {
            throw new BusinessException(ErrorCode.INVALID_INPUT, "state 형식이 올바르지 않습니다.");
        }
        return findProvider(providerName).buildAuthorizeUrl(state);
    }

    private OAuthProvider findProvider(String name) {
        return oAuthProviders.stream()
                .filter(p -> p.getProviderName().equalsIgnoreCase(name))
                .findFirst()
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.INVALID_INPUT, "지원하지 않는 로그인 제공자입니다: " + name));
    }
}
