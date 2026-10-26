package com.team3.notification.message;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
        if (message.type() == NotificationType.NEW_MATCH) validateNewMatch(message);
    }

    private static void validateNewMatch(NotificationRequestedMessage message) {
        Map<String, Object> payload = message.payload();
        if (!"FIRST_MATCH".equals(payload.get("reason")) || !Boolean.TRUE.equals(payload.get("summary"))) {
            throw new IllegalArgumentException("NEW_MATCH requires a first-match summary");
        }
        try {
            if (!message.sourceEventId().equals(UUID.fromString((String) payload.get("summaryRunId")))) {
                throw new IllegalArgumentException("NEW_MATCH summaryRunId must equal sourceEventId");
            }
            LocalDate.parse((String) payload.get("evaluatedOn"));
        } catch (ClassCastException | NullPointerException | java.time.format.DateTimeParseException | IllegalArgumentException error) {
            throw new IllegalArgumentException("NEW_MATCH requires a valid summaryRunId and evaluatedOn", error);
        }
        long match = summaryCount(payload, "matchCount");
        long eligible = summaryCount(payload, "eligibleCount");
        long possibly = summaryCount(payload, "possiblyCount");
        long maxUnresolved = summaryCount(payload, "maxUnresolved");
        if (match == 0 || eligible + possibly != match || (possibly == 0 && maxUnresolved != 0)
                || (possibly > 0 && !Objects.equals(payload.get("possiblyLabel"), "확인 필요 " + maxUnresolved + "개"))) {
            throw new IllegalArgumentException("NEW_MATCH summary counts or label are inconsistent");
        }
    }

    private static long summaryCount(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
                || ((Number) value).longValue() < 0 || ((Number) value).longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("NEW_MATCH " + key + " must be a nonnegative integer");
        }
        return ((Number) value).longValue();
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
