package com.nameless0422.MenuPick.common.exception;

import com.nameless0422.MenuPick.common.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 비즈니스 예외 처리.
     * ErrorCode에 정의된 HTTP 상태를 그대로 응답 코드로 사용한다.
     * warn 레벨로 로깅 (스택 트레이스 제외 — 예상된 예외이므로).
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException e) {
        // errorCode.name()으로 로깅 — getMessage()에는 사용자 입력 유래 데이터가 포함될 수 있으므로 DEBUG로 격리
        log.warn("BusinessException: [{}]", e.getErrorCode().name());
        log.debug("BusinessException detail: {}", e.getMessage());
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode, e.getMessage()));
    }

    /**
     * @Valid 검증 실패 처리.
     * 여러 필드 오류를 쉼표로 합쳐 하나의 메시지로 반환한다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException e) {
        List<ApiResponse.FieldErrorDetail> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiResponse.FieldErrorDetail(fe.getField(), fe.getDefaultMessage()))
                .collect(Collectors.toList());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.validationError(errors));
    }

    /**
     * @Validated 파라미터 제약 위반 처리 (@Min/@Max 등).
     * 400 Bad Request를 반환한다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        List<ApiResponse.FieldErrorDetail> errors = e.getConstraintViolations().stream()
                .map(cv -> {
                    String path = cv.getPropertyPath().toString();
                    String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
                    return new ApiResponse.FieldErrorDetail(field, cv.getMessage());
                })
                .collect(Collectors.toList());
        return ResponseEntity
                .badRequest()
                .body(ApiResponse.validationError(errors));
    }

    /**
     * 인자 오류 처리 — 400 반환.
     *
     * <p>원본 메시지는 내부 구현 세부(클래스명, 파싱 대상, enum 상수 목록 등)를 그대로 담고 있어
     * 클라이언트에 노출하지 않는다. INVALID_INPUT 표준 응답만 내려주고 상세는 로그로만 남긴다.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("IllegalArgumentException: {}", e.getMessage());
        return ResponseEntity
                .status(ErrorCode.INVALID_INPUT.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.INVALID_INPUT, ErrorCode.INVALID_INPUT.getMessage()));
    }

    /**
     * Redis 연결 실패 처리 — 503 반환.
     *
     * <p>캐시 경로의 장애는 CacheErrorHandler가 흡수하지만, 캐시를 거치지 않고 Redis를 직접
     * 사용하는 경로(레이트리밋 카운터, 토큰 저장 등)는 여기까지 예외가 올라온다. 500이 아니라
     * 재시도 가능함을 알리는 503 + Retry 안내 메시지로 응답한다.
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<ApiResponse<Void>> handleRedisConnectionFailure(RedisConnectionFailureException e) {
        log.error("Redis connection failure", e);
        return ResponseEntity
                .status(ErrorCode.REDIS_UNAVAILABLE.getHttpStatus())
                .body(ApiResponse.error(ErrorCode.REDIS_UNAVAILABLE, ErrorCode.REDIS_UNAVAILABLE.getMessage()));
    }

    /**
     * DB 유니크/FK 제약 위반 처리.
     * 클라이언트에게는 중립적인 409 Conflict 메시지를 반환하고,
     * 실제 원인은 로그로만 남긴다 (DB 구조 노출 방지).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrityViolation(DataIntegrityViolationException e) {
        // DB 스키마·제약조건명이 노출되지 않도록 상세 메시지는 DEBUG로만 기록
        log.warn("DataIntegrityViolationException: [{}]", e.getMostSpecificCause().getClass().getSimpleName());
        log.debug("DataIntegrityViolationException detail: {}", e.getMostSpecificCause().getMessage());
        return error(ErrorCode.DATA_INTEGRITY_VIOLATION);
    }

    /**
     * JSON 파싱 실패 처리 — malformed JSON body 등.
     * 400 Bad Request를 반환한다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("HttpMessageNotReadableException: {}", e.getMessage());
        return error(ErrorCode.MALFORMED_REQUEST_BODY);
    }

    /**
     * 쿼리 파라미터 타입 불일치 처리 (예: Long 파라미터에 문자열 전달).
     * 400 Bad Request를 반환한다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("MethodArgumentTypeMismatchException: param={}, value={}", e.getName(), e.getValue());
        return error(ErrorCode.INVALID_INPUT,
                "'" + e.getName() + "' 파라미터의 타입이 올바르지 않습니다.");
    }

    /**
     * 필수 쿼리 파라미터 누락 처리.
     * 400 Bad Request를 반환한다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("MissingServletRequestParameterException: param={}", e.getParameterName());
        return error(ErrorCode.INVALID_INPUT,
                "필수 파라미터 '" + e.getParameterName() + "'이(가) 누락되었습니다.");
    }

    /**
     * 지원하지 않는 HTTP 메서드 처리 — 405 Method Not Allowed.
     *
     * <p>이 핸들러가 없으면 405가 아니라 500이 나간다. {@code ExceptionHandlerExceptionResolver}가
     * {@code DefaultHandlerExceptionResolver}보다 먼저 돌기 때문에, 이 클래스가
     * {@code ResponseEntityExceptionHandler}를 상속하지 않는 이상 아래
     * {@code @ExceptionHandler(Exception.class)} catch-all이 먼저 잡아 버린다.
     *
     * <p>상태 코드만의 문제가 아니다. catch-all은 매 건 풀 스택트레이스를 ERROR로 남기는데,
     * PUT/DELETE/TRACE를 훑고 다니는 스캐너 봇 하나면 ERROR 로그가 수천 줄 쌓여
     * compose의 로그 상한(20MB×5)을 채우고 정작 사고 직전 로그를 밀어낸다.
     * 클라이언트 실수로 생기는 예상된 예외이므로 WARN + 스택트레이스 없이 남긴다.
     *
     * <p>{@code Allow} 헤더는 스펙(RFC 9110)상 405 응답의 필수 헤더라 함께 내려준다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("HttpRequestMethodNotSupportedException: method={}", e.getMethod());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        Set<HttpMethod> supported = e.getSupportedHttpMethods();
        if (supported != null && !supported.isEmpty()) {
            builder.allow(supported.toArray(new HttpMethod[0]));
        }
        return builder.body(ApiResponse.error(ErrorCode.METHOD_NOT_ALLOWED,
                ErrorCode.METHOD_NOT_ALLOWED.getMessage()));
    }

    /**
     * 지원하지 않는 Content-Type 처리 — 415 Unsupported Media Type.
     *
     * <p>405와 같은 이유로 개별 핸들러가 필요하다(catch-all에 잡히면 500 + ERROR 스택트레이스).
     * 요청 헤더가 잘못된 것이므로 서버 오류가 아니다.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("HttpMediaTypeNotSupportedException: contentType={}", e.getContentType());
        return error(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * 정적 리소스 없음 처리 — Spring MVC가 발생시키는 NoResourceFoundException을 404로 반환.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        return error(ErrorCode.RESOURCE_NOT_FOUND);
    }

    /**
     * 예상치 못한 서버 오류 처리.
     * 내부 스택 트레이스는 로그에만 남기고, 클라이언트에는 일반 메시지만 반환한다.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception e) {
        log.error("Unexpected error", e);
        return error(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    /**
     * 코드에 딸린 상태·문구를 그대로 쓰는 실패 응답.
     *
     * <p>핸들러마다 상태 코드를 손으로 적으면 ErrorCode의 상태와 어긋날 수 있다 —
     * 그러면 같은 오류가 코드와 HTTP 상태로 서로 다른 말을 하게 된다.
     */
    private static ResponseEntity<ApiResponse<Void>> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getMessage());
    }

    /** 문구만 상황에 맞게 바꾸는 실패 응답 (어느 파라미터인지 등). */
    private static ResponseEntity<ApiResponse<Void>> error(ErrorCode errorCode, String message) {
        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode, message));
    }
}
