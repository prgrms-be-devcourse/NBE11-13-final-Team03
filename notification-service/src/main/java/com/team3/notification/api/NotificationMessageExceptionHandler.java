package com.team3.notification.api;

import com.team3.notification.service.MessageIdentityConflictException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.core.JacksonException;
import java.util.Map;

/** No exception messages, message bodies or credential values are returned to the caller. */
@RestControllerAdvice(assignableTypes = NotificationMessageController.class)
public class NotificationMessageExceptionHandler {
    @ExceptionHandler(MessageIdentityConflictException.class)
    ResponseEntity<Map<String, String>> identityConflict() {
        return ResponseEntity.status(409).body(Map.of("code", "MESSAGE_IDENTITY_CONFLICT"));
    }

    @ExceptionHandler({ConstraintViolationException.class, IllegalArgumentException.class, JacksonException.class})
    ResponseEntity<Map<String, String>> invalidMessage() {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_NOTIFICATION_MESSAGE"));
    }

    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(503).body(Map.of("code", "NOTIFICATION_UNAVAILABLE"));
    }
}
