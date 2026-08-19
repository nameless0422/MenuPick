package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.LoginAttemptLimiter;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.MeResponse;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.AuthProvider;
import com.nameless0422.MenuPick.domain.user.AuthProviderRepository;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserHardDeleteService;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 이메일+비밀번호 자체 계정.
 *
 * <p>별도 테이블 없이 {@link AuthProvider}의 {@code provider='LOCAL'} 행으로 표현한다.
 * {@code UNIQUE (provider, social_id)}가 그대로 이메일 중복 가입 제약이 된다.
 *
 * <p><b>핵심 불변식</b>: {@code users.email}에는 소유가 검증된 주소만 들어간다.
 * {@link AuthService}가 이 값을 기준으로 소셜 연동을 기존 계정에 자동 병합하기 때문에,
 * 미인증 주소가 섞이면 공격자가 남의 주소로 먼저 가입해 피해자의 소셜 로그인을 자기 계정으로
 * 끌어오는 탈취가 가능해진다. 그래서 가입 시점에는 이메일을 {@code auth_providers.social_id}에만
 * 두고, 메일 인증을 통과한 뒤에야 {@code users.email}로 옮긴다.
 *
 * <p>DB 작업은 {@link TransactionTemplate}으로 감싸고 메일 발송·Redis 접근은 그 밖에서 한다 —
 * {@link AuthService}가 외부 HTTP 호출을 트랜잭션 밖으로 뺀 것과 같은 이유로,
 * 느린 외부 호출이 DB 커넥션을 붙잡으면 커넥션 풀이 마른다.
 * 메일 발송은 여기서 한 걸음 더 나가 {@link AuthMailer}가 전용 풀로 넘긴다 —
 * 트랜잭션 밖이어도 요청 스레드에 남아 있으면 SMTP가 느려질 때 톰캣 스레드가 잠긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalAuthService {

    private static final Duration VERIFICATION_TTL = Duration.ofHours(24);
    private static final Duration RESET_TTL = Duration.ofMinutes(30);

    private final UserRepository userRepository;
    private final AuthProviderRepository authProviderRepository;
    private final UserHardDeleteService userHardDeleteService;
    private final LoginAttemptLimiter loginAttemptLimiter;
    private final PasswordEncoder passwordEncoder;
    private final AuthTokenStore authTokenStore;
    private final AuthMailer authMailer;
    private final TokenIssuer tokenIssuer;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * 존재하지 않는 계정으로 로그인을 시도해도 실제 계정과 같은 시간이 걸리게 하는 비교 대상.
     *
     * <p>임의의 상수 문자열을 쓰면 인코더가 형식을 알아보지 못해 즉시 false를 반환해 버리고,
     * 오히려 응답 시간 차이로 계정 존재 여부가 드러난다. Argon2는 검증 비용이 커서
     * (설정상 수십 ms) 이 차이가 더 두드러지므로, 기동 시 실제 해시를 만들어 둔다.
     */
    private String dummyHash;

    @PostConstruct
    void initDummyHash() {
        dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    // ---- 가입 ----

    /** 계정을 만들고 인증 메일을 보낸다. 인증 전까지는 로그인할 수 없다. */
    public void signup(String rawEmail, String rawPassword, String rawNickname) {
        String email = normalize(rawEmail);
        String nickname = rawNickname.trim();

        String encodedPassword = passwordEncoder.encode(rawPassword);
        Long userId = inTransaction(() -> createOrReplacePendingAccount(email, encodedPassword, nickname));

        // 발송은 전용 풀에서 비동기로 일어나고 실패해도 예외가 올라오지 않는다(AuthMailer 참고).
        // 즉 가입 응답은 SMTP 왕복을 기다리지 않는다. 메일이 끝내 안 나가면 계정은 미인증
        // 상태로 남고, 사용자는 재발송 버튼이나 같은 주소의 재가입으로 이어갈 수 있다.
        sendVerification(userId, email);
    }

    private Long createOrReplacePendingAccount(String email, String encodedPassword, String nickname) {
        Optional<AuthProvider> existing =
                authProviderRepository.findByProviderAndSocialId(AuthProvider.LOCAL, email);

        if (existing.isPresent()) {
            AuthProvider provider = existing.get();
            User user = provider.getUser();

            if (user.isEmailVerified()) {
                throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
            }

            // 인증을 마치지 않은 계정은 로그인이 불가능해 딸린 데이터가 없다. 같은 주소의 새
            // 가입이 덮어쓰게 두지 않으면, 남의 주소로 먼저 가입해 두는 것만으로 그 사람의
            // 가입을 영구히 막을 수 있다(주소 선점).
            // 자기가 이미 쓰던 이름을 그대로 다시 보낸 경우는 중복이 아니다 — 자기 행이
            // 인덱스를 점유하고 있어 그대로 검사하면 자기 이름에 자기가 걸린다.
            if (!nickname.equals(user.getNickname())) {
                checkNicknameAvailable(nickname);
            }
            provider.changePassword(encodedPassword);
            user.updateNickname(nickname);
            return user.getId();
        }

        checkNicknameAvailable(nickname);

        // 인증 전이므로 email은 비워 둔다 — 위 클래스 주석의 불변식.
        User user = userRepository.save(User.builder()
                .nickname(nickname)
                .emailVerified(false)
                .build());

        authProviderRepository.save(AuthProvider.builder()
                .user(user)
                .provider(AuthProvider.LOCAL)
                .socialId(email)
                .passwordHash(encodedPassword)
                .build());

        return user.getId();
    }

    /**
     * 닉네임은 유일해야 한다(V5 마이그레이션).
     *
     * <p>여기서 미리 보는 이유는 오직 메시지 때문이다. 검사를 건너뛰어도 DB의 UNIQUE 제약이
     * 막지만, 그때 사용자가 받는 응답은 "데이터 무결성 제약 조건을 위반했습니다"라 어느 칸을
     * 고쳐야 하는지 알 수 없다. 이메일 중복을 미리 보는 것과 같은 이유다.
     *
     * <p>검사와 저장 사이의 경쟁은 남는다. 그 창으로 들어온 요청은 제약 위반 → 409로 끝나며,
     * 자체 가입은 사용자가 직접 다시 시도하면 되는 경로라 재시도를 따로 넣지 않는다
     * (소셜 로그인은 사용자가 개입할 수 없어 {@link AuthService}가 재시도한다).
     */
    private void checkNicknameAvailable(String nickname) {
        if (userRepository.existsByNickname(nickname)) {
            throw new BusinessException(ErrorCode.NICKNAME_ALREADY_REGISTERED);
        }
    }

    // ---- 로그인 ----

    public TokenResponse login(String rawEmail, String rawPassword) {
        String email = normalize(rawEmail);
        loginAttemptLimiter.checkAllowed(email);

        Authenticated result;
        try {
            result = inTransaction(() -> authenticate(email, rawPassword));
        } catch (BusinessException e) {
            // 비밀번호가 틀린 경우만 센다. 미인증·잠긴 계정까지 세면 정상 사용자가
            // 안내를 보려고 재시도하는 것만으로 계정이 잠긴다.
            if (e.getErrorCode() == ErrorCode.INVALID_CREDENTIALS) {
                loginAttemptLimiter.recordFailure(email);
            }
            throw e;
        }

        if (result.withdrawnPastGrace()) {
            // 유예기간이 지난 탈퇴 계정. 인증 트랜잭션 안에서 예외를 던지며 정리하면 정리까지
            // 함께 롤백되므로, 트랜잭션을 닫은 뒤 삭제한다. 자격증명 행도 사라지므로
            // 이 시도는 실패로 끝나고 같은 주소로 새로 가입할 수 있게 된다.
            userHardDeleteService.purge(result.userId());
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        loginAttemptLimiter.reset(email);
        return tokenIssuer.issue(result.userId());
    }

    /** 인증 결과. 유예기간이 지난 탈퇴 계정은 트랜잭션 밖에서 정리해야 해서 따로 표시한다. */
    private record Authenticated(Long userId, boolean withdrawnPastGrace) {}

    private Authenticated authenticate(String email, String rawPassword) {
        AuthProvider provider = authProviderRepository
                .findByProviderAndSocialId(AuthProvider.LOCAL, email)
                .orElse(null);

        if (provider == null || provider.getPasswordHash() == null) {
            // 계정이 없어도 해시 비교를 한 번 수행한다 — 건너뛰면 응답 시간 차이만으로
            // 어떤 주소가 가입돼 있는지 알아낼 수 있다.
            passwordEncoder.matches(rawPassword, dummyHash);
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (!passwordEncoder.matches(rawPassword, provider.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        User user = provider.getUser();

        if (!user.isEmailVerified()) {
            throw new BusinessException(ErrorCode.EMAIL_NOT_VERIFIED);
        }

        if (user.isDeleted()) {
            if (!user.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS, LocalDateTime.now(clock))) {
                return new Authenticated(user.getId(), true);
            }
            // 이메일은 이미 검증된 값이므로 그대로 둔다(null을 넘기면 덮어쓰지 않는다).
            user.reactivate(null, user.getNickname());
        }

        return new Authenticated(user.getId(), false);
    }

    // ---- 메일 인증 ----

    /** 인증을 마치고 곧바로 로그인시킨다 — 여기서 다시 로그인을 요구할 이유가 없다. */
    public TokenResponse verifyEmail(String token) {
        Long pendingUserId = authTokenStore.consume(AuthTokenStore.Purpose.VERIFY_EMAIL, token)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));

        Verified result = inTransaction(() -> completeVerification(pendingUserId));

        if (result.ownerToPurge() != null) {
            // 유예기간이 지난 탈퇴 계정이 이 주소를 붙잡고 있다. 같은 트랜잭션에서 지울 수 없다 —
            // purge가 벌크 삭제 후 영속성 컨텍스트를 비워 들고 있던 엔티티가 죽고, 무엇보다
            // Hibernate는 플러시 때 UPDATE를 DELETE보다 먼저 내보내므로 users.email을 채우는
            // UPDATE가 옛 행의 DELETE보다 앞서 나가 uq_users_email에 걸린다.
            // 로그인 경로가 같은 상황을 트랜잭션 밖에서 정리하는 것과 같은 방식이다.
            userHardDeleteService.purge(result.ownerToPurge());
            // 주소를 붙잡던 행이 사라졌으니 이번에는 병합 없이 이 계정이 그 주소의 주인이 된다.
            result = inTransaction(() -> completeVerification(pendingUserId));
        }

        return tokenIssuer.issue(result.userId());
    }

    /**
     * 메일 인증 결과.
     *
     * <p>유예기간이 지난 동일 주소 탈퇴 계정은 트랜잭션 밖에서 정리해야 해서 따로 표시한다
     * (로그인 경로의 {@link Authenticated}와 같은 이유).
     */
    private record Verified(Long userId, Long ownerToPurge) {}

    private Verified completeVerification(Long pendingUserId) {
        AuthProvider provider = authProviderRepository
                .findByUserIdAndProvider(pendingUserId, AuthProvider.LOCAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));

        User pending = provider.getUser();
        if (pending.isEmailVerified()) {
            return new Verified(pending.getId(), null);
        }

        String email = provider.getSocialId();

        // 같은 주소로 이미 소셜 가입한 계정이 있으면 새 계정을 따로 두지 않고 그쪽에 붙인다.
        // 메일 인증 통과가 곧 주소 소유의 증명이므로 안전한 병합이다. 가입 시점에 미리
        // 막지 않는 이유는, 그때는 소유가 증명되지 않아 "이 주소에 계정이 있다"는 사실만
        // 흘리게 되기 때문이다. users.email의 UNIQUE 제약을 지키는 역할도 겸한다.
        Optional<User> owner = userRepository.findByEmail(email);
        if (owner.isPresent()) {
            User target = owner.get();

            // findByEmail은 soft delete를 거르지 않는다. 탈퇴한 계정에 그대로 붙이면
            // deletedAt이 남은 채 로그인까지 되어, 사용자는 예전 메뉴가 다 보이니 복귀했다고
            // 믿지만 유예기간이 끝나는 순간 WithdrawnUserCleanupScheduler가 이 계정을 잡아
            // 메뉴·식당·히스토리를 전부 하드 삭제한다. 탈퇴 계정을 만났을 때의 판단은
            // 소셜 경로(AuthService.resolveUser)·자체 로그인(authenticate)과 같아야 한다 —
            // 유예 안이면 되살리고, 지났으면 옛 데이터를 버리고 새로 시작한다.
            if (target.isDeleted()) {
                if (!target.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS, LocalDateTime.now(clock))) {
                    return new Verified(null, target.getId());
                }
                // 주소는 이미 검증된 값이라 그대로 두고(null을 넘기면 덮어쓰지 않는다),
                // 닉네임도 쓰던 것을 지킨다 — 탈퇴 중에도 그 행이 인덱스를 붙잡고 있어
                // 다시 배정받으려 하면 자기 이름을 자기가 빼앗긴다.
                target.reactivate(null, target.getNickname());
            }

            provider.moveTo(target);

            // FK가 아직 pending을 가리키는 상태로 삭제가 먼저 나가지 않도록 이동을 먼저 반영한다.
            authProviderRepository.flush();

            // 인증 전이라 로그인한 적이 없고, 따라서 딸린 데이터도 없다.
            userRepository.delete(pending);
            return new Verified(target.getId(), null);
        }

        pending.verifyEmail(email);
        return new Verified(pending.getId(), null);
    }

    /** 가입 여부를 응답으로 흘리지 않도록, 보낼 대상이 없어도 성공으로 끝낸다. */
    public void resendVerification(String rawEmail) {
        String email = normalize(rawEmail);

        Long userId = inTransaction(() -> authProviderRepository
                .findByProviderAndSocialId(AuthProvider.LOCAL, email)
                .map(AuthProvider::getUser)
                .filter(user -> !user.isEmailVerified())
                .map(User::getId)
                .orElse(null));

        if (userId != null) {
            sendVerification(userId, email);
        }
    }

    private void sendVerification(Long userId, String email) {
        String token = authTokenStore.issue(
                AuthTokenStore.Purpose.VERIFY_EMAIL, userId, VERIFICATION_TTL);
        authMailer.sendVerification(email, token, VERIFICATION_TTL);
    }

    // ---- 비밀번호 재설정 ----

    /** {@link #resendVerification}과 같은 이유로 대상이 없어도 성공으로 끝낸다. */
    public void requestPasswordReset(String rawEmail) {
        String email = normalize(rawEmail);

        Long userId = inTransaction(() -> authProviderRepository
                .findByProviderAndSocialId(AuthProvider.LOCAL, email)
                .map(AuthProvider::getUser)
                .filter(User::isEmailVerified)
                .filter(user -> !user.isDeleted())
                .map(User::getId)
                .orElse(null));

        if (userId != null) {
            String token = authTokenStore.issue(
                    AuthTokenStore.Purpose.PASSWORD_RESET, userId, RESET_TTL);
            authMailer.sendPasswordReset(email, token, RESET_TTL);
        }
    }

    public TokenResponse confirmPasswordReset(String token, String rawPassword) {

        Long userId = authTokenStore.consume(AuthTokenStore.Purpose.PASSWORD_RESET, token)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_AUTH_TOKEN));

        String encodedPassword = passwordEncoder.encode(rawPassword);
        inTransaction(() -> {
            localProvider(userId).changePassword(encodedPassword);
            return null;
        });

        // Refresh Token은 사용자당 한 개(refresh:{userId})라, 새로 발급하면 기존 세션이
        // 밀려난다. 비밀번호를 빼앗겼다 되찾는 흐름이므로 다른 세션이 남으면 안 된다 —
        // RefreshTokenStore.save가 회전 유예 값까지 함께 버리는 이유도 이것이다.
        return tokenIssuer.issue(userId);
    }

    public TokenResponse changePassword(Long userId, String currentPassword, String newPassword) {

        inTransaction(() -> {
            AuthProvider provider = localProvider(userId);

            if (!passwordEncoder.matches(currentPassword, provider.getPasswordHash())) {
                throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
            }

            provider.changePassword(passwordEncoder.encode(newPassword));
            return null;
        });

        // 재설정과 같은 이유로 세션을 새로 발급한다.
        return tokenIssuer.issue(userId);
    }

    // ---- 계정 정보 ----

    @Transactional(readOnly = true)
    public MeResponse me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        // 인증 수단을 한 번에 가져와 비밀번호 보유 여부와 연동 목록을 함께 만든다 —
        // 화면이 둘을 같이 쓰므로(연동 해제 버튼을 열어줄지가 hasPassword에 달렸다) 나눠 조회할 이유가 없다.
        List<AuthProvider> providers = authProviderRepository.findAllByUserId(userId);

        boolean hasPassword = providers.stream()
                .anyMatch(provider -> provider.isLocal() && provider.getPasswordHash() != null);

        // LOCAL은 제외한다 — 연동한 소셜 계정이 아니라 자체 자격증명이고, 위 hasPassword가 이미 알려준다.
        // 정렬은 목록 순서가 조회마다 흔들리지 않게 하려는 것뿐이다.
        List<String> linkedProviders = providers.stream()
                .filter(provider -> !provider.isLocal())
                .map(AuthProvider::getProvider)
                .sorted()
                .toList();

        return new MeResponse(user.getEmail(), user.getNickname(), hasPassword, linkedProviders);
    }

    // ---- 공통 ----

    private AuthProvider localProvider(Long userId) {
        AuthProvider provider = authProviderRepository
                .findByUserIdAndProvider(userId, AuthProvider.LOCAL)
                .orElseThrow(() -> new BusinessException(ErrorCode.LOCAL_ACCOUNT_REQUIRED));

        if (provider.getPasswordHash() == null) {
            throw new BusinessException(ErrorCode.LOCAL_ACCOUNT_REQUIRED);
        }
        return provider;
    }

    /**
     * 이메일은 소문자로 정규화해 저장·조회한다.
     *
     * <p>스키마 콜레이션(utf8mb4_unicode_ci)이 이미 대소문자를 구분하지 않지만, 콜레이션이
     * 바뀌어도 "Foo@x.com"과 "foo@x.com"이 갈라지지 않도록 애플리케이션에서도 맞춘다.
     */
    private String normalize(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private <T> T inTransaction(Supplier<T> work) {
        return transactionTemplate.execute(status -> work.get());
    }
}
