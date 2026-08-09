package com.nameless0422.MenuPick.common.exception;


import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    /**
     * ErrorCode에 정의된 기본 메시지를 사용하는 생성자.
     */
    public BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 기본 메시지 대신 구체적인 메시지를 직접 지정하는 생성자.
     * ID 등 동적 값을 포함한 메시지가 필요할 때 사용한다.
     */
    public BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

}
