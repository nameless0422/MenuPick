package com.nameless0422.MenuPick.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 탈퇴 유예기간 경계.
 *
 * <p>이 경계 하나에 두 가지가 걸려 있다. 안쪽이면 재로그인으로 계정이 되살아나고, 바깥이면
 * 로그인 시도가 곧 <b>기존 데이터 하드 삭제</b>로 이어진다(`AuthService`·`LocalAuthService`).
 * 한 칸 어긋나면 되살아나야 할 계정이 지워진다.
 *
 * <p>기준 시각을 밖에서 받도록 바꾸기 전에는 이 검증을 실제 시계에 기대야 해서, 경계를 딱
 * 짚어보는 대신 "31일 전"처럼 넉넉히 벌려 놓고 지나가는 수밖에 없었다.
 */
class UserTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 2, 15, 4, 0);

    private User withdrawnAt(LocalDateTime deletedAt) {
        User user = User.builder().email("a@b.com").nickname("탈퇴유저").build();
        user.softDelete(deletedAt);
        return user;
    }

    @Test
    @DisplayName("유예 마지막 날 안쪽이면 아직 되살릴 수 있다")
    void justInsideGraceIsRecoverable() {
        User user = withdrawnAt(NOW.minusDays(30).plusMinutes(1));

        assertThat(user.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS, NOW)).isTrue();
    }

    @Test
    @DisplayName("정확히 30일이 지난 순간부터는 유예가 끝난 것으로 본다")
    void exactlyGracePeriodIsExpired() {
        // 경계는 열려 있다(isAfter). 30일 0분 0초는 "안"이 아니라 "밖"이다 —
        // 이 한 칸이 재활성화와 하드 삭제를 가른다.
        User user = withdrawnAt(NOW.minusDays(30));

        assertThat(user.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS, NOW)).isFalse();
    }

    @Test
    @DisplayName("탈퇴하지 않은 계정은 유예 판정 대상이 아니다")
    void activeAccountIsNotInGracePeriod() {
        User user = User.builder().email("a@b.com").nickname("현역").build();

        // deletedAt이 null인데 true를 주면, 탈퇴한 적 없는 계정이 복구 경로를 타게 된다.
        assertThat(user.isWithinGracePeriod(User.WITHDRAW_GRACE_PERIOD_DAYS, NOW)).isFalse();
        assertThat(user.isDeleted()).isFalse();
    }
}
