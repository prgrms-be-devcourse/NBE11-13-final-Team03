package com.team3.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import java.net.URI;
import java.util.Set;

@ConfigurationProperties("yon.notification.sqs")
public record NotificationSqsProperties(@DefaultValue("false") boolean consumerEnabled,
        @DefaultValue("") String queueUrl, @DefaultValue("") String dlqUrl,
        @DefaultValue("") String region, @DefaultValue("") String endpoint,
        @DefaultValue("10") int waitTimeSeconds, @DefaultValue("120") int visibilityTimeoutSeconds) {
    public NotificationSqsProperties {
        if (waitTimeSeconds < 0 || waitTimeSeconds > 20 || visibilityTimeoutSeconds < 30
                || visibilityTimeoutSeconds > 43200) {
            throw new IllegalArgumentException("Invalid SQS polling/visibility timing");
        }
        if (consumerEnabled) {
            if (region.isBlank() || !region.matches("[a-z]{2}(?:-[a-z]+)+-\\d+")) {
                throw new IllegalArgumentException("NOTIFICATION_SQS_REGION is required");
            }
            validateQueue(queueUrl, region, endpoint);
            validateQueue(dlqUrl, region, endpoint);
            if (queueUrl.equals(dlqUrl)) throw new IllegalArgumentException("SQS source queue and DLQ must differ");
        }
    }

    public String expectedArn(String url) {
        var segments = URI.create(url).getPath().split("/");
        String partition = region.startsWith("cn-") ? "aws-cn" : region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
        return "arn:" + partition + ":sqs:" + region + ":" + segments[1] + ":" + segments[2];
    }

    private static void validateQueue(String url, String region, String endpoint) {
        var queue = uri(url);
        if (!queue.getPath().matches("/[0-9]{12}/[a-zA-Z0-9_-]{1,80}")) {
            throw new IllegalArgumentException("A Standard SQS queue URL is required (source and DLQ)");
        }
        if (endpoint.isBlank()) {
            if (!"https".equals(queue.getScheme()) || queue.getPort() != -1
                    || !Set.of("sqs." + region + ".amazonaws.com", "sqs." + region + ".amazonaws.com.cn").contains(queue.getHost())) {
                throw new IllegalArgumentException("SQS queue region/endpoint mismatch");
            }
        } else {
            var local = uri(endpoint);
            if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(local.getHost())
                    || !(local.getPath().isEmpty() || local.getPath().equals("/"))
                    || !queue.getScheme().equals(local.getScheme()) || !queue.getHost().equals(local.getHost())
                    || queue.getPort() != local.getPort()) {
                throw new IllegalArgumentException("SQS override must use the same loopback origin");
            }
        }
    }

    private static URI uri(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("SQS source and DLQ URLs are required");
        var uri = URI.create(value);
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("SQS URL must not contain credentials/query/fragment");
        }
        return uri;
    }
}
