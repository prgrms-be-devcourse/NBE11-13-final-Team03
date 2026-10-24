package com.team3.notification.api;

import com.team3.notification.service.NotificationUserApiException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.Map;

@RestControllerAdvice(assignableTypes = NotificationUserController.class)
public class NotificationUserExceptionHandler {
    @ExceptionHandler(NotificationUserApiException.class)
    ResponseEntity<Map<String, String>> business(NotificationUserApiException error) {
        return ResponseEntity.status(error.status()).body(Map.of("code", error.code()));
    }
    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class, ConstraintViolationException.class})
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_NOTIFICATION_REQUEST"));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code", "NOTIFICATION_UNAVAILABLE"));
    }
}
