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
     * 이 인증 수단의 주인을 옮긴다. {@code passwordHash}도 함께 따라간다.
     *
     * <p><b>왜 비밀번호를 그대로 옮겨도 되는가.</b> 이 이동은 메일로 보낸 링크를 실제로 누른
     * 뒤에만 일어난다. 즉 비밀번호를 정한 사람이 그 주소의 메일함을 통제한다는 것이 증명된
     * 상태다. 증명 없이 옮겨간다면 남이 정한 비밀번호가 내 계정에 붙는 셈이 되므로 위험하지만,
     * 여기서는 그 전제가 성립한다.
     *
     * <p><b>지금 도달 가능한 경우는 하나뿐이다</b> — 탈퇴한 사용자가 같은 주소로 다시 가입해
     * 돌아오는 경로. 살아 있는 계정의 주소로는 {@code LocalAuthService}가 가입 시점에
     * pending 계정 자체를 만들지 않는다({@code existsByEmailAndDeletedAtIsNull}). 그 가드가
     * 사라지면 이 메서드의 안전성 근거도 함께 흔들리므로 둘을 같이 봐야 한다.
     */
    public void moveTo(User newOwner) {
        this.user = newOwner;
    }
}
