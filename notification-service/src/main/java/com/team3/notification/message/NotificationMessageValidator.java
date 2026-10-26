package com.team3.notification.message;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;

@Component
public class NotificationMessageValidator {
    private final Validator validator;

    public NotificationMessageValidator(Validator validator) {
        this.validator = validator;
    }

    public void validate(NotificationRequestedMessage message) {
        Objects.requireNonNull(message, "message");
        var violations = validator.validate(message);
        if (!violations.isEmpty()) throw new ConstraintViolationException(violations);
        // PostgreSQL text/jsonb cannot store NUL; reject it here (400) instead of failing the insert (503, retried).
        if (containsNul(message.title()) || containsNul(message.content()) || containsNul(message.payload())) {
            throw new IllegalArgumentException("Notification text must not contain NUL characters");
        }
        if (!message.expiresAt().isAfter(message.occurredAt())) {
            throw new IllegalArgumentException("expiresAt must be after occurredAt");
        }
        var uri = URI.create(message.link());
        String path = uri.getPath();
        if (uri.isAbsolute() || uri.getRawAuthority() != null || path == null || !path.startsWith("/")
                || path.startsWith("//") || path.contains("\\")
                || path.chars().anyMatch(Character::isISOControl)
                || message.link().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Notification link must be an internal absolute path");
        }
    }

    private static boolean containsNul(Object value) {
        if (value instanceof String text) return text.indexOf('\u0000') >= 0;
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(e -> containsNul(e.getKey()) || containsNul(e.getValue()));
        }
        if (value instanceof Collection<?> values) return values.stream().anyMatch(NotificationMessageValidator::containsNul);
        return false;
    }
}
