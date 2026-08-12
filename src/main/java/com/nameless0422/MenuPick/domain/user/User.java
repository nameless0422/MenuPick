package com.nameless0422.MenuPick.domain.user;

import com.nameless0422.MenuPick.common.config.TimeConfig;
import com.nameless0422.MenuPick.common.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseTimeEntity {

    /** 탈퇴 후 이 기간 내 재로그인하면 계정이 복구되고, 지나면 하드 삭제 대상이 된다. */
    public static final int WITHDRAW_GRACE_PERIOD_DAYS = 30;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 255)
    private String email;

    /**
     * {@link #email}의 소유가 검증됐는지.
     *
     * <p>AuthService가 이메일을 기준으로 소셜 연동을 기존 계정에 자동 병합하므로,
     * 미검증 주소가 users.email에 들어가면 계정 탈취 경로가 열린다. 자체 계정은
     * 메일 인증을 마친 뒤에야 email이 채워지고 이 값이 true가 된다.
     */
    @Column(nullable = false)
    private boolean emailVerified;

    @Column(nullable = false, length = 50)
    private String nickname;

    private LocalDateTime deletedAt;

    @Builder
    public User(String email, String nickname, boolean emailVerified) {
        this.email = email;
        this.nickname = nickname;
        this.emailVerified = emailVerified;
    }

    /** 메일 인증 완료 — 이 시점에야 주소를 users.email에 확정한다. */
    public void verifyEmail(String email) {
        this.email = email;
        this.emailVerified = true;
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    /**
     * 탈퇴 시각을 기록한다.
     *
     * <p>다른 엔티티(Menu/Restaurant/History)는 시각을 서비스에서 주입받도록 바꿨지만,
     * 이 메서드의 호출부(AuthService)는 이번 작업 범위 밖이라 시그니처를 유지한다.
     * 대신 JVM 기본 시간대에 좌우되지 않도록 서비스 기준 시간대(KST)를 명시한다.
     * TODO: AuthService에 Clock을 주입할 수 있게 되면 {@code softDelete(LocalDateTime)}로 전환.
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now(TimeConfig.SERVICE_ZONE);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /**
     * 유예기간 내 재로그인으로 계정을 되살린다.
     *
     * @param verifiedEmail 소유가 검증된 주소. null이면 기존 주소와 검증 상태를 그대로 둔다
     *                      — 검증되지 않은 값으로 덮어쓰면 {@link #emailVerified} 불변식이 깨진다.
     */
    public void reactivate(String verifiedEmail, String nickname) {
        this.deletedAt = null;
        if (verifiedEmail != null) {
            this.email = verifiedEmail;
            this.emailVerified = true;
        }
        this.nickname = nickname;
    }

    /** {@link #softDelete()}와 같은 이유로 시각을 직접 얻되 기준 시간대를 KST로 고정한다. */
    public boolean isWithinGracePeriod(int graceDays) {
        return deletedAt != null
                && deletedAt.plusDays(graceDays).isAfter(LocalDateTime.now(TimeConfig.SERVICE_ZONE));
    }
}
