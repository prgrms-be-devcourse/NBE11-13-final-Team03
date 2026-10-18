package com.team3.backend.global.exception;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    // 1. 비즈니스 예외 처리
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException exception) {
        ErrorCode errorCode = exception.getErrorCode();

        log.warn(
                "Business exception: code={}, message={}",
                errorCode.getCode(),
                exception.getMessage()
        );

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ErrorResponse.from(errorCode));
    }

    // 2. @Valid 검증 예외 처리
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidException(MethodArgumentNotValidException exception) {
        Map<String, String> fieldErrors = exception
                .getBindingResult()
                .getFieldErrors()
                .stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        error -> Objects.requireNonNullElse(
                                error.getDefaultMessage(),
                                "잘못된 요청입니다."
                        ),
                        (first, second) -> first, LinkedHashMap::new
                ));

        log.warn("Validation failed: {}", fieldErrors);

        ErrorCode errorCode = GlobalErrorCode.INVALID_INPUT_VALUE;
        ErrorResponse response = ErrorResponse.validation(
                errorCode.getCode(),
                errorCode.getMessage(),
                fieldErrors
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    // 3. 파라미터 타입 불일치 예외 처리
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentTypeMismatchException(MethodArgumentTypeMismatchException exception) {
        log.warn(
                "Argument type mismatch. parameter={}, value={}",
                exception.getName(),
                exception.getValue()
        );

        ErrorCode errorCode = GlobalErrorCode.TYPE_MISMATCH;
        ErrorResponse response = ErrorResponse.validation(
                errorCode.getCode(),
                errorCode.getMessage(),
                Map.of()
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(response);
    }

    // 4. 예상치 못한 500 서버 내부 에러 처리
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception exception) {
        log.error("Unhandled Exception occurred: ", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.internalServerError());
    }
}
