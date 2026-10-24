package com.team3.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("yon.notification.push")
public record NotificationPushProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("") String firebaseProjectId, @DefaultValue("") String publicBaseUrl,
        @DefaultValue("10") int batchSize, @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT2M") Duration leaseTimeout, @DefaultValue("PT1M") Duration retryDelay,
        @DefaultValue("PT10S") Duration sendTimeout) {
    public NotificationPushProperties {
        if (batchSize < 1 || batchSize > 100 || maxAttempts < 1 || !positive(retryDelay) || !positive(sendTimeout)
                || sendTimeout.toMillis() > 30_000 || !positive(leaseTimeout)
                || leaseTimeout.compareTo(sendTimeout.plusSeconds(10)) <= 0) {
            throw new IllegalArgumentException("Invalid Notification push timing/batch configuration");
        }
        if (enabled) {
            if (firebaseProjectId == null || !firebaseProjectId.matches("[a-z0-9][a-z0-9-]{3,62}")) {
                throw new IllegalArgumentException("FCM_PROJECT_ID is required when push is enabled");
            }
            var uri = URI.create(publicBaseUrl);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("Notification push requires a public HTTPS origin");
            }
        }
    }
    private static boolean positive(Duration duration) { return duration != null && duration.toMillis() >= 1; }
}
