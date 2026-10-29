package com.team3.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.util.Set;

/** Explicit operator opt-in; this key is separate from the normal facade/receiver key. */
@ConfigurationProperties("yon.notification.test-push")
public record NotificationTestPushProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String apiKey, Set<Long> allowedUserIds) {
    public NotificationTestPushProperties {
        allowedUserIds = allowedUserIds == null ? Set.of() : Set.copyOf(allowedUserIds);
        if (enabled && (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(Character::isISOControl)
                || allowedUserIds.isEmpty() || allowedUserIds.stream().anyMatch(id -> id <= 0))) {
            throw new IllegalArgumentException("Test push requires a dedicated internal key and positive allowed user IDs");
        }
    }

    @Override public String toString() {
        return "NotificationTestPushProperties[enabled=" + enabled + ", apiKey=REDACTED, allowedUserIds=" + allowedUserIds + "]";
    }
}
