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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.test.util.ReflectionTestUtils;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @Mock private AuthMailer authMailer;
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
                jwtTokenProvider, refreshTokenStore, authMailer, jwtProperties,
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
    @DisplayName("연동된 적 없는 소셜 계정으로 로그인하면 새 유저를 만들지 않고 거절한다")
    void socialLogin_notLinked_rejected() {
        // 카카오는 비즈앱 전환 없이는 이메일을 주지 않는다. 예전처럼 여기서 계정을 만들면
        // users.email이 NULL인 채로 남아 비밀번호 재설정도 안내 메일도 불가능한 고아 계정이 된다.
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", null, "테스트", false));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.socialLogin("KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ACCOUNT_NOT_LINKED);

        verify(userRepository, never()).save(any(User.class));
        verify(authProviderRepository, never()).save(any(AuthProvider.class));
        verify(refreshTokenStore, never()).save(any(), anyString(), anyLong());
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
    @DisplayName("검증된 이메일이면 같은 이메일의 기존 유저에 연동을 붙여 로그인시킨다")
    void socialLogin_sameEmail_linksProviderToExistingUser() {
        // 구글은 소유가 검증된 주소를 주므로 이 자동 연동 경로가 계속 살아 있어야 한다.
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
    @DisplayName("미검증 이메일은 같은 이메일 유저가 있어도 조회조차 하지 않고 거절한다")
    void socialLogin_unverifiedEmail_neverLinksToExistingUser() {
        // 미검증 이메일을 신뢰하면 공격자가 피해자 주소를 자기 소셜 계정에 넣는 것만으로
        // 피해자 계정에 올라탈 수 있다.
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("attacker_kakao", "victim@email.com", "공격자", false));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "attacker_kakao"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> authService.socialLogin("KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ACCOUNT_NOT_LINKED);

        // 피해자 계정으로의 통합 조회가 아예 일어나지 않아야 한다
        verify(userRepository, never()).findByEmail(any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("동시 연동으로 UNIQUE 충돌이 나면 새 트랜잭션에서 재시도해 로그인을 완료한다")
    void socialLogin_concurrentLinkConflict_retriesInNewTransaction() {
        User winner = User.builder().email("race@email.com").nickname("먼저연동").build();
        AuthProvider winnerProvider = AuthProvider.builder()
                .user(winner).provider("GOOGLE").socialId("google_race").build();

        given(kakaoProvider.getProviderName()).willReturn("GOOGLE");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("google_race", "race@email.com", "먼저연동", true));
        // 1회차: 아직 안 보임 → 연동 행 생성 중 UNIQUE 충돌. 2회차: 상대 트랜잭션이 커밋한 행이 보인다.
        given(authProviderRepository.findByProviderAndSocialId("GOOGLE", "google_race"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(winnerProvider));
        given(userRepository.findByEmail("race@email.com")).willReturn(Optional.of(winner));
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willThrow(new DataIntegrityViolationException("uq_auth_provider_social"));

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        TokenResponse result = authService.socialLogin("GOOGLE", "auth_code");

        assertThat(result.accessToken()).isEqualTo("access_token");
        verify(authProviderRepository, org.mockito.Mockito.times(2))
                .findByProviderAndSocialId("GOOGLE", "google_race");
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
    @DisplayName("재활성화 중 제공자가 준 새 닉네임이 이미 쓰이고 있으면 번호를 붙인다")
    void socialLogin_deletedUser_changedNicknameTaken_getsSuffix() {
        // 닉네임은 제공자가 정해 사용자가 고칠 수 없다. 흔한 이름이라는 이유로 거절하면
        // 그 사람은 복귀 자체를 못 한다.
        User deletedUser = User.builder().email("deleted@email.com").nickname("옛이름").build();
        deletedUser.softDelete(NOW);
        AuthProvider provider = AuthProvider.builder()
                .user(deletedUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "deleted@email.com", "홍길동", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(provider));
        given(userRepository.existsByNickname("홍길동")).willReturn(true);
        given(userRepository.existsByNickname("홍길동2")).willReturn(false);

        given(jwtTokenProvider.createAccessToken(any())).willReturn("access_token");
        given(jwtTokenProvider.createRefreshToken(any())).willReturn("refresh_token");

        authService.socialLogin("KAKAO", "auth_code");

        assertThat(deletedUser.getNickname()).isEqualTo("홍길동2");
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
    @DisplayName("탈퇴 유예기간이 지났으면 옛 데이터를 하드 삭제하고, 새 계정을 만드는 대신 거절한다")
    void socialLogin_deletedUser_afterGracePeriod_purgesAndRejects() {
        User deletedUser = User.builder().email("deleted@email.com").nickname("탈퇴유저").build();
        // id를 채워 두는 이유: 정리 대상은 "유예기간이 지난 유저의 id"로 트랜잭션 밖에 전달된다.
        // 빌더만 쓰면 id가 null이라 그 표시가 "정리할 것 없음"과 구분되지 않는다 —
        // 실제로 조회되는 유저는 언제나 저장된 행이라 id가 있다.
        ReflectionTestUtils.setField(deletedUser, "id", 9L);
        deletedUser.softDelete(NOW.minusDays(31));

        AuthProvider provider = AuthProvider.builder()
                .user(deletedUser).provider("KAKAO").socialId("kakao_123").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_123", "deleted@email.com", "탈퇴유저", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_123"))
                .willReturn(Optional.of(provider));

        assertThatThrownBy(() -> authService.socialLogin("KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ACCOUNT_NOT_LINKED);

        // 정리는 트랜잭션을 닫은 뒤에 일어나야 롤백에 휩쓸리지 않는다
        verify(userHardDeleteService).purge(deletedUser.getId());
        verify(userRepository, never()).save(any(User.class));
        verify(refreshTokenStore, never()).save(any(), anyString(), anyLong());
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

    // ---- 소셜 계정 연동 ----

    @Test
    @DisplayName("연동 - 로그인한 계정에 소셜 계정을 붙이고 연동 목록을 돌려준다")
    void link_success() {
        User me = userWithId(1L, "나");
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_new", null, "카카오닉", false));
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_new"))
                .willReturn(Optional.empty());
        given(authProviderRepository.findAllByUserId(1L))
                .willReturn(List.of(localProvider(me)));
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willAnswer(inv -> inv.getArgument(0));

        AuthService.LinkResult result = authService.linkSocialAccount(1L, "kakao", "auth_code");

        // LOCAL은 연동 목록이 아니다 — 자체 자격증명이라 hasPassword가 따로 알려준다
        assertThat(result.linkedProviders()).containsExactly("KAKAO");
        // 로그인 수단이 늘었으니 기존 세션은 끊긴다 — Refresh Token을 새로 저장하면 그 계정의
        // 다른 세션이 전부 밀려난다(RefreshTokenStore.save가 회전 유예 값까지 버린다).
        // 당사자에게는 그 새 토큰이 그대로 나가야 한다. 안 그러면 설정 화면에서 버튼 한 번
        // 누른 사용자가 스스로를 로그아웃시킨 셈이 된다.
        verify(refreshTokenStore).save(eq(1L), any(), anyLong());
        assertThat(result.tokens()).isNotNull();

        ArgumentCaptor<AuthProvider> captor = ArgumentCaptor.forClass(AuthProvider.class);
        verify(authProviderRepository).save(captor.capture());
        assertThat(captor.getValue().getUser()).isSameAs(me);
        assertThat(captor.getValue().getSocialId()).isEqualTo("kakao_new");
        // 경로에 소문자로 들어와도 저장은 제공자가 선언한 이름으로 한다
        assertThat(captor.getValue().getProvider()).isEqualTo("KAKAO");
    }

    @Test
    @DisplayName("연동 - 다른 사용자에게 이미 연동된 소셜 계정이면 거절한다")
    void link_socialAccountOwnedByAnotherUser_rejected() {
        // 허용하면 그 소셜 계정의 주인이 다음 로그인부터 공격자 계정으로 들어오게 된다.
        User me = userWithId(1L, "나");
        User other = userWithId(2L, "남");
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_victim", null, "피해자", false));
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_victim"))
                .willReturn(Optional.of(AuthProvider.builder()
                        .user(other).provider("KAKAO").socialId("kakao_victim").build()));

        assertThatThrownBy(() -> authService.linkSocialAccount(1L, "KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ACCOUNT_TAKEN);

        verify(authProviderRepository, never()).save(any(AuthProvider.class));
    }

    @Test
    @DisplayName("연동 - 이미 내 계정에 붙어 있는 소셜 계정이면 거절한다")
    void link_alreadyLinkedToSelf_rejected() {
        User me = userWithId(1L, "나");
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_mine", null, "나", false));
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_mine"))
                .willReturn(Optional.of(AuthProvider.builder()
                        .user(me).provider("KAKAO").socialId("kakao_mine").build()));

        assertThatThrownBy(() -> authService.linkSocialAccount(1L, "KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ALREADY_LINKED);

        verify(authProviderRepository, never()).save(any(AuthProvider.class));
    }

    @Test
    @DisplayName("연동 - 같은 제공자를 다른 소셜 계정으로 또 붙이려 하면 거절한다")
    void link_sameProviderTwice_rejected() {
        // (user_id, provider) UNIQUE가 DB에 없어서, 통과시키면 KAKAO 행이 둘 생기고
        // 그때부터 findByUserIdAndProvider가 결과 둘을 만나 조회 자체가 터진다.
        User me = userWithId(1L, "나");
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_second", null, "나", false));
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_second"))
                .willReturn(Optional.empty());
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(
                AuthProvider.builder().user(me).provider("KAKAO").socialId("kakao_first").build()));

        assertThatThrownBy(() -> authService.linkSocialAccount(1L, "KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ALREADY_LINKED);

        verify(authProviderRepository, never()).save(any(AuthProvider.class));
    }

    @Test
    @DisplayName("연동 - 동시 요청으로 UNIQUE 충돌이 나면 새 트랜잭션에서 다시 보고 거절한다")
    void link_concurrentConflict_retriesAndRejects() {
        // 제약 위반이 난 트랜잭션은 rollback-only라 같은 트랜잭션에서 재조회할 수 없다.
        // 새 트랜잭션에서 다시 보면 상대가 커밋한 행이 보여 정확한 사유로 끝난다.
        User me = userWithId(1L, "나");
        User other = userWithId(2L, "남");
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code"))
                .willReturn(new OAuthUserProfile("kakao_race", null, "나", false));
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_race"))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(AuthProvider.builder()
                        .user(other).provider("KAKAO").socialId("kakao_race").build()));
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(localProvider(me)));
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willThrow(new DataIntegrityViolationException("uq_auth_provider_social"));

        assertThatThrownBy(() -> authService.linkSocialAccount(1L, "KAKAO", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_ACCOUNT_TAKEN);

        verify(authProviderRepository, org.mockito.Mockito.times(2))
                .findByProviderAndSocialId("KAKAO", "kakao_race");
    }

    @Test
    @DisplayName("연동 - 지원하지 않는 제공자면 인가 코드를 교환하기도 전에 400")
    void link_unknownProvider_rejectedBeforeTokenExchange() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");

        assertThatThrownBy(() -> authService.linkSocialAccount(1L, "NAVER", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verify(kakaoProvider, never()).getUserProfile(anyString());
        verifyNoInteractions(authProviderRepository);
    }

    @Test
    @DisplayName("해제 - 비밀번호가 남아 있으면 연동을 끊는다")
    void unlink_success() {
        User me = userWithId(1L, "나");
        AuthProvider local = localProvider(me);
        AuthProvider kakao = AuthProvider.builder()
                .user(me).provider("KAKAO").socialId("kakao_mine").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(local, kakao));

        AuthService.LinkResult result = authService.unlinkSocialAccount(1L, "kakao");

        assertThat(result.linkedProviders()).isEmpty();
        verify(authProviderRepository).delete(kakao);
        // 끊어낸 수단으로 만들어진 세션이 남아 있으면 "이 로그인 방법을 없앴다"는 행동이
        // 아무것도 끊지 못한 것이 된다. Refresh Token을 새로 저장해 나머지를 밀어낸다.
        verify(refreshTokenStore).save(eq(1L), any(), anyLong());
    }

    @Test
    @DisplayName("해제 - 다른 소셜 연동이 남으면 비밀번호가 없어도 끊을 수 있다")
    void unlink_anotherSocialRemains_allowed() {
        User me = userWithId(1L, "나");
        AuthProvider kakao = AuthProvider.builder()
                .user(me).provider("KAKAO").socialId("kakao_mine").build();
        AuthProvider google = AuthProvider.builder()
                .user(me).provider("GOOGLE").socialId("google_mine").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(kakao, google));

        assertThat(authService.unlinkSocialAccount(1L, "KAKAO").linkedProviders())
                .containsExactly("GOOGLE");
        verify(authProviderRepository).delete(kakao);
    }

    @Test
    @DisplayName("해제 - 마지막 로그인 수단이면 거절한다")
    void unlink_lastLoginMethod_rejected() {
        // 통과시키면 그 계정에는 영원히 들어갈 수 없고, 탈퇴조차 못 해 데이터만 남는다.
        User me = userWithId(1L, "나");
        AuthProvider kakao = AuthProvider.builder()
                .user(me).provider("KAKAO").socialId("kakao_mine").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(kakao));

        assertThatThrownBy(() -> authService.unlinkSocialAccount(1L, "KAKAO"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LAST_LOGIN_METHOD);

        verify(authProviderRepository, never()).delete(any(AuthProvider.class));
    }

    @Test
    @DisplayName("해제 - 비밀번호 없는 LOCAL 행은 로그인 수단으로 세지 않는다")
    void unlink_localWithoutPassword_isNotALoginMethod() {
        User me = userWithId(1L, "나");
        AuthProvider passwordless = AuthProvider.builder()
                .user(me).provider(AuthProvider.LOCAL).socialId("me@email.com").build();
        AuthProvider kakao = AuthProvider.builder()
                .user(me).provider("KAKAO").socialId("kakao_mine").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(passwordless, kakao));

        assertThatThrownBy(() -> authService.unlinkSocialAccount(1L, "KAKAO"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.LAST_LOGIN_METHOD);

        verify(authProviderRepository, never()).delete(any(AuthProvider.class));
    }

    @Test
    @DisplayName("해제 - 연동한 적 없는 제공자면 404")
    void unlink_notLinked_rejected() {
        User me = userWithId(1L, "나");

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(localProvider(me)));

        assertThatThrownBy(() -> authService.unlinkSocialAccount(1L, "KAKAO"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.SOCIAL_LINK_NOT_FOUND);

        verify(authProviderRepository, never()).delete(any(AuthProvider.class));
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
        authService.logout(1L, null);

        verify(refreshTokenStore).delete(1L);
    }

    /**
     * 로그아웃이 가장 필요한 순간은 Access Token이 만료된 뒤다. principal이 없다고 아무것도
     * 하지 않으면 14일짜리 Refresh Token이 남은 채라, 공용 PC에서 다음 사용자가 /refresh
     * 한 번으로 남의 세션을 되살린다.
     */
    @Test
    @DisplayName("로그아웃 - 인증이 없어도 Refresh Token 쿠키의 주인 세션을 끊는다")
    void logout_withoutPrincipal_usesRefreshCookie() {
        Claims claims = mock(Claims.class);
        given(jwtTokenProvider.parseRefreshToken("살아있는-refresh")).willReturn(Optional.of(claims));
        given(jwtTokenProvider.getUserId(claims)).willReturn(7L);

        authService.logout(null, "살아있는-refresh");

        verify(refreshTokenStore).delete(7L);
    }

    @Test
    @DisplayName("로그아웃 - 주체를 찾을 수 없으면 아무것도 지우지 않는다")
    void logout_withoutAnySubject_isNoop() {
        authService.logout(null, "쓰레기-토큰");

        verify(refreshTokenStore, never()).delete(any());
    }

    /**
     * 명시적 연동(link)에는 있던 같은 제공자 중복 가드가 자동 병합 경로에는 없었다.
     * DB에 (user_id, provider) UNIQUE가 없어(V1 스키마) 행이 둘 생기면, 그때부터
     * findByUserIdAndProvider가 결과 둘을 만나 비밀번호 변경·연동 해제가 통째로 막힌다.
     */
    @Test
    @DisplayName("자동 병합 - 이미 같은 제공자가 연동돼 있으면 행을 하나 더 만들지 않는다")
    void autoMerge_sameProviderAlreadyLinked_rejected() {
        User owner = userWithId(5L, "주인");
        AuthProvider existingKakao = AuthProvider.builder()
                .user(owner).provider("KAKAO").socialId("kakao_old").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code")).willReturn(
                new OAuthUserProfile("kakao_new", "owner@example.com", "주인", true));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_new"))
                .willReturn(Optional.empty());
        given(userRepository.findByEmail("owner@example.com")).willReturn(Optional.of(owner));
        given(authProviderRepository.findAllByUserId(5L)).willReturn(List.of(existingKakao));

        assertThatThrownBy(() -> authService.socialLogin("kakao", "auth_code"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.SOCIAL_ALREADY_LINKED);

        verify(authProviderRepository, never()).save(any(AuthProvider.class));
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

    @Test
    @DisplayName("authorizeUrl - 제공자가 만든 URL을 그대로 돌려준다")
    void authorizeUrl_delegatesToProvider() {
        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.buildAuthorizeUrl("abcdef0123456789"))
                .willReturn("https://kauth.kakao.com/oauth/authorize?client_id=x");

        assertThat(authService.authorizeUrl("KAKAO", "abcdef0123456789"))
                .isEqualTo("https://kauth.kakao.com/oauth/authorize?client_id=x");
    }

    /**
     * state는 브라우저가 준 값을 URL에 그대로 싣는 자리다. 형식을 막지 않으면 개행이나
     * 따옴표가 섞여 들어가 인가 URL이 의도치 않게 잘리거나 늘어난다.
     */
    @ParameterizedTest
    @DisplayName("authorizeUrl - state 형식이 어긋나면 제공자를 부르지 않고 400")
    @ValueSource(strings = {"short", "has space", "semi;colon", "amp&ersand", "hash#mark"})
    void authorizeUrl_rejectsMalformedState(String state) {
        assertThatThrownBy(() -> authService.authorizeUrl("KAKAO", state))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verifyNoInteractions(kakaoProvider);
    }

    /**
     * 개행이 섞인 state를 그대로 URL에 실으면 인가 요청이 그 지점에서 잘린다.
     * 이스케이프 표기 대신 문자 코드로 만들어, 소스에서 눈에 띄지 않는 제어문자를 확실히 넣는다.
     */
    @Test
    @DisplayName("authorizeUrl - state에 제어문자가 섞이면 400")
    void authorizeUrl_rejectsControlCharacterInState() {
        String state = "abcdef0123456789" + (char) 10 + "abcdef0123456789";

        assertThatThrownBy(() -> authService.authorizeUrl("KAKAO", state))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.INVALID_INPUT);

        verifyNoInteractions(kakaoProvider);
    }

    /** 연동·해제는 소유자를 id로 비교하므로(남의 계정인지 판정) 저장된 것처럼 id를 채워 둔다. */
    private static User userWithId(Long id, String nickname) {
        User user = User.builder().nickname(nickname).build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /** 비밀번호가 있는 자체 계정 행 — "해제해도 들어올 문이 남는가" 판정의 기준이 된다. */
    private static AuthProvider localProvider(User user) {
        return AuthProvider.builder()
                .user(user)
                .provider(AuthProvider.LOCAL)
                .socialId("me@email.com")
                .passwordHash("{argon2}hash")
                .build();
    }

    // --- 로그인 수단 변경 통지 (#87) ---

    /**
     * 세션을 끊는 것만으로는 Access Token을 탈취한 쪽이 자기 소셜 계정을 붙여 둔 경우를
     * 막지 못한다 — 그쪽은 자기 소셜로 다시 들어온다. 주인이 사실을 알아야 연동을 해제하고
     * 비밀번호를 바꿀 수 있으므로, 이 통지가 이 변경에 대한 실질적인 방어선이다.
     */
    @Test
    @DisplayName("연동하면 계정 주인에게 알린다")
    void link_notifiesOwner() {
        User me = userWithId(1L, "나");
        me.verifyEmail("me@example.com");

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code")).willReturn(
                new OAuthUserProfile("kakao_new", null, "카카오닉", false));
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_new"))
                .willReturn(Optional.empty());
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(localProvider(me)));
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willAnswer(inv -> inv.getArgument(0));

        authService.linkSocialAccount(1L, "kakao", "auth_code");

        verify(authMailer).sendLoginMethodChanged("me@example.com", "카카오", true);
    }

    @Test
    @DisplayName("해제해도 계정 주인에게 알린다")
    void unlink_notifiesOwner() {
        User me = userWithId(1L, "나");
        me.verifyEmail("me@example.com");
        AuthProvider local = localProvider(me);
        AuthProvider kakao = AuthProvider.builder()
                .user(me).provider("KAKAO").socialId("kakao_mine").build();

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(local, kakao));

        authService.unlinkSocialAccount(1L, "kakao");

        verify(authMailer).sendLoginMethodChanged("me@example.com", "카카오", false);
    }

    /**
     * #71 이전에 소셜로 만들어진 계정은 users.email이 비어 있을 수 있다. 보낼 곳이 없다는
     * 사실이 연동 자체를 막을 이유는 아니다 — 통지는 건너뛰고 변경은 그대로 진행한다.
     */
    @Test
    @DisplayName("주소가 없는 계정이면 통지를 건너뛰되 변경은 그대로 진행한다")
    void link_withoutEmail_skipsNotification() {
        User me = userWithId(1L, "나");

        given(kakaoProvider.getProviderName()).willReturn("KAKAO");
        given(kakaoProvider.getUserProfile("auth_code")).willReturn(
                new OAuthUserProfile("kakao_new", null, "카카오닉", false));
        given(userRepository.findById(1L)).willReturn(Optional.of(me));
        given(authProviderRepository.findByProviderAndSocialId("KAKAO", "kakao_new"))
                .willReturn(Optional.empty());
        given(authProviderRepository.findAllByUserId(1L)).willReturn(List.of(localProvider(me)));
        given(authProviderRepository.save(any(AuthProvider.class)))
                .willAnswer(inv -> inv.getArgument(0));

        AuthService.LinkResult result = authService.linkSocialAccount(1L, "kakao", "auth_code");

        assertThat(result.linkedProviders()).containsExactly("KAKAO");
        verifyNoInteractions(authMailer);
    }

}
