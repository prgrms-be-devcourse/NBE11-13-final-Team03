package com.team3.backend.collection.controller;

import jakarta.persistence.EntityNotFoundException;
import org.springframework.dao.DataAccessException;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {RawRecordController.class, ManualSyncController.class})
public class CollectionExceptionHandler {
    @ExceptionHandler(TaskRejectedException.class)
    public ProblemDetail executorUnavailable() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "최신화 실행기를 사용할 수 없습니다.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail invalidBody() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "요청 JSON의 필수값과 형식을 확인하세요.");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail invalidArgument(IllegalArgumentException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(EntityNotFoundException.class)
    public ProblemDetail notFound(EntityNotFoundException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail invalidState(IllegalStateException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail storageFailure() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "원본 데이터 저장소 처리에 실패했습니다.");
    }
}
