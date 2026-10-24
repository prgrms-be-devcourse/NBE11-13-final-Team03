package com.team3.notification.sqs;

import com.team3.notification.config.NotificationSqsProperties;
import com.team3.notification.domain.entity.ReceptionStatus;
import com.team3.notification.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class NotificationSqsConsumerTest {
    private final SqsClient sqs = mock(SqsClient.class);
    private final NotificationSqsTopology topology = mock(NotificationSqsTopology.class);
    private final NotificationMessageHandler handler = mock(NotificationMessageHandler.class);
    private final NotificationSqsProperties properties = new NotificationSqsProperties(true,
            "https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications",
            "https://sqs.ap-northeast-2.amazonaws.com/123456789012/yon-notifications-dlq",
            "ap-northeast-2", "", 10, 120);
    private final NotificationSqsConsumer consumer = new NotificationSqsConsumer(sqs, properties, topology, handler);
    private final UUID applicationId = UUID.randomUUID();

    @Test
    void handlesBeforeDeleteAndAcknowledgesEveryCommittedOutcomeUsingCurrentReceiptHandle() {
        for (ReceptionStatus status : ReceptionStatus.values()) {
            reset(sqs, handler, topology);
            deliver("current-receipt");
            when(handler.handle("body")).thenReturn(new NotificationReceptionResult(applicationId, status, null, false));

            consumer.runOnce();

            var order = inOrder(topology, sqs, handler);
            order.verify(topology).verify();
            order.verify(sqs).receiveMessage(any(ReceiveMessageRequest.class));
            order.verify(handler).handle("body");
            order.verify(sqs).deleteMessage(argThat((DeleteMessageRequest request) ->
                    request.queueUrl().equals(properties.queueUrl()) && request.receiptHandle().equals("current-receipt")));
        }
    }

    @Test
    void processingErrorsNeverDeleteAndAreLeftForRedrive() {
        for (RuntimeException failure : new RuntimeException[]{new IllegalArgumentException("SECRET bad body"),
                new MessageIdentityConflictException(), new DataAccessResourceFailureException("SECRET db error")}) {
            reset(sqs, handler);
            deliver("current-receipt");
            doThrow(failure).when(handler).handle("body");

            assertThatCode(consumer::runOnce).doesNotThrowAnyException();

            verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
        }
    }

    @Test
    void deleteFailureRedeliveryReusesHandlerAndNewestReceiptHandle() {
        deliver("first-receipt");
        when(handler.handle("body")).thenReturn(new NotificationReceptionResult(applicationId, ReceptionStatus.CREATED, 1L, false))
                .thenReturn(new NotificationReceptionResult(applicationId, ReceptionStatus.CREATED, 1L, true));
        doThrow(new IllegalStateException("SECRET deletion error")).doReturn(DeleteMessageResponse.builder().build())
                .when(sqs).deleteMessage(any(DeleteMessageRequest.class));
        consumer.runOnce();

        deliver("new-receipt");
        consumer.runOnce();

        verify(handler, times(2)).handle("body");
        verify(sqs).deleteMessage(argThat((DeleteMessageRequest request) -> request.receiptHandle().equals("first-receipt")));
        verify(sqs).deleteMessage(argThat((DeleteMessageRequest request) -> request.receiptHandle().equals("new-receipt")));
    }

    @Test
    void topologyOrReceiveFailureNeverCallsHandlerOrDelete() {
        doThrow(new IllegalStateException("SQS_TOPOLOGY_INVALID")).when(topology).verify();
        consumer.runOnce();
        verifyNoInteractions(sqs, handler);

        reset(topology);
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenThrow(new IllegalStateException("SECRET SDK failure"));
        consumer.runOnce();
        verifyNoInteractions(handler);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void receivesOneMessageWithConfiguredLongPollingAndVisibilityAndDoesNothingWhenEmpty() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());
        consumer.runOnce();
        verify(sqs).receiveMessage(argThat((ReceiveMessageRequest request) -> request.maxNumberOfMessages() == 1
                && request.waitTimeSeconds() == 10 && request.visibilityTimeout() == 120
                && request.queueUrl().equals(properties.queueUrl())));
        verifyNoInteractions(handler);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test
    void missingReceiptHandleNeverCommitsUnacknowledgeableMessage() {
        deliver("");
        consumer.runOnce();
        verifyNoInteractions(handler);
        verify(sqs, never()).deleteMessage(any(DeleteMessageRequest.class));
    }

    private void deliver(String handle) {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(Message.builder().messageId("sqs-generated-id").receiptHandle(handle).body("body").build()).build());
    }
}
