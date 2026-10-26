package com.team3.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("yon.notification.receiver")
public record NotificationReceiverProperties(@DefaultValue("false") boolean enabled,
                                             @DefaultValue("") String apiKey) {
    public NotificationReceiverProperties {
        if (enabled && (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("A dedicated Notification internal API key is required");
        }
    }

    @Override public String toString() {
        return "NotificationReceiverProperties[enabled=" + enabled + ", apiKey=REDACTED]";
    }
}
