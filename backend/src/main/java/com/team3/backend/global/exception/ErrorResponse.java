package com.team3.backend.global.exception;

import java.util.Map;

public record ErrorResponse(
        String code,
        String message,
        Map<String, String> fieldErrors
) {
    // 기본
    public static ErrorResponse from(ErrorCode errorCode) {
        return new ErrorResponse(
                errorCode.getCode(),
                errorCode.getMessage(),
                Map.of()
        );
    }

    // 검증 오류 시
    public static ErrorResponse validation(String code, String message, Map<String, String> fieldErrors) {
        return new ErrorResponse(
                code,
                message,
                fieldErrors
        );
    }

    // 서버 에러
    public static ErrorResponse internalServerError() {
        return new ErrorResponse(
                "COMMON_500",
                "서버 내부 오류가 발생했습니다.",
                Map.of()
        );
    }
}
