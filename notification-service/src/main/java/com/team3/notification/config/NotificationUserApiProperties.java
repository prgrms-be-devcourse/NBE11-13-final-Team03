package com.team3.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Separate from message reception: user APIs must not enable Outbox consumption. */
@ConfigurationProperties("yon.notification.user-api")
public record NotificationUserApiProperties(@DefaultValue("false") boolean enabled,
                                            @DefaultValue("") String apiKey) {
    public NotificationUserApiProperties {
        if (enabled && (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("Notification user API requires a dedicated internal key");
        }
    }
    @Override public String toString() {
        return "NotificationUserApiProperties[enabled=" + enabled + ", apiKey=REDACTED]";
    }
}
