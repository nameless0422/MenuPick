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

    /**
     * 자체(이메일+비밀번호) 계정을 나타내는 provider 값.
     *
     * <p>이 경우 {@link #socialId}는 소문자로 정규화한 이메일이고 {@link #passwordHash}가 채워진다.
     * UNIQUE (provider, social_id)가 그대로 "이메일 중복 가입 불가" 제약이 된다.
     */
    public static final String LOCAL = "LOCAL";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String provider;

    @Column(nullable = false, length = 255)
    private String socialId;

    /**
     * 소셜 계정은 비밀번호가 없으므로 null. 알고리즘 접두사를 포함한 인코딩 결과가 그대로 들어간다.
     * 길이 근거는 V4 마이그레이션 주석 참고 — Argon2id 결과가 104자라 bcrypt 기준으로 잡으면 잘린다.
     */
    @Column(length = 255)
    private String passwordHash;

    @Builder
    public AuthProvider(User user, String provider, String socialId, String passwordHash) {
        this.user = user;
        this.provider = provider;
        this.socialId = socialId;
        this.passwordHash = passwordHash;
    }

    public boolean isLocal() {
        return LOCAL.equals(provider);
    }

    public void changePassword(String encodedPassword) {
        this.passwordHash = encodedPassword;
    }

    /**
     * 이 인증 수단의 주인을 옮긴다.
     *
     * <p>메일 인증을 마친 자체 계정의 주소가 이미 소셜로 가입된 계정의 것이면, 별도 계정을
     * 남기지 않고 그쪽에 붙인다. 인증을 통과했다는 것이 곧 주소 소유의 증명이므로 안전하다.
     */
    public void moveTo(User newOwner) {
        this.user = newOwner;
    }
}
