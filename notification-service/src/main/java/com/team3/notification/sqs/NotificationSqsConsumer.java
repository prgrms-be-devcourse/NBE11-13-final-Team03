package com.team3.notification.sqs;

import com.team3.notification.config.NotificationSqsProperties;
import com.team3.notification.service.NotificationMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.Objects;

public class NotificationSqsConsumer {
    private static final Logger log = LoggerFactory.getLogger(NotificationSqsConsumer.class);
    private final SqsClient sqs;
    private final NotificationSqsProperties properties;
    private final NotificationSqsTopology topology;
    private final NotificationMessageHandler handler;

    public NotificationSqsConsumer(SqsClient sqs, NotificationSqsProperties properties,
            NotificationSqsTopology topology, NotificationMessageHandler handler) {
        this.sqs = sqs; this.properties = properties; this.topology = topology; this.handler = handler;
    }

    @Scheduled(fixedDelayString = "${yon.notification.sqs.poll-delay-ms:1000}", scheduler = "notificationSqsScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runOnce() {
        ReceiveMessageResponse response;
        try {
            topology.verify();
            // One at a time: queued messages must not spend their visibility window waiting for earlier DB work.
            response = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(1).waitTimeSeconds(properties.waitTimeSeconds())
                    .visibilityTimeout(properties.visibilityTimeoutSeconds()).build());
        } catch (RuntimeException failure) {
            log.error("Notification SQS poll stopped: code=SQS_POLL_OR_CONFIGURATION_FAILURE");
            return;
        }
        for (var message : response.messages()) {
            if (message.receiptHandle() == null || message.receiptHandle().isBlank()) {
                log.warn("Notification SQS message retained: code=SQS_RECEIPT_HANDLE_MISSING");
                continue;
            }
            try {
                // REQUIRES_NEW receive()/withdrawal transactions have committed before handle() returns.
                var result = Objects.requireNonNull(handler.handle(message.body()));
                log.info("Notification SQS consumed: messageId={}, result={}, duplicateMessage={}",
                        result.messageId(), result.status(), result.duplicateMessage());
            } catch (RuntimeException failure) {
                // Invalid envelopes/conflicts are NOT acknowledged either: SQS redrive policy isolates them in the DLQ.
                log.warn("Notification SQS message retained: code=SQS_HANDLER_FAILURE");
                continue;
            }
            try {
                // Use THIS delivery's receipt handle, never application/SQS messageId or an earlier handle.
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                        .receiptHandle(message.receiptHandle()).build());
            } catch (RuntimeException failure) {
                // DB commit stays durable. Re-delivery goes through the same idempotent Handler.
                log.warn("Notification SQS delete failed after DB commit: code=SQS_DELETE_FAILURE");
            }
        }
    }
}
