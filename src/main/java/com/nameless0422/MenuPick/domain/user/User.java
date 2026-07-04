package com.nameless0422.MenuPick.domain.user;

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

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void reactivate(String email, String nickname) {
        this.deletedAt = null;
        this.email = email;
        this.nickname = nickname;
    }

    public boolean isWithinGracePeriod(int graceDays) {
        return deletedAt != null && deletedAt.plusDays(graceDays).isAfter(LocalDateTime.now());
    }
}
