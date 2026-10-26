package com.team3.dataservice.outbox.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("yon.collection.notification.publisher")
public record CollectionNotificationPublisherProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String region,
        @DefaultValue("") String queueUrl,
        @DefaultValue("") String endpoint,
        @DefaultValue("10") int batchSize,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT1M") Duration leaseTimeout,
        @DefaultValue("PT1M") Duration retryDelay,
        @DefaultValue("5000") long pollDelayMs) {
    public CollectionNotificationPublisherProperties {
        if (batchSize < 1 || batchSize > 500 || maxAttempts < 1 || pollDelayMs < 1
                || leaseTimeout == null || leaseTimeout.compareTo(Duration.ofSeconds(15)) <= 0
                || retryDelay == null || retryDelay.toMillis() < 1) {
            throw new IllegalArgumentException("Invalid collection publisher timing/batch configuration");
        }
        if (enabled) {
            if (region == null || !region.matches("[a-z]{2}(?:-[a-z]+)+-\\d+")) {
                throw new IllegalArgumentException("COLLECTION_NOTIFICATION_SQS_REGION is required");
            }
            var queue = url(queueUrl, "COLLECTION_NOTIFICATION_SQS_QUEUE_URL");
            if (queue.getPath().isBlank() || queue.getPath().equals("/") || queue.getPath().endsWith(".fifo")) {
                throw new IllegalArgumentException("A Standard administrator SQS queue URL is required");
            }
            if (endpoint == null || endpoint.isBlank()) {
                if (!"https".equals(queue.getScheme()) || queue.getPort() != -1
                        || !Set.of("sqs." + region + ".amazonaws.com", "sqs." + region + ".amazonaws.com.cn")
                        .contains(queue.getHost())) {
                    throw new IllegalArgumentException("Queue URL must match the regional AWS SQS endpoint");
                }
            } else {
                var local = url(endpoint, "COLLECTION_NOTIFICATION_SQS_ENDPOINT");
                if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(local.getHost())
                        || !(local.getPath().isEmpty() || local.getPath().equals("/"))
                        || !queue.getScheme().equals(local.getScheme()) || !queue.getHost().equals(local.getHost())
                        || queue.getPort() != local.getPort()) {
                    throw new IllegalArgumentException("SQS endpoint override requires the same loopback origin");
                }
            }
        }
    }

    private static URI url(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        URI uri;
        try { uri = URI.create(value); }
        catch (IllegalArgumentException failure) { throw new IllegalArgumentException(name + " is not a valid URL"); }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(name + " must be an HTTP(S) URL without credentials/query/fragment");
        }
        return uri;
    }

    @Override public String toString() {
        return "CollectionNotificationPublisherProperties[enabled=" + enabled + "]";
    }
}
