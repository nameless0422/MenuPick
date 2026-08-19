package com.nameless0422.MenuPick.domain.auth;

import com.nameless0422.MenuPick.common.exception.BusinessException;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import com.nameless0422.MenuPick.common.security.JwtProperties;
import com.nameless0422.MenuPick.common.security.JwtTokenProvider;
import com.nameless0422.MenuPick.domain.auth.dto.AuthResponse.TokenResponse;
import com.nameless0422.MenuPick.domain.user.User;
import com.nameless0422.MenuPick.domain.user.UserRepository;
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

    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final JwtProperties jwtProperties;

    /**
     * 발급 직전에 계정이 살아 있는지 다시 확인한다.
     *
     * <p>발급을 요청한 시점과 실제로 발급되는 시점 사이에 계정이 탈퇴할 수 있다. 특히
     * 비밀번호 재설정 토큰은 TTL이 30분이라, 메일을 받아 두고 탈퇴한 뒤 그 링크를 누르면
     * {@code requestPasswordReset}의 탈퇴 필터를 이미 지나온 상태로 여기까지 온다.
     * 그대로 발급하면 {@code deletedAt}이 남은 채 정상 세션이 생기고, 사용자는 복귀했다고
     * 믿지만 유예기간이 끝나는 순간 정리 배치가 메뉴·식당·히스토리를 전부 지운다.
     * 호출자마다 검사를 흩뿌리지 않고, 발급 경로가 모이는 이 한 곳에서 막는다.
     *
     * <p>여기서 되살리지 않고 <b>거부</b>하는 이유: 계정 복구는 "이 계정으로 다시 들어오겠다"는
     * 명시적 행위(로그인·메일 인증)에서만 일어나야 하고, 그 경로들은 각자 트랜잭션 안에서
     * 유예기간을 보고 reactivate/purge 중 무엇을 할지 이미 판단한다
     * ({@code LocalAuthService.authenticate}, {@code AuthService.resolveUser},
     * {@code LocalAuthService.completeVerification}). 비밀번호를 바꿨다는 사실은 복귀 의사가
     * 아니며, 트랜잭션 밖에서 도는 발급기가 계정 상태를 되돌리면 유예기간 판단이 두 군데로
     * 갈라져 어긋난다. 거부돼도 막다른 길은 아니다 — 사용자가 그냥 로그인하면 그 경로가
     * 유예기간 안에서 계정을 복구한다.
     *
     * <p>즉 이 검사에 걸리는 것은 "복구 판단을 거치지 않고 여기까지 온" 호출뿐이다.
     * 복구를 마친 호출자는 자기 트랜잭션을 커밋한 뒤 부르므로 여기서 살아 있는 것으로 보인다.
     */
    public TokenResponse issue(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));

        if (user.isDeleted()) {
            throw new BusinessException(
                    ErrorCode.UNAUTHORIZED, "탈퇴한 계정입니다. 다시 로그인해주세요.");
        }

        String accessToken = jwtTokenProvider.createAccessToken(userId);
        String refreshToken = jwtTokenProvider.createRefreshToken(userId);

        refreshTokenStore.save(userId, refreshToken, jwtProperties.refreshTokenExpiry());

        return new TokenResponse(accessToken, refreshToken);
    }
}
