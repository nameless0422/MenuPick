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
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(
            ZonedDateTime.of(2026, 1, 15, 0, 30, 0, 0, KST).toInstant(), KST);
    private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);

    @Mock private UserRepository userRepository;
    @Mock private AuthProviderRepository authProviderRepository;
    @Mock private UserHardDeleteService userHardDeleteService;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenStore refreshTokenStore;
    @Mock private OAuthProvider kakaoProvider;

    private AuthService authService;
    private JwtProperties jwtProperties;

    /** 트랜잭션 경계만 흉내내는 최소 매니저 — 콜백 실행 여부만 검증하면 되므로 실제 리소스는 없다. */
    private static final PlatformTransactionManager NO_OP_TX_MANAGER = new PlatformTransactionManager() {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    };

    @BeforeEach
    void setUp() {
        jwtProperties = new JwtProperties(
                "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!",
                1800000L, 1209600000L
        );
        authService = new AuthService(
                userRepository, authProviderRepository,
                new NicknameAllocator(userRepository),
                userHardDeleteService,
                jwtTokenProvider, refreshTokenStore, jwtProperties,
                // 토큰 발급은 TokenIssuer로 옮겼지만, 이 테스트가 검증하는 것은
                // "로그인 성공 시 어떤 토큰이 저장·반환되는가"라 실제 구현을 그대로 넣는다.
                new TokenIssuer(userRepository, jwtTokenProvider, refreshTokenStore, jwtProperties),
                new TransactionTemplate(NO_OP_TX_MANAGER),
                List.of(kakaoProvider),
                FIXED_CLOCK
        );

        // TokenIssuer가 발급 직전에 계정이 살아 있는지 DB에서 다시 확인한다(탈퇴 계정에
        // 세션이 붙는 것을 막는 검사). 이 클래스의 관심사는 그 검사가 아니므로 기본값을
        // "활성 계정"으로 두고, 필요한 테스트만 개별로 덮어쓴다.
        lenient().when(userRepository.findById(any()))
                .thenReturn(Optional.of(User.builder().nickname("활성").build()));
    }

    @Test
    @DisplayName("신규 사용자 소셜 로그인 시 유저를 생성하고 토큰을 발급한다")
    void socialLogin_newUser() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "test@email.com", "테스트", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.empty());

        User savedUser = User.builder().email("test@email.com").nickname("테스트").build();
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willReturn(AuthProvider.builder().user(savedUser).provider("KAKAO").socialId("kakao_123").build());

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        assertThat(result.refreshToken()).isEqualTo("refresh_token");
        verify(userRepository).save(any(User.class));
        verify(authProviderRepository).save(any(AuthProvider.class));
        verify(refreshTokenStore).save(any(), eq("refresh_token"), eq(jwtProperties.refreshTokenExpiry()));
    }

    @Test
    @DisplayName("닉네임이 이미 쓰이고 있어도 소셜 로그인은 통과한다 — 번호를 붙여 가입시킨다")
    void socialLogin_duplicateNickname_getsSuffix() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_999", "new@email.com", "홍길동", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_999"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmail("new@email.com")).willReturn(Optional.empty());
        given(userRepository.existsByNickname("홍길동")).willReturn(true);
        given(userRepository.existsByNickname("홍길동2")).willReturn(false);

        given(userRepository.save(any(User.class))).willAnswer(inv -> inv.getArgument(0));
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        authService.socialLogin("KAKAO", "auth_code");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        // 닉네임은 카카오가 준 값이라 사용자가 고른 적이 없다. 흔한 이름이라는 이유로
        // 거절하면 그 사람은 로그인 자체를 못 한다.
        assertThat(captor.getValue().getNickname()).isEqualTo("홍길동2");
    }

    @Test
    @DisplayName("기존 사용자 소셜 로그인 시 새 유저를 생성하지 않고 토큰을 발급한다")
    void socialLogin_existingUser() {
        User existingUser = User.builder().email("existing@email.com").nickname("기존유저").build();
        AuthProvider existingProvider = AuthProvider.builder()
                .user(existingUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "existing@email.com", "기존유저", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(existingProvider));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
    }

    @Test
    @DisplayName("같은 이메일의 기존 유저가 있으면 새 계정 대신 소셜 연동만 추가한다")
    void socialLogin_sameEmail_linksProviderToExistingUser() {
        User existingUser = User.builder().email("same@email.com").nickname("기존유저").build();

        given(kakaoProvider.getProviderName()).willReturn("GOOGLE");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("google_123", "same@email.com", "구글닉네임", true));
        given(authProviderRepository.findByProviderAndSocialId("GOOGLE", "google_123"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmail("same@email.com")).willReturn(Optional.of(existingUser));
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willAnswer(inv -> inv.getArgument(0));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        TokenResponse result = authService.socialLogin("GOOGLE", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        verify(userRepository, never()).save(any(User.class));

        ArgumentCaptor<AuthProvider> captor = ArgumentCaptor.forClass(AuthProvider.class);
        verify(authProviderRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(existingUser);
        assertThat(captor.getValue().getProvider()).isEqualTo("GOOGLE");
    }

    @Test
    @DisplayName("미검증 이메일은 같은 이메일 유저가 있어도 통합하지 않고, 이메일 없이 새 계정을 생성한다")
    void socialLogin_unverifiedEmail_neverLinksToExistingUser() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("attacker_kakao", "victim@email.com", "공격자", false));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "attacker_kakao"))
                .willReturn(Optional.empty());

        User savedUser = User.builder().nickname("공격자").build();
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willReturn(AuthProvider.builder().user(savedUser).provider("KAKAO").socialId("attacker_kakao").build());

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        authService.socialLogin("KAKAO", "auth_code");

        // 피해자 계정으로의 통합 조회가 아예 일어나지 않아야 한다
        verify(userRepository, never()).findByEmail(any());

        // 미검증 이메일은 저장하지도 않는다 (uq_users_email 충돌 + 타인 주소 선점 방지)
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isNull();
    }

    @Test
    @DisplayName("프로필에 이메일이 없으면 통합 조회 없이 새 계정을 생성한다")
    void socialLogin_nullEmail_createsNewUserWithoutIntegration() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", null, "테스트", false));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.empty());

        User savedUser = User.builder().nickname("테스트").build();
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willReturn(AuthProvider.builder().user(savedUser).provider("KAKAO").socialId("kakao_123").build());

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        authService.socialLogin("KAKAO", "auth_code");

        verify(userRepository, never()).findByEmail(any());
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("닉네임 동의를 거부해 nickname이 null이어도 기본 닉네임으로 가입한다 (users.nickname NOT NULL)")
    void socialLogin_nullNickname_usesDefaultNickname() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", null, null, false));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.empty());

        User savedUser = User.builder().nickname("메뉴픽 사용자").build();
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willReturn(AuthProvider.builder().user(savedUser).provider("KAKAO").socialId("kakao_123").build());

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        authService.socialLogin("KAKAO", "auth_code");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isNotBlank();
    }

    @Test
    @DisplayName("동시 가입으로 UNIQUE 충돌이 나면 새 트랜잭션에서 재시도해 로그인을 완료한다")
    void socialLogin_concurrentSignupConflict_retriesInNewTransaction() {
        User winner = User.builder().email("race@email.com").nickname("먼저가입").build();
        AuthProvider winnerProvider = AuthProvider.builder()
                .user(winner).provider("KAKAO").socialId("kakao_race").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_race", "race@email.com", "먼저가입", true));
        // 1회차: 아직 안 보임 → 생성 시도 중 UNIQUE 충돌. 2회차: 상대 트랜잭션이 커밋한 행이 보인다.
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_race"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(winnerProvider));
        given(userRepository.findByEmail("race@email.com")).willReturn(Optional.empty());
        given(userRepository.save(any(User.class)))
                .willThrow(new DataIntegrityViolationException("uq_users_email"));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        verify(authProviderRepository, org.mockito.Mockito.times(2))
                .findByProviderAndSocialId("KAKAO", "kakao_race");
    }

    @Test
    @DisplayName("탈퇴 후 유예기간 내 로그인하면 계정이 재활성화된다")
    void socialLogin_deletedUser_withinGracePeriod_reactivated() {
        User deletedUser = User.builder().email("deleted@email.com").nickname("탈퇴유저").build();
        deletedUser.softDelete(NOW);
        AuthProvider provider = AuthProvider.builder()
                .user(deletedUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "deleted@email.com", "탈퇴유저", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(provider));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        assertThat(deletedUser.isDeleted()).isFalse();
    }

    @Test
    @DisplayName("재활성화 시 프로필 닉네임이 없으면 기존 닉네임을 유지한다")
    void socialLogin_deletedUser_nullNickname_keepsExistingNickname() {
        User deletedUser = User.builder().email("deleted@email.com").nickname("탈퇴유저").build();
        deletedUser.softDelete(NOW);
        AuthProvider provider = AuthProvider.builder()
                .user(deletedUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", null, null, false));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(provider));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        authService.socialLogin("KAKAO", "auth_code");

        assertThat(deletedUser.isDeleted()).isFalse();
        assertThat(deletedUser.getNickname()).isEqualTo("탈퇴유저");
        assertThat(deletedUser.getEmail()).isEqualTo("deleted@email.com");
    }

    @Test
    @DisplayName("탈퇴 후 유예기간이 지나면 기존 데이터를 하드 삭제하고 새 계정으로 가입한다")
    void socialLogin_deletedUser_afterGracePeriod_purgesAndCreatesNewAccount() {
        User deletedUser = User.builder().email("deleted@email.com").nickname("탈퇴유저").build();
        // Clock을 주입받게 되면서 리플렉션으로 deletedAt을 조작할 필요가 없어졌다.
        deletedUser.softDelete(NOW.minusDays(31));

        AuthProvider provider = AuthProvider.builder()
                .user(deletedUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "deleted@email.com", "탈퇴유저", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(provider));

        User newUser = User.builder().email("deleted@email.com").nickname("탈퇴유저").build();
        given(userRepository.save(any(User.class))).willReturn(newUser);
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willReturn(AuthProvider.builder().user(newUser).provider("KAKAO").socialId("kakao_123").build());

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        TokenResponse result = authService.socialLogin("KAKAO", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        verify(userHardDeleteService).purge(deletedUser.getId());
        verify(userRepository).save(any(User.class));
        verify(authProviderRepository).save(any(AuthProvider.class));
    }

    @Test
    @DisplayName("소셜 프로필 조회는 DB 트랜잭션을 열기 전에 끝난다")
    void socialLogin_fetchesProfileBeforeOpeningTransaction() {
        // 프로필 조회가 실패하면 DB 접근이 전혀 없어야 한다 (= 트랜잭션 안에서 외부 호출을 하지 않는다)
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("bad_code"))
                .willThrow(new BusinessException(ErrorCode.OAUTH_INVALID_CODE));

        assertThatThrownBy(() -> authService.socialLogin("KAKAO", "bad_code"))
                .isInstanceOf(BusinessException.class);

        verify(authProviderRepository, never()).findByProviderAndSocialId(anyString(), anyString());
        verify(refreshTokenStore, never()).save(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("유효한 Refresh Token으로 토큰을 재발급한다")
    void refresh_validToken() {
        Claims claims = Jwts.claims().subject("1").build();
        given(jwtTokenProvider.parseRefreshToken("old_refresh")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(1L);
        given(jwtTokenProvider.createAccessToken(1L)).willReturn("new_access");
        given(jwtTokenProvider.createRefreshToken(1L)).willReturn("new_refresh");
        given(refreshTokenStore.rotate(1L, "old_refresh", "new_refresh", jwtProperties.refreshTokenExpiry()))
                .willReturn(Optional.of("new_refresh"));

        TokenResponse result = authService.refresh("old_refresh");

        assertThat(result.accessToken()).isEqualTo("new_access");
        assertThat(result.refreshToken()).isEqualTo("new_refresh");
    }

    @Test
    @DisplayName("유예 창 안의 직전 토큰이면 저장소가 돌려준 현재 토큰을 그대로 내려보낸다")
    void refresh_previousTokenWithinGrace_returnsStoredToken() {
        // 탭 두 개가 부팅하며 각자 refresh를 부르는 흔한 상황. 탭1이 먼저 회전하면 탭2의
        // 쿠키는 직전 토큰이 되는데, 저장소가 유예 창 안이라 판단해 현재 유효한 토큰을
        // 돌려준다. 여기서 방금 만든 new_refresh를 고집하면 탭2는 저장소에 없는 토큰을
        // 들고 다니다 다음 회전에서 탈취로 판정되어 양쪽 세션이 함께 끊긴다.
        Claims claims = Jwts.claims().subject("1").build();
        given(jwtTokenProvider.parseRefreshToken("previous_refresh")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(1L);
        given(jwtTokenProvider.createAccessToken(1L)).willReturn("new_access");
        given(jwtTokenProvider.createRefreshToken(1L)).willReturn("new_refresh");
        given(refreshTokenStore.rotate(1L, "previous_refresh", "new_refresh",
                jwtProperties.refreshTokenExpiry()))
                .willReturn(Optional.of("current_refresh"));

        TokenResponse result = authService.refresh("previous_refresh");

        assertThat(result.accessToken()).isEqualTo("new_access");
        assertThat(result.refreshToken()).isEqualTo("current_refresh");
    }

    @Test
    @DisplayName("저장된 것과 다른 Refresh Token이면 CAS 회전이 실패하고 예외가 발생한다")
    void refresh_tokenMismatch() {
        Claims claims = Jwts.claims().subject("1").build();
        given(jwtTokenProvider.parseRefreshToken("stolen_refresh")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(1L);
        given(jwtTokenProvider.createAccessToken(1L)).willReturn("new_access");
        given(jwtTokenProvider.createRefreshToken(1L)).willReturn("new_refresh");
        // 스크립트가 불일치를 감지하고 키를 삭제한 뒤 빈 값을 돌려준다
        given(refreshTokenStore.rotate(eq(1L), eq("stolen_refresh"), anyString(), anyLong()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("stolen_refresh"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("Access Token으로 refresh를 시도하면 저장된 Refresh Token을 건드리지 않고 거부한다")
    void refresh_withAccessToken_rejectedWithoutTouchingStore() {
        given(jwtTokenProvider.parseRefreshToken("access_token")).willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("access_token"))
                .isInstanceOf(BusinessException.class);
        verify(refreshTokenStore, never()).rotate(any(), anyString(), anyString(), anyLong());
        verify(refreshTokenStore, never()).delete(any());
    }

    @Test
    @DisplayName("Redis 장애는 503(REDIS_UNAVAILABLE)으로 전파된다")
    void refresh_redisUnavailable_propagatesAs503() {
        Claims claims = Jwts.claims().subject("1").build();
        given(jwtTokenProvider.parseRefreshToken("old_refresh")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(1L);
        given(jwtTokenProvider.createAccessToken(1L)).willReturn("new_access");
        given(jwtTokenProvider.createRefreshToken(1L)).willReturn("new_refresh");
        given(refreshTokenStore.rotate(any(), anyString(), anyString(), anyLong()))
                .willThrow(new BusinessException(ErrorCode.REDIS_UNAVAILABLE));

        assertThatThrownBy(() -> authService.refresh("old_refresh"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.REDIS_UNAVAILABLE);
    }

    @Test
    @DisplayName("로그아웃 시 Redis에서 Refresh Token을 삭제한다")
    void logout() {
        authService.logout(1L);

        verify(refreshTokenStore).delete(1L);
    }

    @Test
    @DisplayName("회원 탈퇴 시 soft delete 후 Redis Refresh Token을 삭제한다")
    void withdraw() {
        User user = User.builder().email("withdraw@email.com").nickname("탈퇴예정").build();
        given(userRepository.findById(1L)).willReturn(Optional.of(user));

        authService.withdraw(1L);

        assertThat(user.isDeleted()).isTrue();
        verify(refreshTokenStore).delete(1L);
    }
}
