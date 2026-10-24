package com.team3.backend.outbox;

import com.team3.backend.outbox.publisher.*;
import com.team3.backend.outbox.service.OutboxPublication;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqsNotificationPublisherTest {
    private final SqsClient sqs = mock(SqsClient.class);
    private final AwsCredentialsProvider credentials = mock(AwsCredentialsProvider.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SqsNotificationPublisher publisher = new SqsNotificationPublisher(sqs, credentials,
            "https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications", mapper);

    @Test
    void sendsBothEnvelopeTypesUnchangedWithoutFifoFieldsAndRetriesKeepIdentity() throws Exception {
        for (var fixture : new String[]{"notification-requested-v1.json", "user-withdrawn-v1.json"}) {
            reset(sqs);
            var publication = publication(fixture);
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                    .digest(publication.json().getBytes(StandardCharsets.UTF_8)));
            when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder()
                    .messageId("sqs-generated-id").md5OfMessageBody(digest).build());

            publisher.publish(publication);
            publisher.publish(publication);

            var requests = ArgumentCaptor.forClass(SendMessageRequest.class);
            verify(sqs, times(2)).sendMessage(requests.capture());
            for (var request : requests.getAllValues()) {
                assertThat(request.messageBody()).isEqualTo(publication.json());
                assertThat(request.messageGroupId()).isNull();
                assertThat(request.messageDeduplicationId()).isNull();
                assertThat(request.messageAttributes().get("correlationId").stringValue())
                        .isEqualTo(mapper.readTree(publication.json()).path("correlationId").asText());
            }
        }
    }

    @Test
    void invalidEnvelopeFailsBeforeCredentialsOrNetwork() {
        var invalid = new OutboxPublication(1, UUID.randomUUID(), UUID.randomUUID(), "{}");
        assertThatThrownBy(() -> publisher.publish(invalid)).isInstanceOfSatisfying(NotificationPublicationException.class,
                failure -> { assertThat(failure.getMessage()).isEqualTo("INVALID_OUTBOX_ENVELOPE");
                    assertThat(failure.isRetryable()).isFalse(); });
        verifyNoInteractions(credentials, sqs);
    }

    @Test
    void missingCredentialsAndAccessOrQueueErrorsAreConfigurationFailures() throws Exception {
        var publication = publication("user-withdrawn-v1.json");
        when(credentials.resolveCredentials()).thenThrow(SdkClientException.create("SECRET credential provider error"));
        assertThatThrownBy(() -> publisher.publish(publication)).isInstanceOfSatisfying(NotificationPublicationException.class,
                failure -> { assertThat(failure.isConfiguration()).isTrue();
                    assertThat(failure.getMessage()).isEqualTo("SQS_CREDENTIALS_UNAVAILABLE");
                    assertThat(failure.getCause()).isNull(); });
        verifyNoInteractions(sqs);
        reset(credentials);
        for (String code : new String[]{"AccessDenied", "QueueDoesNotExist", "KmsDisabled", "ExpiredToken"}) {
            doThrow(error(400, code)).when(sqs).sendMessage(any(SendMessageRequest.class));
            assertThatThrownBy(() -> publisher.publish(publication)).isInstanceOfSatisfying(NotificationPublicationException.class,
                    failure -> assertThat(failure.isConfiguration()).isTrue());
        }
    }

    @Test
    void throttlingServerFailuresAndTimeoutsRetryButInvalidContentsAreTerminal() throws Exception {
        var publication = publication("notification-requested-v1.json");
        for (RuntimeException error : new RuntimeException[]{error(400, "RequestThrottled"),
                error(503, "ServiceUnavailable"), SdkClientException.create("SECRET network failure")}) {
            doThrow(error).when(sqs).sendMessage(any(SendMessageRequest.class));
            assertThatThrownBy(() -> publisher.publish(publication)).isInstanceOfSatisfying(NotificationPublicationException.class,
                    failure -> { assertThat(failure.isRetryable()).isTrue();
                        assertThat(failure.isConfiguration()).isFalse();
                        assertThat(failure.getMessage()).doesNotContain("SECRET"); });
        }
        doThrow(error(400, "InvalidMessageContents")).when(sqs).sendMessage(any(SendMessageRequest.class));
        assertThatThrownBy(() -> publisher.publish(publication)).isInstanceOfSatisfying(NotificationPublicationException.class,
                failure -> assertThat(failure.isRetryable()).isFalse());
    }

    @Test
    void missingAcknowledgementOrChecksumMismatchNeverMarksPublicationSuccessful() throws Exception {
        var publication = publication("notification-requested-v1.json");
        for (var response : new SendMessageResponse[]{SendMessageResponse.builder().build(),
                SendMessageResponse.builder().messageId("sqs-id").md5OfMessageBody("wrong-checksum").build()}) {
            when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(response);
            assertThatThrownBy(() -> publisher.publish(publication)).isInstanceOfSatisfying(NotificationPublicationException.class,
                    failure -> { assertThat(failure.getMessage()).isEqualTo("SQS_INVALID_ACKNOWLEDGEMENT");
                        assertThat(failure.isRetryable()).isTrue(); });
        }
    }

    private OutboxPublication publication(String fixture) throws Exception {
        String json = new ClassPathResource("contracts/" + fixture).getContentAsString(StandardCharsets.UTF_8);
        return new OutboxPublication(1, UUID.fromString(mapper.readTree(json).path("messageId").asText()), UUID.randomUUID(), json);
    }

    private SqsException error(int status, String code) {
        var builder = SqsException.builder();
        builder.statusCode(status);
        builder.awsErrorDetails(AwsErrorDetails.builder()
                .errorCode(code).errorMessage("SECRET remote body").build());
        // SDK Builder inherits build(): AwsServiceException; BuilderImpl actually creates SqsException.
        return (SqsException) builder.build();
    }
}
