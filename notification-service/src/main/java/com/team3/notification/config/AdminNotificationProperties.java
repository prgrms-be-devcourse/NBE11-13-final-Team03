package com.team3.notification.config;

import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("yon.notification.admin")
public record AdminNotificationProperties(@DefaultValue("false") boolean consumerEnabled,
        @DefaultValue("") String queueUrl, @DefaultValue("") String dlqUrl,
        @DefaultValue("") String region, @DefaultValue("") String endpoint,
        @DefaultValue("10") int waitTimeSeconds, @DefaultValue("60") int visibilityTimeoutSeconds,
        @DefaultValue("") String slackWebhookUrl, @DefaultValue("1000") long pollDelayMs) {
    public AdminNotificationProperties {
        if (visibilityTimeoutSeconds < 60 || pollDelayMs < 1) {
            throw new IllegalArgumentException("Invalid administrator polling/visibility timing");
        }
        try {
            new NotificationSqsProperties(consumerEnabled, queueUrl, dlqUrl, region, endpoint, waitTimeSeconds, visibilityTimeoutSeconds);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid administrator SQS configuration");
        }
        if (consumerEnabled) validateWebhook(slackWebhookUrl);
    }

    public NotificationSqsProperties sqsSettings() {
        return new NotificationSqsProperties(consumerEnabled, queueUrl, dlqUrl, region, endpoint, waitTimeSeconds, visibilityTimeoutSeconds);
    }

    private static void validateWebhook(String value) {
        URI uri;
        try { uri = URI.create(value); }
        catch (RuntimeException failure) { throw new IllegalArgumentException("Administrator Slack webhook URL is required"); }
        boolean local = uri.getHost() != null && "http".equals(uri.getScheme())
                && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        boolean slack = uri.getHost() != null && "https".equals(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 443)
                && Set.of("hooks.slack.com", "hooks.slack-gov.com").contains(uri.getHost())
                && uri.getPath().matches("/services/[^/]+/[^/]+/[^/]+");
        if ((!local && !slack) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Administrator Slack webhook must be HTTPS Slack or an explicit loopback test URL");
        }
    }

    @Override public String toString() { return "AdminNotificationProperties[consumerEnabled=" + consumerEnabled + ", webhook=REDACTED]"; }
}
