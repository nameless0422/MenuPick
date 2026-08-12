package com.nameless0422.MenuPick.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthRequest {

    /**
     * 비밀번호 길이 범위.
     *
     * <p>상한은 순수한 입력 검증이다. 해싱은 Argon2id로 하는데, BCrypt와 달리 입력을 잘라내지
     * 않으므로 "이 길이를 넘으면 서로 다른 비밀번호가 같은 해시가 된다"는 제약이 없다.
     * Argon2의 비용은 메모리·반복 횟수로 정해지고 입력 길이와 무관해서 긴 입력이 부하가 되지도 않는다.
     * 그래서 알고리즘이 아니라 상식적인 상한으로 128자를 둔다.
     */
    public static final int PASSWORD_MAX_LENGTH = 128;
    public static final int PASSWORD_MIN_LENGTH = 8;

    public record OAuthLoginRequest(
            @NotBlank(message = "인가 코드는 필수입니다.")
            String code
    ) {}

    public record SignupRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 255)
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH,
                    message = "비밀번호는 " + PASSWORD_MIN_LENGTH + "자 이상이어야 합니다.")
            String password,

            @NotBlank(message = "닉네임은 필수입니다.")
            @Size(max = 50)
            String nickname
    ) {}

    public record LoginRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Size(max = 255)
            String email,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(max = PASSWORD_MAX_LENGTH)
            String password
    ) {}

    /** 메일 링크로 전달된 일회용 토큰만 담는 요청(이메일 인증). */
    public record TokenRequest(
            @NotBlank(message = "토큰은 필수입니다.")
            String token
    ) {}

    /** 인증 메일 재발송·비밀번호 재설정 요청처럼 주소만 받는 경우. */
    public record EmailRequest(
            @NotBlank(message = "이메일은 필수입니다.")
            @Email(message = "이메일 형식이 올바르지 않습니다.")
            @Size(max = 255)
            String email
    ) {}

    public record PasswordResetRequest(
            @NotBlank(message = "토큰은 필수입니다.")
            String token,

            @NotBlank(message = "비밀번호는 필수입니다.")
            @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH,
                    message = "비밀번호는 " + PASSWORD_MIN_LENGTH + "자 이상이어야 합니다.")
            String newPassword
    ) {}

    public record PasswordChangeRequest(
            @NotBlank(message = "현재 비밀번호는 필수입니다.")
            @Size(max = PASSWORD_MAX_LENGTH)
            String currentPassword,

            @NotBlank(message = "새 비밀번호는 필수입니다.")
            @Size(min = PASSWORD_MIN_LENGTH, max = PASSWORD_MAX_LENGTH,
                    message = "비밀번호는 " + PASSWORD_MIN_LENGTH + "자 이상이어야 합니다.")
            String newPassword
    ) {}
}
