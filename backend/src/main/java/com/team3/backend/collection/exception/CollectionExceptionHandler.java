package com.team3.backend.collection.exception;

import com.team3.backend.collection.controller.ManualSyncController;
import com.team3.backend.collection.controller.RawRecordController;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.core.task.TaskRejectedException;
import com.team3.backend.global.exception.BusinessException;
import com.team3.backend.global.exception.ErrorResponse;
import com.team3.backend.global.exception.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {RawRecordController.class, ManualSyncController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class CollectionExceptionHandler {
    private final GlobalExceptionHandler global;

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> accessDenied() {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.ACCESS_DENIED));
    }

    @ExceptionHandler(TaskRejectedException.class)
    public ResponseEntity<ErrorResponse> executorUnavailable() {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.EXECUTOR_UNAVAILABLE));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> invalidBody() {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.INVALID_BODY));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidArgument(IllegalArgumentException exception) {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.INVALID_ARGUMENT));
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(EntityNotFoundException exception) {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> invalidState(IllegalStateException exception) {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.INVALID_STATE));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ErrorResponse> storageFailure() {
        return global.handleBusinessException(new BusinessException(CollectionErrorCode.STORAGE_FAILURE));
    }
}
