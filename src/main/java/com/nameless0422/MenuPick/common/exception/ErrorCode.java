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
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    LOCK_ACQUISITION_FAILED(HttpStatus.TOO_MANY_REQUESTS, "현재 요청이 많습니다. 잠시 후 다시 시도해주세요."),
    TOO_MANY_LOGIN_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도 횟수를 초과했습니다. 15분 후 다시 시도해주세요."),
    TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "요청 한도를 초과했습니다. 잠시 후 다시 시도해주세요."),

    // --- Menu ---
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다."),
    MENU_ACCESS_DENIED(HttpStatus.FORBIDDEN, "해당 메뉴에 대한 접근 권한이 없습니다."),

    // --- Tag ---
    TAG_NOT_FOUND(HttpStatus.NOT_FOUND, "태그를 찾을 수 없습니다."),
    TAG_DUPLICATE(HttpStatus.CONFLICT, "이미 존재하는 태그입니다.");

    private final HttpStatus httpStatus;
    /** 클라이언트에 그대로 전달되는 메시지 — 민감 정보를 포함하지 않아야 한다. */
    private final String message;

}
