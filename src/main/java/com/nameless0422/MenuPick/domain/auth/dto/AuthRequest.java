package com.nameless0422.MenuPick.domain.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthRequest {

    /**
     * 비밀번호 길이 상한.
     *
     * <p>BCrypt는 72바이트를 넘는 입력을 잘라내므로, 이 값을 넘겨두면 서로 다른 긴 비밀번호가
     * 같은 해시를 갖는다. 한글은 UTF-8에서 글자당 3바이트라 글자 수만으로는 막을 수 없어
     * {@code LocalAuthService}가 바이트 길이도 함께 검사한다. 여기서는 명백히 긴 입력을
     * 컨트롤러 단에서 빠르게 걷어내는 역할만 한다.
     */
    public static final int PASSWORD_MAX_LENGTH = 72;
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
