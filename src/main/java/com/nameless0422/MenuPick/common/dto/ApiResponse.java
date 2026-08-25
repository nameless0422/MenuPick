package com.nameless0422.MenuPick.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nameless0422.MenuPick.common.exception.ErrorCode;
import lombok.Getter;

import java.util.List;


/**
 * 모든 API 응답을 감싸는 통합 응답 래퍼.
 *
 * <p>성공 시: {@code { "success": true, "data": { ... } }}
 * <br>실패 시: {@code { "success": false, "errorCode": "ORDER_NOT_FOUND", "message": "오류 메시지" }}
 * <br>검증 실패 시: {@code { "success": false, "errors": [{ "field": "email", "message": "..." }] }}
 *
 * <p>null 필드는 JSON 직렬화에서 제외된다 ({@link JsonInclude#NON_NULL}).
 * 생성자를 private으로 막고 정적 팩토리 메서드만 노출해 일관된 형태를 강제한다.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final T data;
    private final String message;

    /** 비즈니스 예외 에러 코드 — null이면 JSON 생략 */
    private final String errorCode;

    /** @Valid 필드별 에러 목록 — null이면 JSON 생략 */
    private final List<FieldErrorDetail> errors;

    private ApiResponse(boolean success, T data, String message,
                        String errorCode, List<FieldErrorDetail> errors) {
        this.success = success;
        this.data = data;
        this.message = message;
        this.errorCode = errorCode;
        this.errors = errors;
    }

    /** 데이터가 있는 성공 응답 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null, null);
    }

    /** 데이터 없는 성공 응답 (204 No Content 등) */
    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(true, null, null, null, null);
    }

    /**
     * 실패 응답 — ErrorCode + message 포함.
     *
     * <p>errorCode 없이 message만 담는 오버로드가 있었으나 제거했다. 그 오버로드를 쓰던
     * 핸들러들(409·400·405·415·404·catch-all 500)은 {@code errorCode}가 null이 되고
     * {@link JsonInclude#NON_NULL} 때문에 <b>필드 자체가 응답에서 사라졌다.</b> 같은 상태
     * 코드에 두 가지 스키마가 존재하는 셈이라, 프론트가 errorCode로 분기하면 어느 쪽이
     * 오느냐에 따라 조용히 깨진다. 오버로드를 없애면 새 핸들러도 코드를 고르지 않을 수 없다.
     */
    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, message, errorCode.name(), null);
    }

    /** @Valid 검증 실패 응답 — 필드별 에러 목록 포함 */
    public static <T> ApiResponse<T> validationError(List<FieldErrorDetail> errors) {
        return new ApiResponse<>(false, null, "입력값이 올바르지 않습니다.", null, errors);
    }

    /** 필드별 에러 정보. */
    public record FieldErrorDetail(String field, String message) {}
}