package com.nameless0422.MenuPick.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;


/**
 * 전역 에러 코드
 *
 * <p>각 코드는 HTTP 상태 코드와 사용자에게 노출할 메시지를 함께 보관
 * {@link GlobalExceptionHandler}가 값을 읽어 응답 상태 본문 결정
 *
 * <p>도메인별로 그룹을 나눠 관리
 * 새로운 도메인을 추가할 때 해당 그룹에
 * 코드를 추가
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),
    FORBIDDEN(HttpStatus.FORBIDDEN, "접근 권한이 없습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "잘못된 입력값입니다."),

    // --- 프레임워크가 던지는 요청 오류. GlobalExceptionHandler가 쓴다 ---
    // 예전에는 이 응답들이 errorCode 없이 message만 담아, 같은 상태 코드에 두 가지 스키마가
    // 존재했다(@JsonInclude(NON_NULL) 때문에 필드 자체가 사라진다). 프론트가 errorCode로
    // 분기하면 어느 쪽이 오느냐에 따라 조용히 깨진다.
    MALFORMED_REQUEST_BODY(HttpStatus.BAD_REQUEST, "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요."),
    DATA_INTEGRITY_VIOLATION(HttpStatus.CONFLICT, "데이터 무결성 제약 조건을 위반했습니다."),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 HTTP 메서드입니다."),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "지원하지 않는 Content-Type입니다."),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 리소스를 찾을 수 없습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    TOO_MANY_LOGIN_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도 횟수를 초과했습니다. 15분 후 다시 시도해주세요."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),
    REDIS_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "일시적인 서버 오류입니다. 잠시 후 다시 시도해주세요."),

    // --- Auth ---
    OAUTH_INVALID_CODE(HttpStatus.UNAUTHORIZED, "소셜 로그인 인가 코드가 유효하지 않습니다. 다시 로그인해주세요."),
    OAUTH_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, "소셜 로그인 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요."),

    // --- Auth: 소셜 연동 ---
    // 소셜 로그인은 더 이상 가입 경로가 아니다. 카카오에서 이메일을 받으려면 비즈앱 전환이
    // 필요한데 그게 불가능해, 소셜로 계정을 만들면 비밀번호 재설정도 메일 수신도 못 하는
    // 고아 계정만 남는다. 그래서 가입은 이메일로만 받고 소셜은 연동 수단으로만 쓴다.
    // 401을 쓰는 이유는 이것이 "인증 실패"이기 때문이다 — 프론트의 PRE_AUTH_PATHS에
    // 들어 있는 경로라 401이 토큰 재발급 재시도를 유발하지도 않는다.
    SOCIAL_ACCOUNT_NOT_LINKED(HttpStatus.UNAUTHORIZED,
            "연동된 계정이 없습니다. 이메일로 가입한 뒤 설정에서 소셜 계정을 연동해주세요."),
    // 남의 소셜 계정을 자기 계정에 붙이려는 시도까지 포함한다 — 허용하면 그 소셜 계정의
    // 주인이 다음 로그인부터 공격자 계정으로 들어오게 된다.
    SOCIAL_ACCOUNT_TAKEN(HttpStatus.CONFLICT, "이미 다른 계정에 연동된 소셜 계정입니다."),
    SOCIAL_ALREADY_LINKED(HttpStatus.CONFLICT, "이미 연동된 소셜 계정입니다."),
    SOCIAL_LINK_NOT_FOUND(HttpStatus.NOT_FOUND, "연동되지 않은 소셜 계정입니다."),
    // 이걸 막지 않으면 비밀번호 없는 계정이 마지막 연동을 끊는 순간 영원히 들어갈 수 없게 된다.
    LAST_LOGIN_METHOD(HttpStatus.CONFLICT,
            "마지막 로그인 수단이라 해제할 수 없습니다. 비밀번호를 먼저 설정해주세요."),

    // --- Auth: 자체 계정 ---
    // 자체 가입에서만 쓴다. 소셜 로그인은 닉네임을 제공자가 정해 사용자가 고칠 수 없으므로
    // 거절하면 로그인 자체가 막힌다 — 그쪽은 NicknameAllocator가 번호를 붙여 통과시킨다.
    NICKNAME_ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다."),
    // 이메일 존재 여부를 흘리지 않도록 "없는 계정"과 "틀린 비밀번호"에 같은 코드를 쓴다.
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다."),
    EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "이메일 인증이 필요합니다. 받은 메일의 링크를 눌러주세요."),
    INVALID_AUTH_TOKEN(HttpStatus.BAD_REQUEST, "링크가 만료되었거나 이미 사용되었습니다. 다시 요청해주세요."),
    LOCAL_ACCOUNT_REQUIRED(HttpStatus.BAD_REQUEST, "비밀번호로 로그인하는 계정이 아닙니다."),
    // 인증 흐름에서는 클라이언트에 도달하지 않는다 — 발송이 비동기라 AuthMailer가 잡아 로그로만 남긴다.
    // EmailSender를 동기로 부르는 곳이 생기면 그때 이 상태 코드가 쓰인다.
    MAIL_SEND_FAILED(HttpStatus.BAD_GATEWAY, "메일 발송에 실패했습니다. 잠시 후 다시 시도해주세요."),

    // --- Menu ---
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다."),

    // --- Tag ---
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "태그를 찾을 수 없습니다."),
    TAG_DUPLICATE(HttpStatus.CONFLICT, "이미 존재하는 태그입니다."),

    // --- MenuRestaurant ---
    MENU_RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "메뉴-식당 연결을 찾을 수 없습니다."),
    MENU_RESTAURANT_DUPLICATE(HttpStatus.CONFLICT, "이미 연결된 메뉴-식당입니다."),

    // --- Restaurant ---
    RESTAURANT_NOT_FOUND(HttpStatus.NOT_FOUND, "식당을 찾을 수 없습니다."),

    // --- Naver Maps ---
    NAVER_MAPS_API_ERROR(HttpStatus.BAD_GATEWAY, "네이버 지도 API 호출에 실패했습니다."),

    // --- Kakao Local ---
    KAKAO_LOCAL_API_ERROR(HttpStatus.BAD_GATEWAY, "카카오 로컬 API 호출에 실패했습니다."),

    // --- Pick ---
    NO_PICK_CANDIDATES(HttpStatus.NOT_FOUND, "필터 조건에 맞는 메뉴가 없습니다."),

    // --- History ---
    HISTORY_NOT_FOUND(HttpStatus.NOT_FOUND, "히스토리를 찾을 수 없습니다.");

    private final HttpStatus httpStatus;
    /** 클라이언트에 그대로 전달되는 메시지 — 민감 정보를 포함하지 않아야 한다. */
    private final String message;

}
