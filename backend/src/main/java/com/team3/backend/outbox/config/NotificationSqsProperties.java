package com.team3.backend.outbox.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties("yon.notification.sqs")
public record NotificationSqsProperties(@DefaultValue("") String queueUrl,
                                        @DefaultValue("") String region,
                                        @DefaultValue("") String endpoint) {
    public void validate(NotificationPublisherProperties publisher) {
        if (region.isBlank() || !region.matches("[a-z]{2}(?:-[a-z]+)+-\\d+")) {
            throw new IllegalArgumentException("NOTIFICATION_SQS_REGION is required");
        }
        var queue = origin(queueUrl, "NOTIFICATION_SQS_QUEUE_URL");
        if (queue.getPath().isBlank() || queue.getPath().equals("/") || queue.getPath().endsWith(".fifo")) {
            throw new IllegalArgumentException("A Standard SQS queue URL is required");
        }
        if (endpoint.isBlank()) {
            if (!"https".equals(queue.getScheme()) || !Set.of("sqs." + region + ".amazonaws.com",
                    "sqs." + region + ".amazonaws.com.cn").contains(queue.getHost()) || queue.getPort() != -1) {
                throw new IllegalArgumentException("Queue URL must match the regional AWS SQS endpoint");
            }
        } else {
            var local = origin(endpoint, "NOTIFICATION_SQS_ENDPOINT");
            if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(local.getHost())
                    || !(local.getPath().isEmpty() || local.getPath().equals("/"))
                    || !queue.getScheme().equals(local.getScheme()) || !queue.getHost().equals(local.getHost())
                    || queue.getPort() != local.getPort()) {
                throw new IllegalArgumentException("SQS endpoint override is restricted to the same loopback origin");
            }
        }
        if (publisher.leaseTimeout().compareTo(Duration.ofSeconds(15)) <= 0) {
            throw new IllegalArgumentException("SQS publisher lease must exceed the 10-second API timeout plus recovery margin");
        }
    }

    private static URI origin(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        var uri = URI.create(value);
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(name + " must be an HTTP(S) URL without credentials/query/fragment");
        }
        return uri;
    }
}
