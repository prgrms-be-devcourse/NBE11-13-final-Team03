package com.team3.backend.collection.exception;

import com.team3.backend.collection.controller.CollectionAdminController;
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
 * 수집 관리자 API의 권한 오류를 기존과 같은 COLLECTION_007로 응답한다.
 * 그 밖의 수집 에러(COLLECTION_001~006)는 DataService가 만든 응답을 그대로 전달한다.
 */
@RestControllerAdvice(assignableTypes = CollectionAdminController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class CollectionExceptionHandler {

    private final GlobalExceptionHandler global;

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accessDenied() {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.ACCESS_DENIED));
    }
}
