package com.team3.backend.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;

@ConfigurationProperties("yon.notification.facade")
public record NotificationFacadeProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("http://notification-base-url-not-configured.invalid") String baseUrl,
        @DefaultValue("") String apiKey, @DefaultValue("8") int maxConcurrentRequests) {
    public NotificationFacadeProperties {
        if (maxConcurrentRequests < 1 || maxConcurrentRequests > 100) {
            throw new IllegalArgumentException("Invalid Notification facade concurrency limit");
        }
        if (enabled) {
            var uri = URI.create(baseUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getHost().endsWith(".invalid") || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("Notification facade requires an HTTP(S) origin");
            }
            if (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("Notification facade requires a dedicated internal key");
            }
        }
    }
    @Override public String toString() {
        return "NotificationFacadeProperties[enabled=" + enabled + ", apiKey=REDACTED]";
    }
}
