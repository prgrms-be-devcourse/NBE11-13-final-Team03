package com.team3.backend.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("yon.notification.publisher")
public record NotificationPublisherProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http") String transport,
        @DefaultValue(NotificationPublisherProperties.UNSET_BASE_URL) String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("10") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT1M") Duration leaseTimeout,
        @DefaultValue("PT1M") Duration retryDelay,
        @DefaultValue("PT1S") Duration connectTimeout,
        @DefaultValue("PT2S") Duration readTimeout
) {
    public NotificationPublisherProperties {
        if (enabled) {
            if (!"http".equals(transport) && !"sqs".equals(transport)) {
                throw new IllegalArgumentException("Notification publisher transport must be http or sqs");
            }
        }
        if (enabled && "http".equals(transport)) {
            if (baseUrl == null || baseUrl.isBlank() || UNSET_BASE_URL.equals(baseUrl)) {
                throw new IllegalArgumentException("NOTIFICATION_BASE_URL is required when the publisher is enabled");
            }
            var uri = URI.create(baseUrl);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("Notification base URL must be an HTTP(S) origin");
            }
            if (apiKey == null || apiKey.isBlank() || apiKey.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("A dedicated Notification internal API key is required");
            }
        }
        if (batchSize < 1 || batchSize > 500 || maxAttempts < 1
                || !positive(leaseTimeout) || !positive(retryDelay)
                || !positive(connectTimeout) || !positive(readTimeout)
                || connectTimeout.toMillis() > Integer.MAX_VALUE || readTimeout.toMillis() > Integer.MAX_VALUE
                || leaseTimeout.compareTo(connectTimeout.plus(readTimeout).plusSeconds(5)) <= 0) {
            throw new IllegalArgumentException("Invalid Notification publisher timing/batch configuration");
        }
    }

    /**
     * No usable default: a forgotten URL must not silently call localhost. The reserved .invalid host never resolves,
     * and the Feign proxy that is registered even while disabled needs a non-empty URL.
     */
    public static final String UNSET_BASE_URL = "http://notification-base-url-not-configured.invalid";

    private static boolean positive(Duration value) { return value != null && value.toMillis() >= 1; }

    @Override public String toString() {
        return "NotificationPublisherProperties[enabled=" + enabled + ", transport=" + transport + ", apiKey=REDACTED]";
    }
}
