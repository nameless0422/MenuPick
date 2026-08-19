package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 발급 지점의 마지막 관문 검증.
 *
 * <p>탈퇴 여부를 호출자마다 보게 하면 한 곳만 빠져도 탈퇴 계정에 정상 세션이 붙는다.
 * 실제로 비밀번호 재설정은 토큰 TTL이 30분이라 "요청 후 탈퇴 → 링크 클릭"으로 그 구멍에
 * 들어갈 수 있다. 그래서 발급 경로가 모이는 여기서 막는지를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class TokenIssuerTest {

    @Mock private UserRepository userRepository;
    @Mock private JwtTokenProvider jwtTokenProvider;
    @Mock private RefreshTokenStore refreshTokenStore;

    private final JwtProperties jwtProperties = new JwtProperties(
            "testSecretKeyMustBeAtLeast256BitsLongForHS256Algorithm!!",
            1800000L, 1209600000L
    );

    private TokenIssuer tokenIssuer;

    @BeforeEach
    void setUp() {
        tokenIssuer = new TokenIssuer(
                userRepository, jwtTokenProvider, refreshTokenStore, jwtProperties);
    }

    private User user(long id, LocalDateTime deletedAt) {
        User user = User.builder().email("user@example.com").emailVerified(true).nickname("테스터").build();
        ReflectionTestUtils.setField(user, "id", id);
        if (deletedAt != null) {
            user.softDelete(deletedAt);
        }
        return user;
    }

    @Test
    @DisplayName("살아 있는 계정에는 토큰을 발급하고 Refresh를 저장소에 기록한다")
    void activeUser_issuesAndStores() {
        given(userRepository.findById(7L)).willReturn(Optional.of(user(7L, null)));
        given(jwtTokenProvider.createAccessToken(7L)).willReturn("access");
        given(jwtTokenProvider.createRefreshToken(7L)).willReturn("refresh");

        TokenResponse result = tokenIssuer.issue(7L);

        assertThat(result.accessToken()).isEqualTo("access");
        assertThat(result.refreshToken()).isEqualTo("refresh");
        verify(refreshTokenStore).save(7L, "refresh", jwtProperties.refreshTokenExpiry());
    }

    @Test
    @DisplayName("탈퇴 상태 계정에는 발급하지 않는다 — deletedAt이 남은 채 활성 세션이 생기면 30일 뒤 데이터가 지워진다")
    void withdrawnUser_rejected() {
        // 유예기간 안이어도 거부한다. 복구는 로그인·메일 인증처럼 복귀 의사가 분명한
        // 경로가 자기 트랜잭션 안에서 판단해야 하고, 발급기가 대신 되살리면 그 판단이 갈라진다.
        given(userRepository.findById(7L))
                .willReturn(Optional.of(user(7L, LocalDateTime.now().minusDays(1))));

        assertThatThrownBy(() -> tokenIssuer.issue(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(jwtTokenProvider, never()).createRefreshToken(anyLong());
        verify(refreshTokenStore, never()).save(any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("이미 하드 삭제된 계정 ID로는 발급하지 않는다")
    void purgedUser_rejected() {
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> tokenIssuer.issue(7L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        verify(refreshTokenStore, never()).save(any(), anyString(), anyLong());
    }
}
