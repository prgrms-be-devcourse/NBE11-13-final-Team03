package com.team3.notification.admin;

import com.team3.notification.config.AdminNotificationProperties;
import com.team3.notification.sqs.NotificationSqsTopology;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

public class AdminNotificationSqsConsumer {
    private static final Logger log = LoggerFactory.getLogger(AdminNotificationSqsConsumer.class);
    private final SqsClient sqs;
    private final AdminNotificationProperties properties;
    private final NotificationSqsTopology topology;
    private final AdminNotificationHandler handler;

    public AdminNotificationSqsConsumer(SqsClient sqs, AdminNotificationProperties properties,
                                        NotificationSqsTopology topology, AdminNotificationHandler handler) {
        this.sqs = sqs; this.properties = properties; this.topology = topology; this.handler = handler;
    }

    @Scheduled(fixedDelayString = "${yon.notification.admin.poll-delay-ms:1000}", scheduler = "adminNotificationSqsScheduler")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void runOnce() {
        ReceiveMessageResponse response;
        try {
            topology.verify();
            response = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(properties.queueUrl())
                    .maxNumberOfMessages(1).waitTimeSeconds(properties.waitTimeSeconds())
                    .visibilityTimeout(properties.visibilityTimeoutSeconds()).build());
        } catch (RuntimeException failure) {
            log.error("Admin SQS poll stopped: code=ADMIN_SQS_POLL_OR_CONFIGURATION_FAILURE");
            return;
        }
        for (var message : response.messages()) {
            if (message.receiptHandle() == null || message.receiptHandle().isBlank()) {
                log.warn("Admin SQS message retained: code=ADMIN_SQS_RECEIPT_HANDLE_MISSING");
                continue;
            }
            try {
                var result = Objects.requireNonNull(handler.handle(message.body()));
                log.info("Admin SQS consumed: messageId={}, duplicate={}", result.messageId(), result.duplicate());
            } catch (RuntimeException failure) {
                log.warn("Admin SQS message retained: code=ADMIN_SQS_HANDLER_FAILURE");
                continue;
            }
            try {
                // Handler's independent transaction has committed before this receipt handle is acknowledged.
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(properties.queueUrl())
                        .receiptHandle(message.receiptHandle()).build());
            } catch (RuntimeException failure) {
                log.warn("Admin SQS delete failed after commit: code=ADMIN_SQS_DELETE_FAILURE");
            }
        }
    }
}
