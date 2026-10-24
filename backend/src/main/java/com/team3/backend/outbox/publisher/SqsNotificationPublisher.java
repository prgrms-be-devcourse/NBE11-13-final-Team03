package com.team3.backend.outbox.publisher;

import com.team3.backend.outbox.service.OutboxPublication;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** SQS acceptance is not Notification consumption. Outbox JSON and identity never change on retries. */
public class SqsNotificationPublisher implements NotificationMessagePublisher {
    private static final Set<String> CONFIGURATION_CODES = Set.of("AccessDenied", "AccessDeniedException",
            "InvalidClientTokenId", "UnrecognizedClientException", "ExpiredToken", "ExpiredTokenException",
            "SignatureDoesNotMatch", "InvalidSecurity", "QueueDoesNotExist", "AWS.SimpleQueueService.NonExistentQueue",
            "KmsAccessDenied", "KmsDisabled", "KmsInvalidKeyUsage", "KmsInvalidState", "KmsNotFound",
            "InvalidAddress");
    private final SqsClient sqs;
    private final AwsCredentialsProvider credentials;
    private final String queueUrl;
    private final ObjectMapper mapper;

    public SqsNotificationPublisher(SqsClient sqs, AwsCredentialsProvider credentials, String queueUrl, ObjectMapper mapper) {
        this.sqs = sqs; this.credentials = credentials; this.queueUrl = queueUrl; this.mapper = mapper;
    }

    @Override
    public void publish(OutboxPublication publication) {
        String correlation;
        try {
            var envelope = mapper.readTree(publication.json());
            if (!publication.messageId().toString().equals(envelope.path("messageId").asText())
                    || !Set.of("NOTIFICATION_REQUESTED", "USER_WITHDRAWN").contains(envelope.path("eventType").asText())) {
                throw new IllegalArgumentException("Invalid envelope");
            }
            correlation = UUID.fromString(envelope.path("correlationId").asText()).toString();
        } catch (RuntimeException failure) {
            throw new NotificationPublicationException("INVALID_OUTBOX_ENVELOPE", false);
        }
        try {
            credentials.resolveCredentials();
        } catch (SdkClientException failure) {
            throw NotificationPublicationException.configuration("SQS_CREDENTIALS_UNAVAILABLE");
        }
        try {
            var response = sqs.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(publication.json())
                    .messageAttributes(Map.of("correlationId", MessageAttributeValue.builder()
                            .dataType("String").stringValue(correlation).build())).build());
            if (response.messageId() == null || response.messageId().isBlank()
                    || !md5(publication.json()).equalsIgnoreCase(response.md5OfMessageBody())) {
                throw new NotificationPublicationException("SQS_INVALID_ACKNOWLEDGEMENT", true);
            }
        } catch (SqsException failure) {
            String code = failure.awsErrorDetails() == null ? ""
                    : java.util.Objects.requireNonNullElse(failure.awsErrorDetails().errorCode(), "");
            if (CONFIGURATION_CODES.contains(code) || Set.of(301, 302, 307, 308, 401, 403, 404).contains(failure.statusCode())) {
                throw NotificationPublicationException.configuration("SQS_CONFIGURATION_ERROR");
            }
            boolean retryable = failure.statusCode() >= 500 || failure.statusCode() == 429
                    || Set.of("RequestThrottled", "KmsThrottled", "ThrottlingException").contains(code);
            throw new NotificationPublicationException("SQS_SERVICE_ERROR", retryable);
        } catch (SdkClientException failure) {
            // Timeout can happen after SQS accepted the message: retry with the SAME application messageId.
            throw new NotificationPublicationException("SQS_IO_FAILURE", true);
        }
    }

    private static String md5(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SQS transport checksum algorithm unavailable", failure);
        }
    }
}
