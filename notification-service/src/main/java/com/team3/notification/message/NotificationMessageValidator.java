package com.team3.notification.message;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.net.URI;
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
}
