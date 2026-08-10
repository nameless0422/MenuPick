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

    @Column(nullable = false, length = 50)
    private String nickname;

    private LocalDateTime deletedAt;

    @Builder
    public User(String email, String nickname) {
        this.email = email;
        this.nickname = nickname;
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

    public void reactivate(String email, String nickname) {
        this.deletedAt = null;
        this.email = email;
        this.nickname = nickname;
    }

    /** {@link #softDelete()}와 같은 이유로 시각을 직접 얻되 기준 시간대를 KST로 고정한다. */
    public boolean isWithinGracePeriod(int graceDays) {
        return deletedAt != null
                && deletedAt.plusDays(graceDays).isAfter(LocalDateTime.now(TimeConfig.SERVICE_ZONE));
    }
}
