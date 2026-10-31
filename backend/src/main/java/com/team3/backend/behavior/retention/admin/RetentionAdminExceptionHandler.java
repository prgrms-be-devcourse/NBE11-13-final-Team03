package com.team3.backend.behavior.retention.admin;

import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.ErrorResponse;
import com.team3.backend.global.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 보관 관리자 API의 권한 오류를 403 RETENTION_001로 응답한다.
 * 없으면 GlobalExceptionHandler의 Exception 처리가 메서드 보안 거부를 500으로 바꾼다 (수집 관리자 API와 같은 방식).
 */
@RestControllerAdvice(assignableTypes = RetentionAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RetentionAdminExceptionHandler {

    private final GlobalExceptionHandler global;

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accessDenied() {
        return global.handleBusinessException(new BusinessException(RetentionAdminErrorCode.ACCESS_DENIED));
    }
}
