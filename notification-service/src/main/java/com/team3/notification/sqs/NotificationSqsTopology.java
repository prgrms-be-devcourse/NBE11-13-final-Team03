package com.team3.notification.sqs;

import com.team3.notification.config.NotificationSqsProperties;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/** Read-only operational guard. No queue creation, attribute mutation, redrive or DLQ consumption. */
public class NotificationSqsTopology {
    private final SqsClient sqs;
    private final NotificationSqsProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private Instant validUntil = Instant.MIN;

    public NotificationSqsTopology(SqsClient sqs, NotificationSqsProperties properties, ObjectMapper mapper, Clock clock) {
        this.sqs = sqs; this.properties = properties; this.mapper = mapper; this.clock = clock;
    }

    public synchronized void verify() {
        if (clock.instant().isBefore(validUntil)) return;
        var source = attributes(properties.queueUrl());
        var dlq = attributes(properties.dlqUrl());
        try {
            String sourceArn = properties.expectedArn(properties.queueUrl());
            String dlqArn = properties.expectedArn(properties.dlqUrl());
            var redrive = mapper.readTree(source.get(QueueAttributeName.REDRIVE_POLICY));
            var allow = mapper.readTree(dlq.get(QueueAttributeName.REDRIVE_ALLOW_POLICY));
            require(sourceArn.equals(source.get(QueueAttributeName.QUEUE_ARN)));
            require(dlqArn.equals(dlq.get(QueueAttributeName.QUEUE_ARN)));
            require(!"true".equals(source.get(QueueAttributeName.FIFO_QUEUE)) && !"true".equals(dlq.get(QueueAttributeName.FIFO_QUEUE)));
            require(dlqArn.equals(redrive.path("deadLetterTargetArn").asText()));
            int receiveCount = Integer.parseInt(redrive.path("maxReceiveCount").asText());
            require(receiveCount >= 1 && receiveCount <= 1000);
            require("byQueue".equals(allow.path("redrivePermission").asText()));
            boolean allowed = false;
            for (var arn : allow.path("sourceQueueArns")) if (sourceArn.equals(arn.asText())) allowed = true;
            require(allowed);
            require(Integer.parseInt(source.get(QueueAttributeName.VISIBILITY_TIMEOUT)) == properties.visibilityTimeoutSeconds());
            int sourceRetention = Integer.parseInt(source.get(QueueAttributeName.MESSAGE_RETENTION_PERIOD));
            int dlqRetention = Integer.parseInt(dlq.get(QueueAttributeName.MESSAGE_RETENTION_PERIOD));
            require(sourceRetention >= 60 && sourceRetention < dlqRetention && dlqRetention <= 1209600);
        } catch (RuntimeException failure) {
            throw new IllegalStateException("SQS_TOPOLOGY_INVALID");
        }
        validUntil = clock.instant().plusSeconds(60);
    }

    private Map<QueueAttributeName, String> attributes(String url) {
        return sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                .attributeNames(QueueAttributeName.ALL).build()).attributes();
    }

    private static void require(boolean condition) {
        if (!condition) throw new IllegalStateException("SQS_TOPOLOGY_INVALID");
    }
}
