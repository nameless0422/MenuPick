package com.nameless0422.MenuPick.domain.user;

import com.nameless0422.MenuPick.common.domain.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "auth_providers")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuthProvider extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 100)
    private String socialId;

    @Builder
    public AuthProvider(User user, String provider, String socialId) {
        this.user = user;
        this.provider = provider;
        this.socialId = socialId;
    }
}
